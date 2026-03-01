package io.github.smyrgeorge.freepath.transport.crypto

import kotlin.experimental.xor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoProviderTest {

    // ---- randomBytes -------------------------------------------------------

    @Test
    fun `randomBytes returns correct size`() {
        assertEquals(1, CryptoProvider.randomBytes(1).size)
        assertEquals(16, CryptoProvider.randomBytes(16).size)
        assertEquals(32, CryptoProvider.randomBytes(32).size)
    }

    @Test
    fun `randomBytes size zero returns empty`() {
        assertEquals(0, CryptoProvider.randomBytes(0).size)
    }

    @Test
    fun `randomBytes produces different values on successive calls`() {
        val a = CryptoProvider.randomBytes(32)
        val b = CryptoProvider.randomBytes(32)
        assertFalse(a.contentEquals(b))
    }

    // ---- X25519 ------------------------------------------------------------

    @Test
    fun `generateX25519KeyPair returns 32-byte keys`() {
        val kp = CryptoProvider.generateX25519KeyPair()
        assertEquals(32, kp.privateKey.size)
        assertEquals(32, kp.publicKey.size)
    }

    @Test
    fun `generateX25519KeyPair returns unique keys on each call`() {
        val kp1 = CryptoProvider.generateX25519KeyPair()
        val kp2 = CryptoProvider.generateX25519KeyPair()
        assertFalse(kp1.publicKey.contentEquals(kp2.publicKey))
    }

    @Test
    fun `x25519DH shared secret is 32 bytes`() {
        val alice = CryptoProvider.generateX25519KeyPair()
        val bob = CryptoProvider.generateX25519KeyPair()
        assertEquals(32, CryptoProvider.x25519DH(alice.privateKey, bob.publicKey).size)
    }

    @Test
    fun `x25519DH is symmetric`() {
        val alice = CryptoProvider.generateX25519KeyPair()
        val bob = CryptoProvider.generateX25519KeyPair()
        val ab = CryptoProvider.x25519DH(alice.privateKey, bob.publicKey)
        val ba = CryptoProvider.x25519DH(bob.privateKey, alice.publicKey)
        assertContentEquals(ab, ba)
    }

    @Test
    fun `x25519DH different peers produce different secrets`() {
        val alice = CryptoProvider.generateX25519KeyPair()
        val bob = CryptoProvider.generateX25519KeyPair()
        val carol = CryptoProvider.generateX25519KeyPair()
        val ab = CryptoProvider.x25519DH(alice.privateKey, bob.publicKey)
        val ac = CryptoProvider.x25519DH(alice.privateKey, carol.publicKey)
        assertFalse(ab.contentEquals(ac))
    }

    // ---- HKDF-SHA256 -------------------------------------------------------

    @Test
    fun `hkdfSha256 output length matches requested`() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16)
        val info = ByteArray(0)
        assertEquals(16, CryptoProvider.hkdfSha256(ikm, salt, info, 16).size)
        assertEquals(32, CryptoProvider.hkdfSha256(ikm, salt, info, 32).size)
        assertEquals(64, CryptoProvider.hkdfSha256(ikm, salt, info, 64).size)
    }

    @Test
    fun `hkdfSha256 is deterministic`() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16) { it.toByte() }
        val info = "freepath-test".encodeToByteArray()
        assertContentEquals(
            CryptoProvider.hkdfSha256(ikm, salt, info, 32),
            CryptoProvider.hkdfSha256(ikm, salt, info, 32),
        )
    }

    @Test
    fun `hkdfSha256 different info produces different output`() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16)
        val out1 = CryptoProvider.hkdfSha256(ikm, salt, "info-a".encodeToByteArray(), 32)
        val out2 = CryptoProvider.hkdfSha256(ikm, salt, "info-b".encodeToByteArray(), 32)
        assertFalse(out1.contentEquals(out2))
    }

    @Test
    fun `hkdfSha256 different salt produces different output`() {
        val ikm = ByteArray(32) { it.toByte() }
        val info = "test".encodeToByteArray()
        val out1 = CryptoProvider.hkdfSha256(ikm, ByteArray(16) { 0 }, info, 32)
        val out2 = CryptoProvider.hkdfSha256(ikm, ByteArray(16) { 1 }, info, 32)
        assertFalse(out1.contentEquals(out2))
    }

    // RFC 5869 Test Case 1 — HMAC-SHA-256
    @Test
    fun `hkdfSha256 matches RFC 5869 test vector`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(
            0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(),
            0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte(),
        )
        val expected = byteArrayOf(
            0x3c, 0xb2.toByte(), 0x5f, 0x25, 0xfa.toByte(), 0xac.toByte(), 0xd5.toByte(), 0x7a,
            0x90.toByte(), 0x43, 0x4f, 0x64, 0xd0.toByte(), 0x36, 0x2f, 0x2a,
            0x2d, 0x2d, 0x0a, 0x90.toByte(), 0xcf.toByte(), 0x1a, 0x5a, 0x4c,
            0x5d, 0xb0.toByte(), 0x2d, 0x56, 0xec.toByte(), 0xc4.toByte(), 0xc5.toByte(), 0xbf.toByte(),
            0x34, 0x00, 0x72, 0x08, 0xd5.toByte(), 0xb8.toByte(), 0x87.toByte(), 0x18,
            0x58, 0x65,
        )
        assertContentEquals(expected, CryptoProvider.hkdfSha256(ikm, salt, info, 42))
    }

    // ---- ChaCha20-Poly1305 -------------------------------------------------

    @Test
    fun `chacha20Poly1305 round-trip`() {
        val key = CryptoProvider.randomBytes(32)
        val nonce = CryptoProvider.randomBytes(12)
        val plaintext = "Hello, Freepath!".encodeToByteArray()
        val aad = "freepath-aad".encodeToByteArray()
        val ct = CryptoProvider.chacha20Poly1305Encrypt(key, nonce, plaintext, aad)
        assertContentEquals(plaintext, CryptoProvider.chacha20Poly1305Decrypt(key, nonce, ct, aad))
    }

    @Test
    fun `chacha20Poly1305 round-trip with empty plaintext`() {
        val key = CryptoProvider.randomBytes(32)
        val nonce = CryptoProvider.randomBytes(12)
        val ct = CryptoProvider.chacha20Poly1305Encrypt(key, nonce, ByteArray(0), ByteArray(0))
        assertContentEquals(ByteArray(0), CryptoProvider.chacha20Poly1305Decrypt(key, nonce, ct, ByteArray(0)))
    }

    @Test
    fun `chacha20Poly1305 ciphertext length is plaintext plus 16 tag bytes`() {
        val key = CryptoProvider.randomBytes(32)
        val nonce = CryptoProvider.randomBytes(12)
        val plaintext = ByteArray(64)
        val ct = CryptoProvider.chacha20Poly1305Encrypt(key, nonce, plaintext, ByteArray(0))
        assertEquals(plaintext.size + 16, ct.size)
    }

    @Test
    fun `chacha20Poly1305 encrypt is deterministic`() {
        val key = CryptoProvider.randomBytes(32)
        val nonce = CryptoProvider.randomBytes(12)
        val plaintext = "deterministic".encodeToByteArray()
        val ct1 = CryptoProvider.chacha20Poly1305Encrypt(key, nonce, plaintext, ByteArray(0))
        val ct2 = CryptoProvider.chacha20Poly1305Encrypt(key, nonce, plaintext, ByteArray(0))
        assertContentEquals(ct1, ct2)
    }

    @Test
    fun `chacha20Poly1305 decrypt fails with wrong key`() {
        val key = CryptoProvider.randomBytes(32)
        val nonce = CryptoProvider.randomBytes(12)
        val ct = CryptoProvider.chacha20Poly1305Encrypt(key, nonce, "secret".encodeToByteArray(), ByteArray(0))
        assertFails { CryptoProvider.chacha20Poly1305Decrypt(CryptoProvider.randomBytes(32), nonce, ct, ByteArray(0)) }
    }

    @Test
    fun `chacha20Poly1305 decrypt fails with wrong nonce`() {
        val key = CryptoProvider.randomBytes(32)
        val nonce = CryptoProvider.randomBytes(12)
        val ct = CryptoProvider.chacha20Poly1305Encrypt(key, nonce, "secret".encodeToByteArray(), ByteArray(0))
        assertFails { CryptoProvider.chacha20Poly1305Decrypt(key, CryptoProvider.randomBytes(12), ct, ByteArray(0)) }
    }

    @Test
    fun `chacha20Poly1305 decrypt fails with tampered ciphertext`() {
        val key = CryptoProvider.randomBytes(32)
        val nonce = CryptoProvider.randomBytes(12)
        val ct =
            CryptoProvider.chacha20Poly1305Encrypt(key, nonce, "secret data".encodeToByteArray(), ByteArray(0)).copyOf()
        ct[0] = ct[0].xor(0xFF.toByte())
        assertFails { CryptoProvider.chacha20Poly1305Decrypt(key, nonce, ct, ByteArray(0)) }
    }

    @Test
    fun `chacha20Poly1305 decrypt fails with wrong aad`() {
        val key = CryptoProvider.randomBytes(32)
        val nonce = CryptoProvider.randomBytes(12)
        val ct =
            CryptoProvider.chacha20Poly1305Encrypt(key, nonce, "secret".encodeToByteArray(), "aad".encodeToByteArray())
        assertFails { CryptoProvider.chacha20Poly1305Decrypt(key, nonce, ct, "wrong".encodeToByteArray()) }
    }

    // ---- Ed25519 -----------------------------------------------------------

    @Test
    fun `generateEd25519KeyPair returns 32-byte keys`() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        assertEquals(32, kp.privateKey.size)
        assertEquals(32, kp.publicKey.size)
    }

    @Test
    fun `generateEd25519KeyPair returns unique keys on each call`() {
        val kp1 = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        assertFalse(kp1.publicKey.contentEquals(kp2.publicKey))
    }

    @Test
    fun `ed25519 sign and verify`() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val message = "Hello, Freepath!".encodeToByteArray()
        val sig = CryptoProvider.ed25519Sign(kp.privateKey, message)
        assertTrue(CryptoProvider.ed25519Verify(kp.publicKey, message, sig))
    }

    @Test
    fun `ed25519 signature is 64 bytes`() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        assertEquals(64, CryptoProvider.ed25519Sign(kp.privateKey, "test".encodeToByteArray()).size)
    }

    // Note: Apple CryptoKit uses hedged (randomized) Ed25519 signing, so two calls
    // with the same key and message produce different but equally valid signatures.
    // Determinism is not guaranteed on iOS — we verify via round-trip instead.

    @Test
    fun `ed25519 verify fails with wrong public key`() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val other = CryptoProvider.generateEd25519KeyPair()
        val sig = CryptoProvider.ed25519Sign(kp.privateKey, "Hello".encodeToByteArray())
        assertFalse(CryptoProvider.ed25519Verify(other.publicKey, "Hello".encodeToByteArray(), sig))
    }

    @Test
    fun `ed25519 verify fails with tampered message`() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val sig = CryptoProvider.ed25519Sign(kp.privateKey, "Hello".encodeToByteArray())
        assertFalse(CryptoProvider.ed25519Verify(kp.publicKey, "World".encodeToByteArray(), sig))
    }

    @Test
    fun `ed25519 verify fails with tampered signature`() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val message = "Hello".encodeToByteArray()
        val sig = CryptoProvider.ed25519Sign(kp.privateKey, message).copyOf()
        sig[0] = sig[0].xor(0xFF.toByte())
        assertFalse(CryptoProvider.ed25519Verify(kp.publicKey, message, sig))
    }

    // ---- Cross-primitive: X25519 + HKDF → ChaCha20 -------------------------

    @Test
    fun `full handshake key derivation and encryption round-trip`() {
        val alice = CryptoProvider.generateX25519KeyPair()
        val bob = CryptoProvider.generateX25519KeyPair()

        val sessionKey = CryptoProvider.hkdfSha256(
            ikm = CryptoProvider.x25519DH(alice.privateKey, bob.publicKey),
            salt = ByteArray(32),
            info = "freepath-session-v1".encodeToByteArray(),
            outputLen = 32,
        )

        val nonce = CryptoProvider.randomBytes(12)
        val plaintext = "session message".encodeToByteArray()
        val aad = "header".encodeToByteArray()
        val ct = CryptoProvider.chacha20Poly1305Encrypt(sessionKey, nonce, plaintext, aad)
        assertContentEquals(plaintext, CryptoProvider.chacha20Poly1305Decrypt(sessionKey, nonce, ct, aad))
    }
}
