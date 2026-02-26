package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.codec.FrameCodec
import io.github.smyrgeorge.freepath.transport.codec.LinkAdapterCodec
import io.github.smyrgeorge.freepath.transport.codec.WireEnvelopeCodec
import io.github.smyrgeorge.freepath.transport.lan.LanLinkAdapter.Companion.LINK_MTU
import io.github.smyrgeorge.freepath.transport.model.Frame
import io.github.smyrgeorge.freepath.transport.model.FrameType
import io.github.smyrgeorge.freepath.transport.model.LinkAdapterPacket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class LanConnection(
    private val socket: Socket,
) {
    private val readChannel = socket.openReadChannel()
    private val writeChannel = socket.openWriteChannel(autoFlush = false)
    private val writeMutex = Mutex()

    /** Reassembly buffer: SEQ → fragments array (sparse, indexed by fragIndex). */
    private val reassembly = mutableMapOf<Long, Array<ByteArray?>>()

    /** Tracks when each SEQ slot was opened, for timeout eviction. */
    private val reassemblyCreatedAt = mutableMapOf<Long, TimeSource.Monotonic.ValueTimeMark>()

    suspend fun sendFrame(frame: Frame) {
        val frameBytes = FrameCodec.encode(frame)
        // For TCP the effective MTU is large; split only if needed
        sendLinkAdapterPackets(frame.seq, frameBytes, frame.type)
    }

    private suspend fun sendLinkAdapterPackets(seq: Long, frameBytes: ByteArray, type: FrameType) {
        val mtu = LINK_MTU
        val fragCount = (frameBytes.size + mtu - 1) / mtu
        writeMutex.withLock {
            for (i in 0 until fragCount) {
                val start = i * mtu
                val end = minOf(start + mtu, frameBytes.size)
                val chunk = frameBytes.copyOfRange(start, end)
                val packet = LinkAdapterPacket(
                    seq = seq,
                    fragIndex = i,
                    fragCount = fragCount,
                    data = chunk,
                )
                val packetBytes = LinkAdapterCodec.encode(packet)
                val envelope = WireEnvelopeCodec.encode(type, packetBytes)
                writeChannel.writeFully(envelope)
            }
            writeChannel.flush()
        }
    }

    suspend fun receiveFrame(): Frame? {
        val headerBuf = ByteArray(WireEnvelopeCodec.HEADER_SIZE)
        return try {
            readChannel.readFully(headerBuf)
            val header = WireEnvelopeCodec.decodeHeader(headerBuf)
            require(header.version == WireEnvelopeCodec.WIRE_VERSION) {
                "Unsupported wire version: ${header.version}"
            }
            val payloadBuf = ByteArray(header.length)
            readChannel.readFully(payloadBuf)
            val (packet, _) = LinkAdapterCodec.decode(payloadBuf)
            reassembleAndDecode(packet)
        } catch (_: Exception) {
            null
        }
    }

    private fun reassembleAndDecode(packet: LinkAdapterPacket): Frame? {
        if (packet.fragCount == 1) {
            // No fragmentation
            return FrameCodec.decode(packet.data)
        }

        // Spec: MUST discard reassembly state that has not completed within the timeout.
        evictStaleReassembly()

        // Spec: MUST enforce a maximum on concurrent SEQ values awaiting reassembly.
        if (!reassembly.containsKey(packet.seq) && reassembly.size >= LinkAdapterPacket.MAX_PENDING_REASSEMBLY) {
            return null  // drop; do not open a new reassembly slot
        }

        val isNew = !reassembly.containsKey(packet.seq)
        val fragments = reassembly.getOrPut(packet.seq) {
            arrayOfNulls(packet.fragCount)
        }
        if (isNew) reassemblyCreatedAt[packet.seq] = TimeSource.Monotonic.markNow()
        fragments[packet.fragIndex] = packet.data

        if (fragments.all { it != null }) {
            reassembly.remove(packet.seq)
            reassemblyCreatedAt.remove(packet.seq)
            val combined = fragments.fold(ByteArray(0)) { acc, frag -> acc + frag!! }
            return FrameCodec.decode(combined)
        }
        return null
    }

    private fun evictStaleReassembly() {
        val timeout = LinkAdapterPacket.REASSEMBLY_TIMEOUT_MS.milliseconds
        val iter = reassembly.iterator()
        while (iter.hasNext()) {
            val seq = iter.next().key
            val mark = reassemblyCreatedAt[seq]
            if (mark != null && mark.elapsedNow() > timeout) {
                iter.remove()
                reassemblyCreatedAt.remove(seq)
            }
        }
    }

    fun close() {
        socket.close()
    }
}
