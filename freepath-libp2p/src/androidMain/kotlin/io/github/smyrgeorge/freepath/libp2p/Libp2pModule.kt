package io.github.smyrgeorge.freepath.libp2p

import io.github.smyrgeorge.freepath.libp2p.util.AbstractLibp2pModule
import io.github.smyrgeorge.freepath.util.AndroidContextHolder
import io.github.smyrgeorge.log4k.impl.extensions.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

actual class Libp2pModule actual constructor() : AbstractLibp2pModule(30.seconds) {

    private val nodeHandle = AtomicLong(0)
    private val handlerId: Long = handlerCounter.incrementAndGet()

    private var appHandler: (suspend (Libp2pEvent) -> Unit)? = null
    private var mdns: MdnsPeerDiscovery? = null
    private val mdnsStarted = AtomicBoolean(false)

    actual fun setEventHandler(handler: suspend (Libp2pEvent) -> Unit): Libp2pModule {
        appHandler = handler
        return this
    }

    actual suspend fun start(
        peerId: String,
        sigKeyPrivate: ByteArray,
        listenAddrs: String,
    ) {
        if (!nodeHandle.compareAndSet(0L, STARTING)) return
        ensureNativeLoaded()
        val ctx = requireNotNull(AndroidContextHolder.applicationContext) {
            "LibP2pModule.applicationContext must be set before start() on Android"
        }
        mdns = MdnsPeerDiscovery(peerId, ctx)
        eventHandlers[handlerId] = ::dispatch
        val handle = Libp2pJni.start(peerId, sigKeyPrivate, listenAddrs, handlerId)
        if (handle == 0L) {
            nodeHandle.set(0L)
            eventHandlers.remove(handlerId)
            mdns = null
            throw Libp2pException("libp2p_start returned null — check stderr")
        }
        nodeHandle.set(handle)
    }

    actual suspend fun stop() {
        val h = nodeHandle.getAndSet(0L)
        if (h == 0L || h == STARTING) return
        requests.close()
        mdns?.stop()
        mdns = null
        mdnsStarted.set(false)
        scope.coroutineContext.cancelChildren()
        eventHandlers.remove(handlerId)
        metrics.close()
        Libp2pJni.stop(h)
    }

    actual override suspend fun dial(multiaddr: String) {
        val h = nodeHandle.get()
        if (h <= 0L) return
        Libp2pJni.dial(h, multiaddr)
    }

    actual override suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray) {
        val h = nodeHandle.get()
        if (h <= 0L) return
        Libp2pJni.sendRequest(h, peerId, reqId, payload)
    }

    actual suspend fun sendResponse(reqId: Long, payload: ByteArray) {
        val h = nodeHandle.get()
        if (h <= 0L) return
        Libp2pJni.sendResponse(h, reqId, payload)
    }

    actual suspend fun sendResponseFailed(reqId: Long, error: String) {
        val h = nodeHandle.get()
        if (h <= 0L) return
        Libp2pJni.sendResponseFailed(h, reqId, error)
    }

    private fun dispatch(event: Libp2pEvent) {
        onEvent(event)
        metrics.onEvent(event)
        scope.launch { runCatching { appHandler?.invoke(event) } }
    }

    private fun onEvent(event: Libp2pEvent) {
        when (event) {
            is Libp2pEvent.RequestReceived -> {
                requests.trySend(event).onFailure {
                    log.error { "Failed to send message to requests channel: $it" }
                }
            }

            is Libp2pEvent.ResponseReceived -> launch { rpc.response(event.reqId, event) }
            is Libp2pEvent.RequestFailed -> launch { rpc.response(event.reqId, event) }
            else -> Unit
        }

        if (event !is Libp2pEvent.NewListenAddr) return
        if (!event.addr.startsWith("/ip4/")) return
        val port = event.addr.substringAfterLast("/tcp/").toIntOrNull() ?: return
        if (port == 0) return
        if (!mdnsStarted.compareAndSet(false, true)) return
        val m = mdns ?: return
        scope.launch {
            m.start(
                port = port,
                onPeerDiscovered = { peerId, address ->
                    val multiaddr = lanAddressToMultiaddr(address) ?: return@start
                    scope.launch { dial(multiaddr) }
                    dispatch(Libp2pEvent.MdnsPeerDiscovered(peerId, address))
                },
                onPeerRemoved = { peerId ->
                    dispatch(Libp2pEvent.MdnsPeerExpired(peerId))
                },
            )
        }
    }

    companion object {
        private const val STARTING = -1L
        private val nativeLoaded = AtomicBoolean(false)
        private val eventHandlers = ConcurrentHashMap<Long, (Libp2pEvent) -> Unit>()
        private val handlerCounter = AtomicLong(0)

        internal fun getEventHandler(id: Long): ((Libp2pEvent) -> Unit)? = eventHandlers[id]

        private fun ensureNativeLoaded() {
            if (!nativeLoaded.compareAndSet(false, true)) return
            System.loadLibrary("freepath_libp2p")
        }
    }
}
