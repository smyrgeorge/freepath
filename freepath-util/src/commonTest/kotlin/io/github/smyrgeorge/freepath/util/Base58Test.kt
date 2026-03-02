package io.github.smyrgeorge.freepath.util

import io.github.smyrgeorge.freepath.util.codec.Base58
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Base58Test {

    // ---- encode: known vectors -------------------------------------------

    @Test
    fun `single zero byte encodes to '1'`() {
        assertEquals("1", Base58.encode(byteArrayOf(0x00)))
    }

    @Test
    fun `three zero bytes encode to '111'`() {
        assertEquals("111", Base58.encode(byteArrayOf(0x00, 0x00, 0x00)))
    }

    @Test
    fun `0x01 encodes to '2'`() {
        // 1 = 1 * 58^0 → alphabet[1] = '2'
        assertEquals("2", Base58.encode(byteArrayOf(0x01)))
    }

    @Test
    fun `0x39 hex57 encodes to z`() {
        // 57 = last index in the BTC alphabet
        assertEquals("z", Base58.encode(byteArrayOf(0x39)))
    }

    @Test
    fun `0x3A hex58 encodes to 21`() {
        // 58 = 1*58 + 0 → '2' '1'
        assertEquals("21", Base58.encode(byteArrayOf(0x3A)))
    }

    @Test
    fun `0xFF hex255 encodes to 5Q`() {
        // 255 = 4*58 + 23 → alphabet[4]='5', alphabet[23]='Q'
        assertEquals("5Q", Base58.encode(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `leading zero preserved 0x00 0x01 encodes to '12'`() {
        // Leading zero byte → leading '1'; remaining 1 → '2'
        assertEquals("12", Base58.encode(byteArrayOf(0x00, 0x01)))
    }

    @Test
    fun `0x62 0x62 0x62 encodes to 'a3gV'`() {
        // Standard Bitcoin Base58 test vector
        assertEquals("a3gV", Base58.encode(byteArrayOf(0x62, 0x62, 0x62)))
    }

    @Test
    fun `0x63 0x63 0x63 encodes to 'aPEr'`() {
        // Standard Bitcoin Base58 test vector
        assertEquals("aPEr", Base58.encode(byteArrayOf(0x63, 0x63, 0x63)))
    }

    // ---- decode: known vectors -------------------------------------------

    @Test
    fun `'1' decodes to single zero byte`() {
        assertContentEquals(byteArrayOf(0x00), Base58.decode("1"))
    }

    @Test
    fun `'111' decodes to three zero bytes`() {
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x00), Base58.decode("111"))
    }

    @Test
    fun `'2' decodes to 0x01`() {
        assertContentEquals(byteArrayOf(0x01), Base58.decode("2"))
    }

    @Test
    fun `'z' decodes to 0x39`() {
        assertContentEquals(byteArrayOf(0x39), Base58.decode("z"))
    }

    @Test
    fun `'21' decodes to 0x3A`() {
        assertContentEquals(byteArrayOf(0x3A), Base58.decode("21"))
    }

    @Test
    fun `'5Q' decodes to 0xFF`() {
        assertContentEquals(byteArrayOf(0xFF.toByte()), Base58.decode("5Q"))
    }

    @Test
    fun `'12' decodes to 0x00 0x01`() {
        assertContentEquals(byteArrayOf(0x00, 0x01), Base58.decode("12"))
    }

    @Test
    fun `'a3gV' decodes to 0x62 0x62 0x62`() {
        assertContentEquals(byteArrayOf(0x62, 0x62, 0x62), Base58.decode("a3gV"))
    }

    @Test
    fun `'aPEr' decodes to 0x63 0x63 0x63`() {
        assertContentEquals(byteArrayOf(0x63, 0x63, 0x63), Base58.decode("aPEr"))
    }

    // ---- round-trip properties -------------------------------------------

    @Test
    fun `round-trip 16-byte node ID`() {
        val nodeId = byteArrayOf(
            0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte(),
            0xDE.toByte(), 0xF0.toByte(), 0x11, 0x22, 0x33, 0x44,
            0x55, 0x66, 0x77, 0x88.toByte()
        )
        assertContentEquals(nodeId, Base58.decode(Base58.encode(nodeId)))
    }

    @Test
    fun `round-trip 32-byte key`() {
        val key = ByteArray(32) { it.toByte() }  // 0x00..0x1F
        assertContentEquals(key, Base58.decode(Base58.encode(key)))
    }

    @Test
    fun `round-trip all-zero bytes`() {
        val zeros = ByteArray(8)
        assertContentEquals(zeros, Base58.decode(Base58.encode(zeros)))
    }

    @Test
    fun `round-trip high-byte values`() {
        val bytes = ByteArray(8) { (0xFF - it).toByte() }  // 0xFF..0xF8
        assertContentEquals(bytes, Base58.decode(Base58.encode(bytes)))
    }

    @Test
    fun `encode and decode are inverse for single bytes across full range`() {
        for (b in 0..255) {
            val input = byteArrayOf(b.toByte())
            assertContentEquals(
                input, Base58.decode(Base58.encode(input)),
                "Round-trip failed for byte 0x${b.toString(16).padStart(2, '0')}"
            )
        }
    }

    // ---- alphabet: excluded characters -----------------------------------

    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    @Test
    fun `encoded output never contains excluded characters`() {
        val excluded = setOf('0', 'O', 'I', 'l')
        repeat(50) { i ->
            val input = ByteArray(16) { (it + i).toByte() }
            val encoded = Base58.encode(input)
            for (ch in encoded) {
                assert(ch !in excluded) {
                    "Encoded output '$encoded' contains excluded character '$ch'"
                }
            }
        }
    }

    // ---- invalid input ---------------------------------------------------

    @Test
    fun `decode throws on excluded char 0`() {
        assertFailsWith<IllegalStateException> { Base58.decode("0") }
    }

    @Test
    fun `decode throws on excluded char O`() {
        assertFailsWith<IllegalStateException> { Base58.decode("O") }
    }

    @Test
    fun `decode throws on excluded char I`() {
        assertFailsWith<IllegalStateException> { Base58.decode("I") }
    }

    @Test
    fun `decode throws on excluded char l`() {
        assertFailsWith<IllegalStateException> { Base58.decode("l") }
    }
}
