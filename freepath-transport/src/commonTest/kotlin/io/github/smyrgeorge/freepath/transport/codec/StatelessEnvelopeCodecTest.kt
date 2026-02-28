package io.github.smyrgeorge.freepath.transport.codec

import io.github.smyrgeorge.freepath.transport.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.transport.model.ContactInfo
import io.github.smyrgeorge.freepath.transport.model.LocalIdentity
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StatelessEnvelopeCodecTest {

    private fun makeIdentity(): LocalIdentity {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val nodeIdRaw = CryptoProvider.randomBytes(16)
        return LocalIdentity(nodeIdRaw, sigKp.publicKey, sigKp.privateKey, encKp.publicKey, encKp.privateKey)
    }

    private fun contactLookupFor(vararg identities: LocalIdentity): (ByteArray) -> ContactInfo? = { nodeIdRaw ->
        identities.firstOrNull { it.nodeIdRaw.contentEquals(nodeIdRaw) }
            ?.let { ContactInfo(it.sigKeyPublic, it.encKeyPublic) }
    }

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test
    fun `seal and open round-trips plaintext`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val plaintext = "hello freepath".encodeToByteArray()

        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, plaintext, timestamp = 1_000_000L)
        val recovered = StatelessEnvelopeCodec.open(envelope, bob, contactLookupFor(alice))

        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun `seal and open round-trips empty payload`() {
        val alice = makeIdentity()
        val bob = makeIdentity()

        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, ByteArray(0), timestamp = 1L)
        val recovered = StatelessEnvelopeCodec.open(envelope, bob, contactLookupFor(alice))

        assertContentEquals(ByteArray(0), recovered)
    }

    @Test
    fun `envelope fields are populated correctly`() {
        val alice = makeIdentity()
        val bob = makeIdentity()

        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "test".encodeToByteArray(), timestamp = 42_000L)

        assertEquals(StatelessEnvelopeCodec.SCHEMA, envelope.schema)
        assertEquals(Base58.encode(alice.nodeIdRaw), envelope.senderId)
        assertEquals(Base58.encode(bob.nodeIdRaw), envelope.receiverId)
        assertEquals(42_000L, envelope.timestamp)
        assertEquals(0, envelope.fragmentIndex)
        assertEquals(1, envelope.fragmentCount)
        assertEquals(12, Base64.decode(envelope.nonce).size)
        assertEquals(64, Base64.decode(envelope.signature).size)
    }

    @Test
    fun `fragmented envelopes seal and open correctly`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val chunks = listOf("part-0".encodeToByteArray(), "part-1".encodeToByteArray(), "part-2".encodeToByteArray())

        val envelopes = chunks.mapIndexed { idx, chunk ->
            StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, chunk, timestamp = 1L, fragmentIndex = idx, fragmentCount = 3)
        }

        val recovered = envelopes.map { StatelessEnvelopeCodec.open(it, bob, contactLookupFor(alice)) }
        chunks.zip(recovered).forEachIndexed { idx, (expected, actual) ->
            assertContentEquals(expected, actual, "Fragment $idx mismatch")
        }
    }

    // ── Verification failures ─────────────────────────────────────────────────

    @Test
    fun `open fails for unknown sender`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = 1L)

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(envelope, bob) { null }  // unknown sender
        }
    }

    @Test
    fun `open fails when receiverId does not match local node`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val carol = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = 1L)

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(envelope, carol, contactLookupFor(alice))  // carol is not the receiver
        }
    }

    @Test
    fun `open fails when signature is tampered`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = 1L)

        val tamperedSig = Base64.decode(envelope.signature).also { it[0] = it[0].inc() }
        val tampered = envelope.copy(signature = Base64.encode(tamperedSig))

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(tampered, bob, contactLookupFor(alice))
        }
    }

    @Test
    fun `open fails when payload ciphertext is tampered`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = 1L)

        val tamperedCt = Base64.decode(envelope.payload).also { it[0] = it[0].inc() }
        val tampered = envelope.copy(payload = Base64.encode(tamperedCt))

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(tampered, bob, contactLookupFor(alice))
        }
    }

    @Test
    fun `open fails when timestamp is modified after signing`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = 1_000L)

        val tampered = envelope.copy(timestamp = 9_999L)

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(tampered, bob, contactLookupFor(alice))
        }
    }

    @Test
    fun `open fails with wrong receiver enc key wrong encKeyPublic`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val wrongKey = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = 1L)

        // Signature verifies (sigKey is correct), but AEAD fails because encKey is wrong.
        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(envelope, bob) { nodeIdRaw ->
                if (alice.nodeIdRaw.contentEquals(nodeIdRaw))
                    ContactInfo(alice.sigKeyPublic, wrongKey.encKeyPublic)  // wrong encKey
                else null
            }
        }
    }

    @Test
    fun `open fails for unsupported schema`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = 1L)

        val tampered = envelope.copy(schema = 99)

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(tampered, bob, contactLookupFor(alice))
        }
    }

    @Test
    fun `open fails for invalid fragmentCount`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = 1L)

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(envelope.copy(fragmentCount = 0), bob, contactLookupFor(alice))
        }
    }
}
