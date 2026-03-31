package io.github.smyrgeorge.freepath.libnet

import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.libble.LibbleModule
import io.github.smyrgeorge.freepath.libnet.LibnetModule.Companion.PREFER_BLE
import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent
import io.github.smyrgeorge.freepath.libp2p.Libp2pModule
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class LibnetModule(
    private val libble: LibbleModule,
    private val libp2p: Libp2pModule,
) {
    private val log = Logger.of(this::class)
    private val started = AtomicBoolean(false)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _requests: Channel<NetRequest> = Channel(capacity = 1000, onBufferOverflow = BufferOverflow.DROP_LATEST)
    val requests: ReceiveChannel<NetRequest> = _requests

    private val pendingMutex = Mutex()
    private val pendingRequests = mutableMapOf<Long, Transport>()

    fun start(
        peerId: String,
    ) {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        scope.launch {
            libp2p.requests.consumeAsFlow().collect { req ->
                pendingMutex.withLock {
                    pendingRequests[req.reqId] = Transport.LIBP2P
                    _requests.trySend(
                        NetRequest(req.senderId, req.recipientId, req.reqId, req.payload, Transport.LIBP2P)
                    ).onFailure {
                        log.error { "Failed to send message to requests channel: $it" }
                    }
                }
            }
        }
        scope.launch {
            libble.requests.consumeAsFlow().collect { req ->
                // req.senderId is already the sender's peerId (embedded in the REQUEST frame header),
                // not an OS-level peripheralId — no lookup needed.
                pendingMutex.withLock {
                    pendingRequests[req.reqId] = Transport.LIBBLE
                    _requests.trySend(
                        NetRequest(req.senderId, peerId, req.reqId, req.payload, Transport.LIBBLE)
                    ).onFailure {
                        log.error { "Failed to send message to requests channel: $it" }
                    }
                }
            }
        }
    }

    fun stop() {
        _requests.close()
        scope.cancel()
    }

    /**
     * Routes an outgoing request to the best available transport.
     * By default LAN (libp2p) is preferred; set [PREFER_BLE] to true to prioritize BLE.
     * Fails fast if the peer is not reachable on any transport.
     */
    suspend fun request(peerId: String, payload: ByteArray): Result<ByteArray> = runCatching {
        val onLan = peerId in libp2p.metrics.value.value.identifiedPeers
        val onBle = peerId in libble.metrics.value.value.identifiedPeers
        val useBle = if (PREFER_BLE) onBle || !onLan else !onLan && onBle

        when {
            useBle && onBle -> {
                log.debug("request: routing to BLE for peerId=$peerId")
                when (val r = libble.request(REQUEST_TIMEOUT, peerId, payload)) {
                    is LibbleEvent.ResponseReceived -> r.payload
                    is LibbleEvent.RequestFailed -> error(r.error)
                }
            }

            onLan -> {
                log.debug("request: routing to LAN for peerId=$peerId")
                when (val r = libp2p.request(REQUEST_TIMEOUT, peerId, payload)) {
                    is Libp2pEvent.ResponseReceived -> r.payload
                    is Libp2pEvent.RequestFailed -> error(r.error)
                }
            }

            else -> {
                error("Peer $peerId is not reachable on any transport (LAN or BLE)")
            }
        }
    }

    /**
     * Sends a success response back via the same transport the request arrived on.
     * No-op if [reqId] is unknown (already responded or timed out).
     */
    suspend fun sendResponse(reqId: Long, payload: ByteArray) {
        val transport = pendingMutex.withLock { pendingRequests.remove(reqId) } ?: run {
            log.warn("sendResponse: unknown reqId=$reqId — already responded or timed out")
            return
        }
        log.debug("sendResponse: reqId=$reqId via $transport (${payload.size} bytes)")
        when (transport) {
            Transport.LIBP2P -> libp2p.sendResponse(reqId, payload)
            Transport.LIBBLE -> libble.sendResponse(reqId, payload)
        }
    }

    /**
     * Sends a failure response back via the same transport the request arrived on.
     * No-op if [reqId] is unknown (already responded or timed out).
     */
    suspend fun sendResponseFailed(reqId: Long, error: String) {
        val transport = pendingMutex.withLock { pendingRequests.remove(reqId) } ?: run {
            log.warn("sendResponseFailed: unknown reqId=$reqId — already responded or timed out")
            return
        }
        log.debug("sendResponseFailed: reqId=$reqId via $transport error=$error")
        when (transport) {
            Transport.LIBP2P -> libp2p.sendResponseFailed(reqId, error)
            Transport.LIBBLE -> libble.sendResponseFailed(reqId, error)
        }
    }

    companion object {
        private val REQUEST_TIMEOUT = 30.seconds

        /** Set to true to prefer BLE over LAN for outbound requests. For debugging only. */
        private const val PREFER_BLE = false
    }
}
