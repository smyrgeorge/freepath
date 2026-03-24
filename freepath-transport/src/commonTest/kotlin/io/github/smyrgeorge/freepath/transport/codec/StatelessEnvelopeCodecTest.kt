package io.github.smyrgeorge.freepath.transport.codec

import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.transport.model.ContactInfo
import io.github.smyrgeorge.freepath.util.codec.Base58
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class StatelessEnvelopeCodecTest {

    private fun makeIdentity(): Identity {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val peerIdRaw = CryptoProvider.randomBytes(32)
        return Identity(peerIdRaw, sigKp.publicKey, sigKp.privateKey, encKp.publicKey, encKp.privateKey)
    }

    private fun contactLookupFor(vararg identities: Identity): (ByteArray) -> ContactInfo? = { nodeIdRaw ->
        identities.firstOrNull { it.nodeIdRaw.contentEquals(nodeIdRaw) }
            ?.let { ContactInfo(it.sigKeyPublic, it.encKeyPublic) }
    }

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test
    fun `seal and open round-trips plaintext`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val plaintext = "hello freepath".encodeToByteArray()

        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, plaintext, timestamp = Instant.fromEpochMilliseconds(1_000_000L))
        val recovered = StatelessEnvelopeCodec.open(envelope, bob, contactLookupFor(alice))

        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun `seal and open round-trips empty payload`() {
        val alice = makeIdentity()
        val bob = makeIdentity()

        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, ByteArray(0), timestamp = Instant.fromEpochMilliseconds(1L))
        val recovered = StatelessEnvelopeCodec.open(envelope, bob, contactLookupFor(alice))

        assertContentEquals(ByteArray(0), recovered)
    }

    @Test
    fun `envelope fields are populated correctly`() {
        val alice = makeIdentity()
        val bob = makeIdentity()

        val ts = Instant.fromEpochMilliseconds(42_000L)
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "test".encodeToByteArray(), timestamp = ts)

        assertEquals(StatelessEnvelopeCodec.SCHEMA, envelope.schema)
        assertEquals(Base58.encode(alice.nodeIdRaw), envelope.senderId)
        assertEquals(Base58.encode(bob.nodeIdRaw), envelope.receiverId)
        assertEquals(ts, envelope.timestamp)
        assertEquals(0, envelope.fragmentIndex)
        assertEquals(1, envelope.fragmentCount)
        assertEquals(12, envelope.nonce.size)
        assertEquals(64, envelope.signature.size)
    }

    @Test
    fun `fragmented envelopes seal and open correctly`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val chunks = listOf("part-0".encodeToByteArray(), "part-1".encodeToByteArray(), "part-2".encodeToByteArray())

        val envelopes = chunks.mapIndexed { idx, chunk ->
            StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, chunk, timestamp = Instant.fromEpochMilliseconds(1L), fragmentIndex = idx, fragmentCount = 3)
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
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = Instant.fromEpochMilliseconds(1L))

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(envelope, bob) { null }  // unknown sender
        }
    }

    @Test
    fun `open fails when receiverId does not match local node`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val carol = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = Instant.fromEpochMilliseconds(1L))

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(envelope, carol, contactLookupFor(alice))  // carol is not the receiver
        }
    }

    @Test
    fun `open fails when signature is tampered`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = Instant.fromEpochMilliseconds(1L))

        val tamperedSig = envelope.signature.copyOf().also { it[0] = it[0].inc() }
        val tampered = envelope.copy(signature = tamperedSig)

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(tampered, bob, contactLookupFor(alice))
        }
    }

    @Test
    fun `open fails when payload ciphertext is tampered`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = Instant.fromEpochMilliseconds(1L))

        val tamperedCt = envelope.payload.copyOf().also { it[0] = it[0].inc() }
        val tampered = envelope.copy(payload = tamperedCt)

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(tampered, bob, contactLookupFor(alice))
        }
    }

    @Test
    fun `open fails when timestamp is modified after signing`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = Instant.fromEpochMilliseconds(1_000L))

        val tampered = envelope.copy(timestamp = Instant.fromEpochMilliseconds(9_999L))

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(tampered, bob, contactLookupFor(alice))
        }
    }

    @Test
    fun `open fails with wrong receiver enc key wrong encKeyPublic`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val wrongKey = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = Instant.fromEpochMilliseconds(1L))

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
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = Instant.fromEpochMilliseconds(1L))

        val tampered = envelope.copy(schema = 99)

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(tampered, bob, contactLookupFor(alice))
        }
    }

    @Test
    fun `open fails for invalid fragmentCount`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = StatelessEnvelopeCodec.seal(alice, bob.nodeIdRaw, bob.encKeyPublic, "x".encodeToByteArray(), timestamp = Instant.fromEpochMilliseconds(1L))

        assertFailsWith<StatelessEnvelopeCodec.EnvelopeException> {
            StatelessEnvelopeCodec.open(envelope.copy(fragmentCount = 0), bob, contactLookupFor(alice))
        }
    }
}
