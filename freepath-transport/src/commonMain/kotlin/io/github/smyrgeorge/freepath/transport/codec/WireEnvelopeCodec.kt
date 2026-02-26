package io.github.smyrgeorge.freepath.transport.codec

import io.github.smyrgeorge.freepath.transport.model.FrameType

object WireEnvelopeCodec {

    /** "FREE" in ASCII: 0x46 0x52 0x45 0x45 */
    val MAGIC = byteArrayOf(0x46, 0x52, 0x45, 0x45)
    const val WIRE_VERSION: Byte = 1
    const val HEADER_SIZE = 10  // MAGIC(4) + VERSION(1) + TYPE(1) + LENGTH(4)

    /** Receiver MUST close the connection if LENGTH exceeds this. */
    const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024  // 16 MiB

    fun encode(type: FrameType, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_SIZE) { "Payload size ${payload.size} exceeds maximum allowed $MAX_PAYLOAD_SIZE" }
        val buf = ByteArray(HEADER_SIZE + payload.size)
        MAGIC.copyInto(buf, 0)
        buf[4] = WIRE_VERSION
        buf[5] = frameTypeToWireByte(type)
        val len = payload.size.toLong()
        buf[6] = ((len shr 24) and 0xFF).toByte()
        buf[7] = ((len shr 16) and 0xFF).toByte()
        buf[8] = ((len shr 8) and 0xFF).toByte()
        buf[9] = (len and 0xFF).toByte()
        payload.copyInto(buf, HEADER_SIZE)
        return buf
    }

    data class Header(val version: Byte, val typeByte: Byte, val length: Int)

    fun decodeHeader(buf: ByteArray, offset: Int = 0): Header {
        require(buf.size - offset >= HEADER_SIZE) { "Buffer too small for wire envelope header" }
        for (i in MAGIC.indices) {
            require(buf[offset + i] == MAGIC[i]) { "Invalid magic bytes — not a Freepath connection" }
        }
        val version = buf[offset + 4]
        val typeByte = buf[offset + 5]
        val length = ((buf[offset + 6].toLong() and 0xFF) shl 24 or
                ((buf[offset + 7].toLong() and 0xFF) shl 16) or
                ((buf[offset + 8].toLong() and 0xFF) shl 8) or
                (buf[offset + 9].toLong() and 0xFF)).toInt()
        require(length in 0..MAX_PAYLOAD_SIZE) { "Payload length $length exceeds maximum" }
        return Header(version, typeByte, length)
    }

    private fun frameTypeToWireByte(type: FrameType): Byte = when (type) {
        FrameType.HANDSHAKE -> 0x00
        FrameType.DATA -> 0x01
        FrameType.ACK -> 0x02
        FrameType.CLOSE -> 0x03
        FrameType.UNKNOWN -> error("Cannot encode UNKNOWN frame type onto the wire")
    }
}
