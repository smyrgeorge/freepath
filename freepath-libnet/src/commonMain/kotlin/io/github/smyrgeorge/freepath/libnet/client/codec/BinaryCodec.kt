package io.github.smyrgeorge.freepath.libnet.client.codec

internal object BinaryCodec {
    fun writeInt32BE(buf: ByteArray, off: Int, value: Int): Int {
        buf[off] = ((value shr 24) and 0xFF).toByte()
        buf[off + 1] = ((value shr 16) and 0xFF).toByte()
        buf[off + 2] = ((value shr 8) and 0xFF).toByte()
        buf[off + 3] = (value and 0xFF).toByte()
        return off + 4
    }

    fun writeInt64BE(buf: ByteArray, off: Int, value: Long): Int {
        buf[off] = ((value shr 56) and 0xFF).toByte()
        buf[off + 1] = ((value shr 48) and 0xFF).toByte()
        buf[off + 2] = ((value shr 40) and 0xFF).toByte()
        buf[off + 3] = ((value shr 32) and 0xFF).toByte()
        buf[off + 4] = ((value shr 24) and 0xFF).toByte()
        buf[off + 5] = ((value shr 16) and 0xFF).toByte()
        buf[off + 6] = ((value shr 8) and 0xFF).toByte()
        buf[off + 7] = (value and 0xFF).toByte()
        return off + 8
    }
}
