package io.github.smyrgeorge.freepath.libnet

import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.libble.LibbleModule
import io.github.smyrgeorge.freepath.libnet.client.codec.FrameCodec
import io.github.smyrgeorge.freepath.libnet.client.model.ReassemblyBuffer
import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent
import io.github.smyrgeorge.freepath.libp2p.Libp2pModule
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.classic.debug
import io.github.smyrgeorge.log4k.classic.error
import io.github.smyrgeorge.log4k.classic.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class LibnetModuleImpl(
    private val libble: LibbleModule,
    private val libp2p: Libp2pModule,
) : LibnetModule {
    private val log = Logger.of(this::class)
    private val started = AtomicBoolean(false)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _requests: Channel<NetRequest> = Channel(capacity = 1000, onBufferOverflow = BufferOverflow.DROP_LATEST)
    override val requests: ReceiveChannel<NetRequest> = _requests

    private val pendingMutex = Mutex()
    private val pendingRequests = mutableMapOf<Long, Transport>()

    // Keyed by (senderId, transferId) to avoid collisions between concurrent senders.
    private val reassemblyBuffers = mutableMapOf<Pair<String, Long>, ReassemblyBuffer>()

    override fun start(peerId: String) {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        scope.launch {
            libp2p.requests.consumeAsFlow().collect { req ->
                runCatching { handleIncoming(req.senderId, req.recipientId, req.reqId, req.payload, Transport.LIBP2P) }
                    .onFailure { log.error(it) { "Unhandled error processing LIBP2P request reqId=${req.reqId}" } }
            }
        }
        scope.launch {
            libble.requests.consumeAsFlow().collect { req ->
                runCatching { handleIncoming(req.senderId, peerId, req.reqId, req.payload, Transport.LIBBLE) }
                    .onFailure { log.error(it) { "Unhandled error processing LIBBLE request reqId=${req.reqId}" } }
            }
        }
        scope.launch {
            while (true) {
                delay(BUFFER_CLEANUP_INTERVAL)
                pendingMutex.withLock {
                    val expired = reassemblyBuffers.entries
                        .filter { (_, buf) -> Clock.System.now() - buf.updatedAt > BUFFER_TTL }
                        .map { it.key }
                    if (expired.isNotEmpty()) {
                        log.warn { "Evicting ${expired.size} stale reassembly buffer(s)" }
                        expired.forEach { reassemblyBuffers.remove(it) }
                    }
                }
            }
        }
    }

    override fun stop() {
        _requests.close()
        scope.cancel()
    }

    override suspend fun request(
        reqId: Long,
        peerId: String,
        payload: ByteArray,
        onFrameSent: (reqId: Long, frameIndex: Int, frameCount: Int) -> Unit,
    ): Result<ByteArray> = runCatching {
        val onLan = peerId in libp2p.metrics.value.value.identifiedPeers
        val onBle = peerId in libble.metrics.value.value.identifiedPeers
        val useBle = if (PREFER_BLE) onBle || !onLan else !onLan && onBle

        val transport: Transport = when {
            useBle && onBle -> Transport.LIBBLE
            onLan -> Transport.LIBP2P
            else -> error("Peer $peerId is not reachable on any transport (LAN or BLE)")
        }

        if (ENABLE_FRAMING) {
            val chunks = FrameCodec.split(payload, transport.mtu)
            val transferId = Random.nextLong()
            log.debug("request: routing to $transport for peerId=$peerId (${chunks.size} frames)")
            var lastResponse = ByteArray(0)
            for ((index, chunk) in chunks.withIndex()) {
                val wrapped = FrameCodec.wrap(transferId, index, chunks.size, chunk)
                lastResponse = sendFramed(transport, peerId, wrapped)
                onFrameSent(reqId, index, chunks.size)
            }
            lastResponse
        } else {
            log.debug("request: routing to $transport for peerId=$peerId")
            val response = sendFramed(transport, peerId, payload)
            onFrameSent(reqId, 0, 1)
            response
        }
    }

    override suspend fun sendResponse(reqId: Long, payload: ByteArray) {
        val transport = pendingMutex.withLock { pendingRequests.remove(reqId) } ?: run {
            log.warn("sendResponse: unknown reqId=$reqId — already responded or timed out")
            return
        }
        require(payload.size <= transport.mtu) {
            "Response payload too large: ${payload.size} bytes (max ${transport.mtu} for $transport)"
        }
        log.debug("sendResponse: reqId=$reqId via $transport (${payload.size} bytes)")
        when (transport) {
            Transport.LIBP2P -> libp2p.sendResponse(reqId, payload)
            Transport.LIBBLE -> libble.sendResponse(reqId, payload)
        }
    }

    override suspend fun sendResponseFailed(reqId: Long, error: String) {
        val transport = pendingMutex.withLock { pendingRequests.remove(reqId) } ?: run {
            log.warn("sendResponseFailed: unknown reqId=$reqId — already responded or timed out")
            return
        }
        require(error.length <= transport.mtu) {
            "Error message too large: ${error.length} chars (max ${transport.mtu} for $transport)"
        }
        log.debug("sendResponseFailed: reqId=$reqId via $transport error=$error")
        when (transport) {
            Transport.LIBP2P -> libp2p.sendResponseFailed(reqId, error)
            Transport.LIBBLE -> libble.sendResponseFailed(reqId, error)
        }
    }

    private suspend fun sendFramed(transport: Transport, peerId: String, payload: ByteArray): ByteArray =
        when (transport) {
            Transport.LIBBLE -> when (val r = libble.request(REQUEST_TIMEOUT, peerId, payload)) {
                is LibbleEvent.ResponseReceived -> r.payload
                is LibbleEvent.RequestFailed -> error(r.error)
            }

            Transport.LIBP2P -> when (val r = libp2p.request(REQUEST_TIMEOUT, peerId, payload)) {
                is Libp2pEvent.ResponseReceived -> r.payload
                is Libp2pEvent.RequestFailed -> error(r.error)
            }
        }

    private suspend fun handleIncoming(
        senderId: String,
        recipientId: String,
        reqId: Long,
        payload: ByteArray,
        transport: Transport,
    ) {
        if (ENABLE_FRAMING) {
            handleIncomingFrame(senderId, recipientId, reqId, payload, transport)
        } else {
            pendingMutex.withLock { pendingRequests[reqId] = transport }
            _requests.trySend(NetRequest(senderId, recipientId, reqId, payload, transport)).onFailure {
                log.error { "Failed to send message to requests channel: $it" }
                pendingMutex.withLock { pendingRequests.remove(reqId) }
            }
        }
    }

    private suspend fun handleIncomingFrame(
        senderId: String,
        recipientId: String,
        reqId: Long,
        payload: ByteArray,
        transport: Transport,
    ) {
        val header = runCatching { FrameCodec.unwrap(payload) }.getOrElse {
            log.error { "handleIncomingFrame: malformed frame from $senderId — ${it.message}" }
            when (transport) {
                Transport.LIBP2P -> libp2p.sendResponseFailed(reqId, "Malformed frame")
                Transport.LIBBLE -> libble.sendResponseFailed(reqId, "Malformed frame")
            }
            return
        }

        if (header.frameCount == 1) {
            // single-frame transfer — deliver directly
            pendingMutex.withLock { pendingRequests[reqId] = transport }
            _requests.trySend(
                NetRequest(senderId, recipientId, reqId, header.payload, transport)
            ).onFailure {
                log.error { "Failed to send message to requests channel: $it" }
                pendingMutex.withLock { pendingRequests.remove(reqId) }
            }
            return
        }

        if (header.frameIndex < header.frameCount - 1) {
            // intermediate frame — buffer it and ACK immediately (bypasses pendingRequests)
            pendingMutex.withLock {
                reassemblyBuffers.getOrPut(Pair(senderId, header.transferId)) {
                    ReassemblyBuffer(header.frameCount)
                }.add(header.frameIndex, header.payload)
            }
            when (transport) {
                Transport.LIBP2P -> libp2p.sendResponse(reqId, ByteArray(0))
                Transport.LIBBLE -> libble.sendResponse(reqId, ByteArray(0))
            }
            return
        }

        // last frame — complete the buffer, assemble, deliver
        val buf = pendingMutex.withLock {
            val b = reassemblyBuffers.remove(Pair(senderId, header.transferId)) ?: run {
                log.error { "handleIncomingFrame: no buffer for transferId=${header.transferId} from $senderId" }
                return@withLock null
            }
            b.add(header.frameIndex, header.payload)
            pendingRequests[reqId] = transport
            b
        } ?: run {
            when (transport) {
                Transport.LIBP2P -> libp2p.sendResponseFailed(reqId, "Transfer buffer not found")
                Transport.LIBBLE -> libble.sendResponseFailed(reqId, "Transfer buffer not found")
            }
            return
        }
        val assembled = buf.assemble()

        _requests.trySend(NetRequest(senderId, recipientId, reqId, assembled, transport)).onFailure {
            log.error { "Failed to send assembled message to requests channel: $it" }
            pendingMutex.withLock { pendingRequests.remove(reqId) }
        }
    }

    companion object {
        private val REQUEST_TIMEOUT = 30.seconds

        /** How long a partial reassembly buffer is kept before being evicted. */
        private val BUFFER_TTL = 60.seconds

        /** How often the cleanup coroutine runs to evict stale reassembly buffers. */
        private val BUFFER_CLEANUP_INTERVAL = 30.seconds

        /** Set to true to prefer BLE over LAN for outbound requests. For debugging only. */
        private const val PREFER_BLE = false

        /** Set to false to disable framing and send payloads as-is (single transport request). */
        private const val ENABLE_FRAMING = true
    }
}
