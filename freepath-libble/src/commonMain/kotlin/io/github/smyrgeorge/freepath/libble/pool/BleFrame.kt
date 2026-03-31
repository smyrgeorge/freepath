package io.github.smyrgeorge.freepath.libble.pool

data class BleFrame(val type: BleFrameType, val payload: ByteArray) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleFrame) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()

    companion object {
        const val MAX_PAYLOAD_SIZE = 65_536

        fun encode(type: BleFrameType, payload: ByteArray = byteArrayOf()): ByteArray {
            val len = payload.size
            return ByteArray(5 + len).also { buf ->
                buf[0] = (len shr 24).toByte()
                buf[1] = (len shr 16).toByte()
                buf[2] = (len shr 8).toByte()
                buf[3] = len.toByte()
                buf[4] = type.byte
                payload.copyInto(buf, destinationOffset = 5)
            }
        }

        fun decode(bytes: ByteArray): BleFrame {
            require(bytes.size >= 5) { "Frame too short: ${bytes.size} bytes" }
            val len = ((bytes[0].toInt() and 0xFF) shl 24) or
                    ((bytes[1].toInt() and 0xFF) shl 16) or
                    ((bytes[2].toInt() and 0xFF) shl 8) or
                    (bytes[3].toInt() and 0xFF)
            require(bytes.size == 5 + len) {
                "Frame length mismatch: header says $len bytes, got ${bytes.size - 5}"
            }
            val type = BleFrameType.fromByte(bytes[4])
            val payload = bytes.copyOfRange(5, bytes.size)
            return BleFrame(type, payload)
        }
    }
}
