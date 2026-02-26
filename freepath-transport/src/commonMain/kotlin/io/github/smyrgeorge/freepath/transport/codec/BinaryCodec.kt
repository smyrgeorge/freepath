package io.github.smyrgeorge.freepath.transport.codec

internal object BinaryCodec {

    fun writeInt32BE(buf: ByteArray, off: Int, value: Int): Int {
        buf[off] = ((value shr 24) and 0xFF).toByte()
        buf[off + 1] = ((value shr 16) and 0xFF).toByte()
        buf[off + 2] = ((value shr 8) and 0xFF).toByte()
        buf[off + 3] = (value and 0xFF).toByte()
        return off + 4
    }

    fun writeUInt32BE(buf: ByteArray, off: Int, value: Long): Int =
        writeInt32BE(buf, off, value.toInt())

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

    fun writeUInt16BE(buf: ByteArray, off: Int, value: Int): Int {
        buf[off] = ((value shr 8) and 0xFF).toByte()
        buf[off + 1] = (value and 0xFF).toByte()
        return off + 2
    }

    fun readUInt32BE(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
                ((buf[off + 1].toLong() and 0xFF) shl 16) or
                ((buf[off + 2].toLong() and 0xFF) shl 8) or
                (buf[off + 3].toLong() and 0xFF)

    fun readUInt16BE(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
}
