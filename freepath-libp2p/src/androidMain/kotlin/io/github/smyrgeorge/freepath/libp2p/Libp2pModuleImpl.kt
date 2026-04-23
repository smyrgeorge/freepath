package io.github.smyrgeorge.freepath.libp2p

import io.github.smyrgeorge.freepath.util.AndroidContextHolder
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class Libp2pModuleImpl : AbstractLibp2pModule() {

    private val nodeHandle = AtomicReference<Long?>(null)
    private val handlerId: Long = handlerCounter.incrementAndGet()

    private var mdns: MdnsPeerDiscovery? = null
    private val mdnsStarted = AtomicBoolean(false)

    override suspend fun start(
        peerId: String,
        sigKeyPrivate: ByteArray,
        listenAddrs: String,
        relayAddrs: String,
        contactLookup: (String) -> Boolean,
    ) {
        if (nodeHandle.get() != null) return
        ensureNativeLoaded()
        val ctx = requireNotNull(AndroidContextHolder.applicationContext) {
            "LibP2pModule.applicationContext must be set before start() on Android"
        }
        mdns = MdnsPeerDiscovery(peerId, ctx)
        contactLookups[handlerId] = contactLookup
        eventHandlers[handlerId] = ::dispatch
        val handle = withContext(dispatcher) {
            Libp2pJni.start(peerId, sigKeyPrivate, listenAddrs, relayAddrs, handlerId)
        }
        if (handle == 0L) {
            nodeHandle.set(null)
            eventHandlers.remove(handlerId)
            contactLookups.remove(handlerId)
            mdns = null
            error("libp2p_start returned null — check stderr")
        }
        nodeHandle.set(handle)
    }

    override suspend fun stop() {
        val h = nodeHandle.getAndSet(null) ?: return
        requests.close()
        mdns?.stop()
        mdns = null
        mdnsStarted.set(false)
        scope.coroutineContext.cancelChildren()
        eventHandlers.remove(handlerId)
        contactLookups.remove(handlerId)
        metrics.close()
        withContext(dispatcher) { Libp2pJni.stop(h) }
    }

    override suspend fun dial(multiaddr: String) {
        val h = nodeHandle.get() ?: return
        withContext(dispatcher) { Libp2pJni.dial(h, multiaddr) }
    }

    override suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray) {
        val h = nodeHandle.get() ?: return
        withContext(dispatcher) { Libp2pJni.sendRequest(h, peerId, reqId, payload) }
    }

    override suspend fun sendResponse(reqId: Long, payload: ByteArray) {
        val h = nodeHandle.get() ?: return
        withContext(dispatcher) { Libp2pJni.sendResponse(h, reqId, payload) }
    }

    override suspend fun sendResponseFailed(reqId: Long, error: String) {
        val h = nodeHandle.get() ?: return
        withContext(dispatcher) { Libp2pJni.sendResponseFailed(h, reqId, error) }
    }

    override fun onFirstListenAddr(port: Int) {
        if (!mdnsStarted.compareAndSet(false, true)) return
        val m = mdns ?: return
        scope.launch {
            m.start(
                port = port,
                onPeerDiscovered = { nodeId, address ->
                    val multiaddr = lanAddressToMultiaddr(address) ?: return@start
                    scope.launch { dial(multiaddr) }
                    dispatch(Libp2pEvent.MdnsPeerDiscovered(nodeId, address))
                },
                onPeerRemoved = { nodeId ->
                    dispatch(Libp2pEvent.MdnsPeerExpired(nodeId))
                },
            )
        }
    }

    companion object {
        private val nativeLoaded = AtomicBoolean(false)
        private val eventHandlers = ConcurrentHashMap<Long, (Libp2pEvent) -> Unit>()
        private val contactLookups = ConcurrentHashMap<Long, (String) -> Boolean>()
        private val handlerCounter = AtomicLong(0)

        internal fun getEventHandler(id: Long): ((Libp2pEvent) -> Unit)? = eventHandlers[id]
        internal fun isKnownContact(handlerId: Long, peerId: String): Boolean =
            contactLookups[handlerId]?.invoke(peerId) ?: false

        private fun ensureNativeLoaded() {
            if (!nativeLoaded.compareAndSet(false, true)) return
            System.loadLibrary("freepath_libp2p")
        }
    }
}
