package io.github.smyrgeorge.freepath.util.crypto

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

    // Note: cryptography-kotlin's JDK provider (BouncyCastle) enforces nonce
    // non-reuse per key. Same key+nonce pairs must never be encrypted twice.
    // Callers are responsible for generating unique nonces per encryption.

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

    // ---- SHA-256 -----------------------------------------------------------

    @Test
    fun `sha256 output is 32 bytes`() {
        assertEquals(32, CryptoProvider.sha256("data".encodeToByteArray()).size)
        assertEquals(32, CryptoProvider.sha256(ByteArray(0)).size)
    }

    @Test
    fun `sha256 is deterministic`() {
        val input = "freepath".encodeToByteArray()
        assertContentEquals(CryptoProvider.sha256(input), CryptoProvider.sha256(input))
    }

    @Test
    fun `sha256 different inputs produce different digests`() {
        assertFalse(
            CryptoProvider.sha256("a".encodeToByteArray())
                .contentEquals(CryptoProvider.sha256("b".encodeToByteArray()))
        )
    }

    // FIPS 180-4 known answer: SHA-256("") and SHA-256("abc").
    @Test
    fun `sha256 matches known test vectors`() {
        val emptyExpected = hex(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
        assertContentEquals(emptyExpected, CryptoProvider.sha256(ByteArray(0)))

        val abcExpected = hex(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
        assertContentEquals(abcExpected, CryptoProvider.sha256("abc".encodeToByteArray()))
    }

    // ---- RFC test vectors: cross-platform wire compatibility ---------------

    // RFC 7748 §5.2 — X25519 test vector #1.
    @Test
    fun `x25519DH matches RFC 7748 test vector`() {
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val uCoord = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        val expected = hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552")
        assertContentEquals(expected, CryptoProvider.x25519DH(scalar, uCoord))
    }

    // RFC 8032 §7.1 — Ed25519 Test 1 (empty message, all fixed key material).
    @Test
    fun `ed25519 matches RFC 8032 test vector 1`() {
        val privateKey = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val publicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val message = ByteArray(0)
        val expectedSig = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                    "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        )
        // Apple CryptoKit hedges (randomizes) Ed25519 signing — exact signature
        // bytes are not reproducible. Verify instead via the known-answer signature
        // and a fresh signature round-trip.
        assertTrue(CryptoProvider.ed25519Verify(publicKey, message, expectedSig))
        val freshSig = CryptoProvider.ed25519Sign(privateKey, message)
        assertTrue(CryptoProvider.ed25519Verify(publicKey, message, freshSig))
    }

    // RFC 8439 §2.8.2 — ChaCha20-Poly1305 known-answer test.
    @Test
    fun `chacha20Poly1305 matches RFC 8439 test vector`() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you " +
                "only one tip for the future, sunscreen would be it.").encodeToByteArray()
        val expectedCiphertext = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                    "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                    "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                    "3ff4def08e4b7a9de576d26586cec64b6116"
        )
        val expectedTag = hex("1ae10b594f09e26a7e902ecbd0600691")
        val expected = expectedCiphertext + expectedTag

        val ct = CryptoProvider.chacha20Poly1305Encrypt(key, nonce, plaintext, aad)
        assertContentEquals(expected, ct)
        assertContentEquals(plaintext, CryptoProvider.chacha20Poly1305Decrypt(key, nonce, ct, aad))
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

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0) { "hex string must have even length" }
        return ByteArray(s.length / 2) { i ->
            ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
        }
    }
}
