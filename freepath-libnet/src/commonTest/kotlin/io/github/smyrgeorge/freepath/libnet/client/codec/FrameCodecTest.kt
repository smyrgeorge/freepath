package io.github.smyrgeorge.freepath.libnet.client.codec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrameCodecTest {

    @Test
    fun `wrap and unwrap round-trip preserves all header fields and payload`() {
        val chunk = byteArrayOf(10, 20, 30)
        val wrapped = FrameCodec.wrap(transferId = 42L, frameIndex = 1, frameCount = 3, chunk = chunk)
        assertEquals(FrameCodec.HEADER_SIZE + 3, wrapped.size)
        val header = FrameCodec.unwrap(wrapped)
        assertEquals(42L, header.transferId)
        assertEquals(1, header.frameIndex)
        assertEquals(3, header.frameCount)
        assertContentEquals(chunk, header.payload)
    }

    @Test
    fun `wrap with empty payload produces header-only frame`() {
        val wrapped = FrameCodec.wrap(0L, 0, 1, ByteArray(0))
        assertEquals(FrameCodec.HEADER_SIZE, wrapped.size)
        val header = FrameCodec.unwrap(wrapped)
        assertEquals(0, header.payload.size)
    }

    @Test
    fun `wrap round-trips Long extremes`() {
        for (v in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L)) {
            val header = FrameCodec.unwrap(FrameCodec.wrap(v, 0, 1, ByteArray(0)))
            assertEquals(v, header.transferId)
        }
    }

    @Test
    fun `unwrap throws on input shorter than HEADER_SIZE`() {
        assertFailsWith<IllegalArgumentException> {
            FrameCodec.unwrap(ByteArray(FrameCodec.HEADER_SIZE - 1))
        }
    }

    @Test
    fun `unwrap throws on wrong marker byte`() {
        val bytes = ByteArray(FrameCodec.HEADER_SIZE) { 0x00 }  // marker = 0x00, not 0x01
        assertFailsWith<IllegalArgumentException> { FrameCodec.unwrap(bytes) }
    }

    @Test
    fun `split produces one chunk when payload fits within mtu`() {
        val payload = ByteArray(100) { it.toByte() }
        val chunks = FrameCodec.split(payload, mtu = 65_536)
        assertEquals(1, chunks.size)
        assertContentEquals(payload, chunks[0])
    }

    @Test
    fun `split produces correct chunks for payload that does not divide evenly`() {
        // chunk size = mtu - HEADER_SIZE = (HEADER_SIZE + 10) - HEADER_SIZE = 10
        val mtu = FrameCodec.HEADER_SIZE + 10
        val payload = ByteArray(25) { it.toByte() }
        val chunks = FrameCodec.split(payload, mtu)
        assertEquals(3, chunks.size)
        assertContentEquals(payload.copyOfRange(0, 10), chunks[0])
        assertContentEquals(payload.copyOfRange(10, 20), chunks[1])
        assertContentEquals(payload.copyOfRange(20, 25), chunks[2])
    }

    @Test
    fun `split of empty payload returns one empty chunk`() {
        val chunks = FrameCodec.split(ByteArray(0), mtu = 65_536)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].size)
    }

    @Test
    fun `split chunks concatenate back to original payload`() {
        val payload = ByteArray(100) { it.toByte() }
        val mtu = FrameCodec.HEADER_SIZE + 17  // chunk size = 17
        val chunks = FrameCodec.split(payload, mtu)
        val reassembled = chunks.fold(ByteArray(0)) { a, b -> a + b }
        assertContentEquals(payload, reassembled)
    }
}
