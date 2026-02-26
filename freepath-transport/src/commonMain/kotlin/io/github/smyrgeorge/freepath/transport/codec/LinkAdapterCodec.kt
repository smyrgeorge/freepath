package io.github.smyrgeorge.freepath.transport.codec

import io.github.smyrgeorge.freepath.transport.model.LinkAdapterPacket

object LinkAdapterCodec {

    fun encode(packet: LinkAdapterPacket): ByteArray {
        val buf = ByteArray(LinkAdapterPacket.HEADER_SIZE + packet.data.size)
        var off = 0
        off = BinaryCodec.writeUInt32BE(buf, off, packet.seq)
        off = BinaryCodec.writeUInt16BE(buf, off, packet.fragIndex)
        off = BinaryCodec.writeUInt16BE(buf, off, packet.fragCount)
        off = BinaryCodec.writeUInt32BE(buf, off, packet.data.size.toLong())
        packet.data.copyInto(buf, off)
        return buf
    }

    fun decode(buf: ByteArray, offset: Int = 0): Pair<LinkAdapterPacket, Int> {
        require(buf.size - offset >= LinkAdapterPacket.HEADER_SIZE) {
            "Buffer too small for Link Adapter Packet header"
        }
        var off = offset
        val seq = BinaryCodec.readUInt32BE(buf, off); off += 4
        val fragIndex = BinaryCodec.readUInt16BE(buf, off); off += 2
        val fragCount = BinaryCodec.readUInt16BE(buf, off); off += 2
        val length = BinaryCodec.readUInt32BE(buf, off).toInt(); off += 4

        require(fragCount > 0) { "fragCount must be > 0" }
        require(fragIndex < fragCount) { "fragIndex must be < fragCount" }
        require(fragCount <= LinkAdapterPacket.MAX_FRAG_COUNT) {
            "fragCount $fragCount exceeds maximum ${LinkAdapterPacket.MAX_FRAG_COUNT}"
        }
        require(buf.size - off >= length) { "Buffer too small for packet data" }

        val data = buf.copyOfRange(off, off + length)
        return Pair(LinkAdapterPacket(seq, fragIndex, fragCount, data), off + length)
    }
}
