package io.github.smyrgeorge.freepath.libble.pool

enum class BleFrameType(val byte: Byte) {
    EXCHANGE(0x01), REQUEST(0x02), RESPONSE(0x03), PING(0x04), PONG(0x05);

    companion object {
        fun fromByte(b: Byte): BleFrameType =
            entries.firstOrNull { it.byte == b }
                ?: throw IllegalArgumentException("Unknown frame type: 0x${b.toString(16)}")
    }
}
