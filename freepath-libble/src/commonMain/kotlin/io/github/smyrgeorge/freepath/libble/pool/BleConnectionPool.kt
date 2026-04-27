package io.github.smyrgeorge.freepath.libble.pool

import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.util.rpc.RpcManager
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.classic.debug
import io.github.smyrgeorge.log4k.classic.info
import io.github.smyrgeorge.log4k.classic.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class BleConnectionPool(
    private val scope: CoroutineScope,
    private val rpc: RpcManager<LibbleEvent.Response>,
    private val psmLookup: suspend (peripheralId: String) -> Int?,
    private val onRequestReceived: suspend (peripheralId: String, reqId: Long, payload: ByteArray, senderPeerId: String) -> Unit,
    private val onPeerRemoved: suspend (peerId: String) -> Unit,
) {
    private val log = Logger.of(this::class)
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, PoolEntry>()

    private class PoolEntry(
        val channel: BleL2capChannel,
        val exchangeFrames: Channel<ByteArray> = Channel(Channel.BUFFERED),
        val pongChannel: Channel<Unit> = Channel(Channel.BUFFERED),
        var keepaliveJob: Job? = null,
        var receiveLoopJob: Job? = null,
        /** The peerId of the remote peer, set when the first REQUEST frame identifies the sender. */
        var peerId: String? = null,
    ) {
        private val entryMutex: Mutex = Mutex()
        suspend inline fun <T> withLock(block: suspend () -> T): T = entryMutex.withLock { block() }
    }

    suspend fun <T> withConnection(
        peripheralId: String,
        block: suspend (channel: BleL2capChannel, exchangeFrames: Channel<ByteArray>) -> T,
    ): T {
        val entry = getOrCreate(peripheralId)
        return entry.withLock {
            check(mutex.withLock { entries[peripheralId] === entry }) {
                "Peripheral $peripheralId evicted from pool"
            }
            block(entry.channel, entry.exchangeFrames)
        }
    }

    suspend fun registerInbound(channel: BleL2capChannel): Channel<ByteArray> {
        val peripheralId = channel.peripheralId
        val entry = PoolEntry(channel = channel)
        val winner = mutex.withLock { entries.getOrPut(peripheralId) { entry } }
        if (winner !== entry) {
            withContext(NonCancellable) { runCatching { channel.close() } }
            return winner.exchangeFrames
        }
        startLoops(peripheralId, winner, channel)
        log.debug("Pool: enrolled $peripheralId (inbound)")
        return winner.exchangeFrames
    }

    /** Close all pool entries. Called from LibbleModule.stop(). */
    suspend fun close() {
        mutex.withLock { entries.keys.toList() }.forEach { remove(it) }
    }

    /**
     * Remove a peripheral from the pool: cancel its jobs and close the channel.
     * Idempotent. Does NOT acquire entryMutex — safe to call from keepaliveLoop.
     */
    suspend fun remove(peripheralId: String) {
        val entry = mutex.withLock { entries.remove(peripheralId) } ?: return
        entry.keepaliveJob?.cancel()
        entry.receiveLoopJob?.cancel()
        withContext(NonCancellable) { runCatching { entry.channel.close() } }
        log.debug("Pool: removed $peripheralId (peerId=${entry.peerId})")
        entry.peerId?.let { onPeerRemoved(it) }
    }

    /**
     * Find the peripheralId of an existing pool entry associated with [peerId].
     * This allows reusing an inbound channel instead of opening a second connection
     * (critical on iOS where CBCentral.identifier ≠ CBPeripheral.identifier).
     */
    suspend fun findEntryForPeer(peerId: String): String? =
        mutex.withLock { entries.entries.firstOrNull { it.value.peerId == peerId }?.key }

    /** Tag a pool entry with the remote peer's [peerId] for bidirectional channel reuse. */
    suspend fun setPeerIdForEntry(peripheralId: String, peerId: String) {
        mutex.withLock { entries[peripheralId]?.peerId = peerId }
    }

    private suspend fun getOrCreate(peripheralId: String): PoolEntry {
        mutex.withLock { entries[peripheralId] }?.let { return it }

        // On iOS the PSM arrives via advertisement local name which may not appear on every
        // advertisement delivery for a cached peripheral. Retry for up to PSM_WAIT_TIMEOUT.
        var psm: Int? = null
        val deadline = Clock.System.now() + PSM_WAIT_TIMEOUT
        while (psm == null && Clock.System.now() < deadline) {
            psm = psmLookup(peripheralId)
            if (psm == null) {
                log.info("Pool: PSM not yet available for $peripheralId, retrying…")
                delay(PSM_RETRY_INTERVAL)
            }
        }
        psm ?: error("Unknown peripheral: $peripheralId — PSM not available after $PSM_WAIT_TIMEOUT")
        log.info("Pool: resolved PSM=$psm for $peripheralId, connecting L2CAP")
        val channel = BleL2capChannel.connect(peripheralId, psm)
        val entry = PoolEntry(channel = channel)

        val winner = mutex.withLock { entries.getOrPut(peripheralId) { entry } }
        if (winner !== entry) {
            withContext(NonCancellable) { runCatching { channel.close() } }
            return winner
        }
        startLoops(peripheralId, winner, channel)
        log.debug("Pool: enrolled $peripheralId (outbound)")
        return winner
    }

    private fun startLoops(peripheralId: String, entry: PoolEntry, channel: BleL2capChannel) {
        entry.receiveLoopJob = scope.launch { receiveLoop(peripheralId, channel) }
        entry.keepaliveJob = scope.launch { keepaliveLoop(peripheralId) }
    }

    private suspend fun receiveLoop(peripheralId: String, channel: BleL2capChannel) {
        log.debug("Pool: receiveLoop started for $peripheralId")
        runCatching {
            channel.incoming.collect { frame ->
                val entry = mutex.withLock { entries[peripheralId] } ?: return@collect
                when (frame.type) {
                    BleFrameType.PING -> runCatching { channel.send(BleFrameType.PONG, byteArrayOf()) }
                    BleFrameType.PONG -> entry.pongChannel.trySend(Unit)
                    BleFrameType.EXCHANGE -> entry.exchangeFrames.trySend(frame.payload)
                    BleFrameType.REQUEST -> routeRequest(peripheralId, frame.payload)
                    BleFrameType.RESPONSE -> routeResponse(peripheralId, frame.payload)
                }
            }
        }.onFailure { e ->
            log.debug("Pool: receiveLoop ended for $peripheralId: ${e.message}")
        }
        // Channel dead (EOF/error/remote disconnected) — evict immediately.
        log.info("Pool: receiveLoop completed for $peripheralId — evicting")
        remove(peripheralId)
    }

    /** Parse 8-byte LE reqId from the start of a payload. Returns null if too short. */
    private fun parseReqId(payload: ByteArray): Long? {
        if (payload.size < 8) return null
        return (0 until 8).fold(0L) { acc, i -> acc or ((payload[i].toLong() and 0xFF) shl (i * 8)) }
    }

    private suspend fun routeRequest(peripheralId: String, payload: ByteArray) {
        // Frame: [8-byte LE reqId] [1-byte peerIdLen] [peerId bytes] [body]
        val reqId = parseReqId(payload) ?: run {
            log.warn("Pool: REQUEST from $peripheralId too short to parse reqId (${payload.size} bytes)")
            return
        }
        if (payload.size < 9) return
        val peerIdLen = payload[8].toInt() and 0xFF
        if (payload.size < 9 + peerIdLen) return
        val senderPeerId = payload.copyOfRange(9, 9 + peerIdLen).decodeToString()
        val body = payload.copyOfRange(9 + peerIdLen, payload.size)
        // Tag the pool entry with the sender's peerId so that future outbound requests
        // to this peer can reuse the same channel (avoids opening a second BLE connection).
        mutex.withLock { entries[peripheralId]?.peerId = senderPeerId }
        log.debug("Pool: REQUEST received from $peripheralId (peerId=$senderPeerId, reqId=$reqId, ${body.size} bytes)")
        onRequestReceived(peripheralId, reqId, body, senderPeerId)
    }

    private fun routeResponse(peripheralId: String, payload: ByteArray) {
        // Frame: [8-byte LE reqId] [1-byte status] [body or error]
        val reqId = parseReqId(payload) ?: run {
            log.warn("Pool: RESPONSE from $peripheralId too short to parse reqId (${payload.size} bytes)")
            return
        }
        if (payload.size < 9) return
        val status = payload[8]
        val body = payload.copyOfRange(9, payload.size)
        val ok = status == 0x00.toByte()
        log.debug("Pool: RESPONSE received from $peripheralId (reqId=$reqId, ok=$ok, ${body.size} bytes)")
        val result = if (ok) LibbleEvent.ResponseReceived(reqId, peripheralId, body)
        else LibbleEvent.RequestFailed(reqId, senderId = peripheralId, error = body.decodeToString())
        scope.launch { rpc.response(reqId, result) }
    }

    private suspend fun keepaliveLoop(peripheralId: String) {
        while (true) {
            delay(KEEPALIVE_INTERVAL)
            val entry = mutex.withLock { entries[peripheralId] } ?: return

            // Drain stale PONGs from a previous cycle.
            while (entry.pongChannel.tryReceive().isSuccess) { /* discard */
            }

            // Try sending PING — if write throws, socket is dead.
            val pingSent = runCatching {
                entry.channel.send(BleFrameType.PING, byteArrayOf())
            }.isSuccess

            if (!pingSent) {
                log.info("Pool: ping write failed for $peripheralId — evicting")
                remove(peripheralId)
                return
            }

            // Wait for PONG.
            val pongReceived = runCatching {
                withTimeout(PONG_TIMEOUT) { entry.pongChannel.receive() }
            }.isSuccess

            if (!pongReceived) {
                log.info("Pool: pong timeout for $peripheralId — evicting")
                remove(peripheralId)
                return
            }
        }
    }

    companion object {
        // PING is 5 bytes on the wire (4-byte header + 1-byte type, empty payload).
        // At 1 ping/second per connection this is negligible bandwidth, but may need
        // increasing if battery drain becomes an issue with many simultaneous connections.
        // Worst-case disconnect detection: KEEPALIVE_INTERVAL + PONG_TIMEOUT = 3 seconds.
        private val KEEPALIVE_INTERVAL = 1.seconds
        private val PONG_TIMEOUT = 2.seconds
        private val PSM_WAIT_TIMEOUT = 10.seconds
        private val PSM_RETRY_INTERVAL = 500.milliseconds
    }
}
