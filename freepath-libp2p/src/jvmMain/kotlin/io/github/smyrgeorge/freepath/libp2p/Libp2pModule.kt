package io.github.smyrgeorge.freepath.libp2p

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

actual class Libp2pModule actual constructor() : AbstractLibp2pModule() {

    private val nodeHandle = AtomicReference<Long?>(null)
    private val handlerId: Long = handlerCounter.incrementAndGet()

    private var mdns: MdnsPeerDiscovery? = null
    private val mdnsStarted = AtomicBoolean(false)

    actual fun setEventHandler(handler: suspend (Libp2pEvent) -> Unit): Libp2pModule {
        appHandler = handler
        return this
    }

    actual override suspend fun start(
        peerId: String,
        sigKeyPrivate: ByteArray,
        listenAddrs: String,
    ) {
        if (nodeHandle.get() != null) return
        ensureNativeLoaded()
        mdns = MdnsPeerDiscovery(peerId)
        eventHandlers[handlerId] = ::dispatch
        val handle = withContext(dispatcher) { Libp2pJni.start(peerId, sigKeyPrivate, listenAddrs, handlerId) }
        if (handle == 0L) {
            nodeHandle.set(null)
            eventHandlers.remove(handlerId)
            mdns = null
            error("libp2p_start returned null — check stderr")
        }
        nodeHandle.set(handle)
    }

    actual override suspend fun stop() {
        val h = nodeHandle.getAndSet(null) ?: return
        requests.close()
        mdns?.stop()
        mdns = null
        mdnsStarted.set(false)
        scope.coroutineContext.cancelChildren()
        eventHandlers.remove(handlerId)
        metrics.close()
        withContext(dispatcher) { Libp2pJni.stop(h) }
    }

    actual override suspend fun dial(multiaddr: String) {
        val h = nodeHandle.get() ?: return
        withContext(dispatcher) { Libp2pJni.dial(h, multiaddr) }
    }

    actual override suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray) {
        val h = nodeHandle.get() ?: return
        withContext(dispatcher) { Libp2pJni.sendRequest(h, peerId, reqId, payload) }
    }

    actual override suspend fun sendResponse(reqId: Long, payload: ByteArray) {
        val h = nodeHandle.get() ?: return
        withContext(dispatcher) { Libp2pJni.sendResponse(h, reqId, payload) }
    }

    actual override suspend fun sendResponseFailed(reqId: Long, error: String) {
        val h = nodeHandle.get() ?: return
        withContext(dispatcher) { Libp2pJni.sendResponseFailed(h, reqId, error) }
    }

    actual override fun onFirstListenAddr(port: Int) {
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
        private val handlerCounter = java.util.concurrent.atomic.AtomicLong(0)
        internal fun getEventHandler(id: Long): ((Libp2pEvent) -> Unit)? = eventHandlers[id]

        private fun ensureNativeLoaded() {
            if (!nativeLoaded.compareAndSet(false, true)) return
            val libName = when {
                System.getProperty("os.name").orEmpty().lowercase().contains("mac") ->
                    if (System.getProperty("os.arch") == "aarch64") "libfreepath_libp2p_aarch64.dylib"
                    else "libfreepath_libp2p.dylib"

                else -> "libfreepath_libp2p.so"
            }
            val gradlePath = System.getProperty("freepath.libp2p.native.path")
            val libFile = if (gradlePath != null) {
                File(gradlePath, libName).takeIf { it.exists() }
                    ?: File(gradlePath, "libfreepath_libp2p.dylib")
            } else {
                val stream = Libp2pModule::class.java.getResourceAsStream("/$libName")
                    ?: error("$libName not found on classpath")
                val ext = if (libName.endsWith(".dylib")) ".dylib" else ".so"
                val tmp = File.createTempFile("freepath_libp2p", ext).also { it.deleteOnExit() }
                stream.use { it.copyTo(tmp.outputStream()) }
                tmp
            }
            System.load(libFile.absolutePath)
        }
    }
}
