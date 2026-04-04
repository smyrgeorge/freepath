package io.github.smyrgeorge.freepath.libnet

import io.github.smyrgeorge.freepath.libnet.client.model.ReassemblyBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReassemblyBufferTest {

    @Test
    fun `single frame completes buffer immediately`() {
        val buf = ReassemblyBuffer(1, "sender", "recipient", Transport.LIBP2P)
        val complete = buf.add(0, byteArrayOf(1, 2, 3))
        assertTrue(complete)
        assertContentEquals(byteArrayOf(1, 2, 3), buf.assemble())
    }

    @Test
    fun `three frames in order complete and assemble correctly`() {
        val buf = ReassemblyBuffer(3, "sender", "recipient", Transport.LIBP2P)
        assertFalse(buf.add(0, byteArrayOf(1, 2)))
        assertFalse(buf.add(1, byteArrayOf(3, 4)))
        assertTrue(buf.add(2, byteArrayOf(5, 6)))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), buf.assemble())
    }

    @Test
    fun `three frames out of order complete and assemble correctly`() {
        val buf = ReassemblyBuffer(3, "sender", "recipient", Transport.LIBBLE)
        assertFalse(buf.add(2, byteArrayOf(5, 6)))
        assertFalse(buf.add(0, byteArrayOf(1, 2)))
        assertTrue(buf.add(1, byteArrayOf(3, 4)))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), buf.assemble())
    }

    @Test
    fun `partial buffer is not complete`() {
        val buf = ReassemblyBuffer(3, "sender", "recipient", Transport.LIBP2P)
        assertFalse(buf.add(0, byteArrayOf(1)))
        assertFalse(buf.add(1, byteArrayOf(2)))
        // frame 2 not yet received — buffer incomplete
    }

    @Test
    fun `assemble with empty frame chunks produces correct result`() {
        val buf = ReassemblyBuffer(2, "sender", "recipient", Transport.LIBP2P)
        buf.add(0, byteArrayOf(1, 2, 3))
        buf.add(1, ByteArray(0))
        assertContentEquals(byteArrayOf(1, 2, 3), buf.assemble())
    }
}
