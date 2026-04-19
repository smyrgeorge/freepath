package io.github.smyrgeorge.freepath.util.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeyPairTest {

    @Test
    fun `equal when private and public bytes match`() {
        val a = KeyPair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val b = KeyPair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `not equal when private key differs`() {
        val a = KeyPair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val b = KeyPair(byteArrayOf(9, 9, 9), byteArrayOf(4, 5, 6))
        assertNotEquals(a, b)
    }

    @Test
    fun `not equal when public key differs`() {
        val a = KeyPair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val b = KeyPair(byteArrayOf(1, 2, 3), byteArrayOf(9, 9, 9))
        assertNotEquals(a, b)
    }

    @Test
    fun `reference equal to self`() {
        val a = KeyPair(byteArrayOf(1), byteArrayOf(2))
        @Suppress("KotlinConstantConditions")
        assertTrue(a == a)
    }

    @Test
    fun `not equal to non-KeyPair`() {
        val a = KeyPair(byteArrayOf(1), byteArrayOf(2))
        assertFalse(a.equals("not a KeyPair"))
        assertFalse(a.equals(null))
    }

    @Test
    fun `equality uses content comparison not reference`() {
        val priv = byteArrayOf(7, 8, 9)
        val pub = byteArrayOf(10, 11)
        val a = KeyPair(priv, pub)
        val b = KeyPair(priv.copyOf(), pub.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `hashCode differs for different keys`() {
        val a = KeyPair(byteArrayOf(1), byteArrayOf(2))
        val b = KeyPair(byteArrayOf(3), byteArrayOf(4))
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `empty byte arrays are supported`() {
        val a = KeyPair(ByteArray(0), ByteArray(0))
        val b = KeyPair(ByteArray(0), ByteArray(0))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `componentN destructuring yields underlying arrays`() {
        val priv = byteArrayOf(1, 2)
        val pub = byteArrayOf(3, 4)
        val (p1, p2) = KeyPair(priv, pub)
        assertSame(p1, priv)
        assertSame(p2, pub)
    }
}
