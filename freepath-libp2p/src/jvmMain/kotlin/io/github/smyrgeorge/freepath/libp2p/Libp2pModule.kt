package io.github.smyrgeorge.freepath.libp2p

import io.github.smyrgeorge.freepath.libp2p.metrics.Libp2pMetrics
import io.github.smyrgeorge.freepath.libp2p.util.AbstractLibp2pModule
import io.github.smyrgeorge.log4k.impl.extensions.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

actual class Libp2pModule actual constructor() : AbstractLibp2pModule(30.seconds) {

    actual val metrics: Libp2pMetrics = Libp2pMetrics()

    private val nodeHandle = AtomicLong(0)
    private val handlerId: Long = handlerCounter.incrementAndGet()

    private var appHandler: ((Libp2pEvent) -> Unit)? = null
    private var mdns: MdnsPeerDiscovery? = null
    private val mdnsStarted = AtomicBoolean(false)

    actual fun setEventHandler(handler: (Libp2pEvent) -> Unit) {
        appHandler = handler
    }

    actual suspend fun start(
        nodeId: String,
        sigKeyPrivate: ByteArray,
        listenAddrs: String,
    ) {
        if (!nodeHandle.compareAndSet(0L, STARTING)) return
        ensureNativeLoaded()
        mdns = MdnsPeerDiscovery(nodeId)
        eventHandlers[handlerId] = ::dispatch
        val handle = Libp2pJni.start(nodeId, sigKeyPrivate, listenAddrs, handlerId)
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

    actual suspend fun dial(multiaddr: String) {
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
        appHandler?.invoke(event)
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
        private const val STARTING = -1L
        private val nativeLoaded = AtomicBoolean(false)
        private val eventHandlers = ConcurrentHashMap<Long, (Libp2pEvent) -> Unit>()
        private val handlerCounter = AtomicLong(0)
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
