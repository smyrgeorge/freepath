package io.github.smyrgeorge.freepath.libp2p

import io.github.smyrgeorge.freepath.libp2p.metrics.Libp2pMetrics
import io.github.smyrgeorge.freepath.util.rpc.RpcManager
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

abstract class AbstractLibp2pModule(
    requestTimeout: Duration = 30.seconds
) {
    private val log = Logger.of(this::class)
    private val rpc = RpcManager<Libp2pEvent.Response>(requestTimeout)

    val dispatcher = Dispatchers.IO
    val scope = CoroutineScope(dispatcher + SupervisorJob())
    val requests: Channel<Libp2pEvent.RequestReceived> = Channel(
        capacity = 1000,
        onBufferOverflow = BufferOverflow.DROP_LATEST
    )

    val metrics: Libp2pMetrics = Libp2pMetrics()

    @Volatile
    protected lateinit var appHandler: suspend (Libp2pEvent) -> Unit

    init {
        require(requestTimeout > Duration.ZERO) { "Request timeout must be positive" }
        scope.launch {
            while (true) {
                delay(PING_DELAY)
                val peers = metrics.value.value.identifiedPeers
                if (peers.isNotEmpty()) {
                    log.debug { "Keep-alive: pinging ${peers.size} connected peer(s)" }
                    peers.forEach { peerId ->
                        runCatching { sendRequest(peerId, PING_REQ_ID, byteArrayOf(PING_MARKER)) }
                            .onFailure { log.debug { "Keep-alive ping failed for $peerId: $it" } }
                    }
                }
            }
        }
    }

    protected abstract fun onFirstListenAddr(port: Int)
    abstract suspend fun start(
        peerId: String,
        sigKeyPrivate: ByteArray,
        listenAddrs: String,
        relayAddrs: String,
        contactLookup: (String) -> Boolean = { false },
    )

    abstract suspend fun stop()
    abstract suspend fun dial(multiaddr: String)
    abstract suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray)
    abstract suspend fun sendResponse(reqId: Long, payload: ByteArray)
    abstract suspend fun sendResponseFailed(reqId: Long, error: String)

    suspend fun request(timeout: Duration, peerId: String, payload: ByteArray): Libp2pEvent.Response =
        rpc.request(timeout) { sendRequest(peerId, it, payload) }

    protected fun dispatch(event: Libp2pEvent) {
        onEvent(event)
        if (event.isPing()) return
        metrics.onEvent(event)
        scope.launch { runCatching { appHandler(event) } }
    }

    private fun onEvent(event: Libp2pEvent) {
        when (event) {
            is Libp2pEvent.RequestReceived if (event.payload.firstOrNull() == PING_MARKER) -> {
                // Auto-respond to pings without surfacing to the application.
                scope.launch { runCatching { sendResponse(event.reqId, ByteArray(0)) } }
            }

            is Libp2pEvent.RequestReceived if (event.payload.firstOrNull() != PING_MARKER) -> {
                requests.trySend(event).onFailure {
                    log.error { "Failed to send message to requests channel: $it" }
                }
            }

            is Libp2pEvent.ResponseReceived if (event.reqId != PING_REQ_ID) -> {
                scope.launch { rpc.response(event.reqId, event) }
            }

            is Libp2pEvent.RequestFailed if (event.reqId != PING_REQ_ID) -> {
                scope.launch { rpc.response(event.reqId, event) }
            }

            is Libp2pEvent.NewListenAddr -> {
                if (!event.addr.startsWith("/ip4/")) return
                val port = event.addr.substringAfterLast("/tcp/").toIntOrNull() ?: return
                if (port == 0) return
                onFirstListenAddr(port)
            }

            else -> Unit
        }
    }

    companion object {
        private fun Libp2pEvent.isPing() = when (this) {
            is Libp2pEvent.RequestReceived -> payload.firstOrNull() == PING_MARKER
            is Libp2pEvent.ResponseReceived -> reqId == PING_REQ_ID
            is Libp2pEvent.RequestFailed -> reqId == PING_REQ_ID
            else -> false
        }

        private val PING_DELAY: Duration = 10.seconds

        /** First byte of a ping payload — discriminates pings from RPC requests. */
        private const val PING_MARKER: Byte = 0x00

        /**
         * Reserved reqId for keep-alive pings.
         * Using Long.MIN_VALUE keeps it outside the RpcManager counter range (starts at 0).
         * RpcManager silently discards responses for unknown reqIds (no registered listener).
         */
        private const val PING_REQ_ID: Long = Long.MIN_VALUE
    }
}
