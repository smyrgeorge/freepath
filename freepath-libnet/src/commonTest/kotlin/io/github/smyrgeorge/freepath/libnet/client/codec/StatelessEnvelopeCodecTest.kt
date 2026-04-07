package io.github.smyrgeorge.freepath.libnet.client.codec

import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.contact.ContactCodec
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.codec.Base58
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Instant

class StatelessEnvelopeCodecTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private data class TestPeer(val identity: Identity, val contact: Contact)

    private fun makeTestPeer(): TestPeer {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val identity = Identity(
            peerIdRaw = CryptoProvider.sha256(sigKp.publicKey),
            sigKeyPublic = sigKp.publicKey,
            sigKeyPrivate = sigKp.privateKey,
            encKeyPublic = encKp.publicKey,
            encKeyPrivate = encKp.privateKey,
        )
        val contact = Contact(
            schema = ContactCodec.SCHEMA,
            sigKey = Base64.encode(sigKp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
        )
        return TestPeer(identity, contact)
    }

    private fun lookup(vararg peers: TestPeer): (String) -> Contact? {
        val map = peers.associate { it.identity.peerId to it.contact }
        return map::get
    }

    private fun seal(
        sender: TestPeer,
        receiver: TestPeer,
        plaintext: ByteArray = "hello".encodeToByteArray(),
        type: Byte = 1,
        timestamp: Instant = Clock.System.now(),
    ) = StatelessEnvelopeCodec.seal(
        sender = sender.identity,
        receiverIdRaw = receiver.identity.peerIdRaw,
        receiverEncKey = receiver.identity.encKeyPublic,
        type = type,
        plaintext = plaintext,
        timestamp = timestamp,
    )

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test
    fun `seal and open round-trip returns original plaintext and type`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()
        val plaintext = "hello from alice".encodeToByteArray()

        val envelope = seal(alice, bob, plaintext, type = 1)
        val (type, result) = StatelessEnvelopeCodec.open(envelope, bob.identity, lookup(alice))

        assertEquals(1.toByte(), type)
        assertContentEquals(plaintext, result)
    }

    @Test
    fun `round-trip works with empty plaintext`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val envelope = seal(alice, bob, plaintext = ByteArray(0))
        val (_, result) = StatelessEnvelopeCodec.open(envelope, bob.identity, lookup(alice))

        assertContentEquals(ByteArray(0), result)
    }

    @Test
    fun `type is preserved through seal and open`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        for (t in listOf(1.toByte(), 2.toByte(), 42.toByte())) {
            val envelope = seal(alice, bob, type = t)
            val (returnedType, _) = StatelessEnvelopeCodec.open(envelope, bob.identity, lookup(alice))
            assertEquals(t, returnedType)
        }
    }

    @Test
    fun `encode and decode round-trip preserves envelope`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        // InstantSerializer stores milliseconds only, so truncate to avoid precision loss in round-trip.
        val timestamp = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
        val envelope = seal(alice, bob, timestamp = timestamp)

        val decoded = StatelessEnvelopeCodec.decode(StatelessEnvelopeCodec.encode(envelope))
        assertEquals(envelope, decoded)
    }

    // ── Sealed sender ─────────────────────────────────────────────────────────

    @Test
    fun `envelope exposes only receiver identity sender is not visible in header`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val envelope = seal(alice, bob)

        // Relay can read the receiver ID for routing — that's intentional.
        assertEquals(Base58.encode(bob.identity.peerIdRaw), envelope.receiverId)
        // StatelessEnvelope has no senderId or signature fields — both are sealed inside the ciphertext.
    }

    @Test
    fun `each seal produces a unique ephemeral key`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val e1 = seal(alice, bob, plaintext = "msg1".encodeToByteArray())
        val e2 = seal(alice, bob, plaintext = "msg2".encodeToByteArray())

        assertFalse(e1.ephemeralKey.contentEquals(e2.ephemeralKey))
    }

    // ── Failure cases ─────────────────────────────────────────────────────────

    @Test
    fun `open fails when envelope is addressed to a different receiver`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()
        val carol = makeTestPeer()

        val envelope = seal(alice, bob)

        // Carol tries to open an envelope addressed to Bob — receiverId mismatch.
        assertFails { StatelessEnvelopeCodec.open(envelope, carol.identity, lookup(alice)) }
    }

    @Test
    fun `open fails when payload is tampered`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val envelope = seal(alice, bob)
        val tampered = envelope.copy(
            payload = envelope.payload.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        )

        assertFails { StatelessEnvelopeCodec.open(tampered, bob.identity, lookup(alice)) }
    }

    @Test
    fun `open fails when sender sigKey does not match`() {
        val alice = makeTestPeer()
        val mallory = makeTestPeer()
        val bob = makeTestPeer()

        val envelope = seal(alice, bob)

        // Bob's contact list maps alice's peerId to mallory's sigKey — signature verification fails.
        val wrongContact = Contact(
            schema = ContactCodec.SCHEMA,
            sigKey = Base64.encode(mallory.identity.sigKeyPublic),
            encKey = Base64.encode(mallory.identity.encKeyPublic),
        )
        assertFails {
            StatelessEnvelopeCodec.open(envelope, bob.identity) {
                if (it == alice.identity.peerId) wrongContact else null
            }
        }
    }

    @Test
    fun `open fails when ephemeral key is replaced`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val envelope = seal(alice, bob)
        val tampered = envelope.copy(ephemeralKey = CryptoProvider.generateX25519KeyPair().publicKey)

        assertFails { StatelessEnvelopeCodec.open(tampered, bob.identity, lookup(alice)) }
    }

    @Test
    fun `open fails when sender is not in contact list`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val envelope = seal(alice, bob)

        assertFails { StatelessEnvelopeCodec.open(envelope, bob.identity) { null } }
    }

    @Test
    fun `open fails when timestamp is negative`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        assertFails {
            seal(alice, bob, timestamp = Instant.fromEpochMilliseconds(-1))
        }
    }

    @Test
    fun `multiple senders can send to the same receiver`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()
        val carol = makeTestPeer()

        val fromAlice = seal(alice, bob, "from alice".encodeToByteArray())
        val fromCarol = seal(carol, bob, "from carol".encodeToByteArray())

        val contacts = lookup(alice, carol)
        val (_, resultAlice) = StatelessEnvelopeCodec.open(fromAlice, bob.identity, contacts)
        val (_, resultCarol) = StatelessEnvelopeCodec.open(fromCarol, bob.identity, contacts)

        assertContentEquals("from alice".encodeToByteArray(), resultAlice)
        assertContentEquals("from carol".encodeToByteArray(), resultCarol)
    }
}
