package io.github.smyrgeorge.freepath.libp2p

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
        relayAddrs: String,
        contactLookup: (String) -> Boolean,
    ) {
        if (nodeHandle.get() != null) return
        ensureNativeLoaded()
        mdns = MdnsPeerDiscovery(peerId)
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

    actual override suspend fun stop() {
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
        private val contactLookups = ConcurrentHashMap<Long, (String) -> Boolean>()
        private val handlerCounter = AtomicLong(0)
        internal fun getEventHandler(id: Long): ((Libp2pEvent) -> Unit)? = eventHandlers[id]
        internal fun isKnownContact(handlerId: Long, peerId: String): Boolean =
            contactLookups[handlerId]?.invoke(peerId) ?: false

        // System.load (rather than System.loadLibrary) is required here: the classpath branch
        // extracts the native library from a JAR resource into File.createTempFile, and that
        // temp directory is not on java.library.path, so loadLibrary cannot locate it.
        @Suppress("UnsafeDynamicallyLoadedCode")
        private fun ensureNativeLoaded() {
            if (!nativeLoaded.compareAndSet(false, true)) return
            val libName = resolveNativeLibName()
            val gradlePath = System.getProperty("freepath.libp2p.native.path")
            val libFile = if (gradlePath != null) {
                File(gradlePath, libName).also {
                    require(it.exists()) { "native lib not found at ${it.absolutePath}" }
                }
            } else {
                val stream = Libp2pModule::class.java.getResourceAsStream("/$libName")
                    ?: error("$libName not found on classpath")
                val ext = when {
                    libName.endsWith(".dylib") -> ".dylib"
                    libName.endsWith(".dll") -> ".dll"
                    else -> ".so"
                }
                val tmp = File.createTempFile("freepath_libp2p", ext).also { it.deleteOnExit() }
                stream.use { it.copyTo(tmp.outputStream()) }
                tmp
            }
            System.load(libFile.absolutePath)
        }

        private fun resolveNativeLibName(): String {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val arch = System.getProperty("os.arch").orEmpty().lowercase()
            val isArm64 = arch == "aarch64" || arch == "arm64"
            return when {
                os.contains("mac") || os.contains("darwin") ->
                    if (isArm64) "libfreepath_libp2p_aarch64.dylib"
                    else "libfreepath_libp2p.dylib"

                os.contains("windows") ->
                    if (isArm64) "freepath_libp2p_aarch64.dll"
                    else "freepath_libp2p.dll"

                else ->
                    if (isArm64) "libfreepath_libp2p_aarch64.so"
                    else "libfreepath_libp2p.so"
            }
        }
    }
}
