package io.github.smyrgeorge.freepath.libnet.client.codec

internal object FrameCodec {
    const val MARKER: Byte = 0x01
    const val HEADER_SIZE = 17  // 1 (marker) + 8 (transferId) + 4 (frameIndex) + 4 (frameCount)
    const val MAX_FRAME_COUNT = 65_536

    data class FrameHeader(
        val transferId: Long,
        val frameIndex: Int,
        val frameCount: Int,
        val payload: ByteArray,
    )

    fun wrap(transferId: Long, frameIndex: Int, frameCount: Int, chunk: ByteArray): ByteArray {
        val buf = ByteArray(HEADER_SIZE + chunk.size)
        var off = 0
        buf[off++] = MARKER
        off = BinaryCodec.writeInt64BE(buf, off, transferId)
        off = BinaryCodec.writeInt32BE(buf, off, frameIndex)
        BinaryCodec.writeInt32BE(buf, off, frameCount)
        chunk.copyInto(buf, HEADER_SIZE)
        return buf
    }

    fun unwrap(bytes: ByteArray): FrameHeader {
        require(bytes.size >= HEADER_SIZE) { "Frame too short: ${bytes.size} bytes" }
        require(bytes[0] == MARKER) { "Invalid frame marker: ${bytes[0]}" }
        var off = 1
        val transferId = readInt64BE(bytes, off); off += 8
        val frameIndex = readInt32BE(bytes, off); off += 4
        val frameCount = readInt32BE(bytes, off)
        require(frameCount in 1..MAX_FRAME_COUNT) { "Invalid frameCount: $frameCount" }
        require(frameIndex in 0 until frameCount) { "Invalid frameIndex: $frameIndex (frameCount=$frameCount)" }
        return FrameHeader(transferId, frameIndex, frameCount, bytes.copyOfRange(HEADER_SIZE, bytes.size))
    }

    fun split(payload: ByteArray, mtu: Int): List<ByteArray> {
        val chunkSize = mtu - HEADER_SIZE
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        do {
            val end = minOf(offset + chunkSize, payload.size)
            chunks.add(payload.copyOfRange(offset, end))
            offset = end
        } while (offset < payload.size)
        return chunks
    }

    private fun readInt64BE(buf: ByteArray, off: Int): Long =
        (buf[off].toLong() and 0xFF shl 56) or
        (buf[off + 1].toLong() and 0xFF shl 48) or
        (buf[off + 2].toLong() and 0xFF shl 40) or
        (buf[off + 3].toLong() and 0xFF shl 32) or
        (buf[off + 4].toLong() and 0xFF shl 24) or
        (buf[off + 5].toLong() and 0xFF shl 16) or
        (buf[off + 6].toLong() and 0xFF shl 8) or
        (buf[off + 7].toLong() and 0xFF)

    private fun readInt32BE(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF shl 24) or
        (buf[off + 1].toInt() and 0xFF shl 16) or
        (buf[off + 2].toInt() and 0xFF shl 8) or
        (buf[off + 3].toInt() and 0xFF)
}
