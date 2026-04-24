package io.github.smyrgeorge.freepath.libnet.client.codec

import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.model.contact.ContactCodec
import io.github.smyrgeorge.freepath.model.contact.Identity
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.libnet.client.model.RelayOptions
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        relay: RelayOptions? = null,
    ) = StatelessEnvelopeCodec.seal(
        sender = sender.identity,
        receiverIdRaw = receiver.identity.peerIdRaw,
        receiverEncKey = receiver.identity.encKeyPublic,
        type = type,
        plaintext = plaintext,
        timestamp = timestamp,
        relay = relay,
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

        val timestamp = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
        val envelope = seal(alice, bob, timestamp = timestamp)

        val decoded = StatelessEnvelopeCodec.decode(StatelessEnvelopeCodec.encode(envelope))
        assertEquals(envelope, decoded)
    }

    // ── Receiver identity hash ────────────────────────────────────────────────

    @Test
    fun `envelope exposes receiver identity hash not plain peerId`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val envelope = seal(alice, bob)

        // Relay nodes check the hash to route without learning the peerId.
        val expectedHash = CryptoProvider.sha256(bob.identity.peerIdRaw)
        assertContentEquals(expectedHash, envelope.receiverIdHash)
        // StatelessEnvelope has no senderId or signature fields — sealed inside ciphertext.
    }

    @Test
    fun `each seal produces a unique ephemeral key`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val e1 = seal(alice, bob, plaintext = "msg1".encodeToByteArray())
        val e2 = seal(alice, bob, plaintext = "msg2".encodeToByteArray())

        assertFalse(e1.ephemeralKey.contentEquals(e2.ephemeralKey))
    }

    // ── Relay ─────────────────────────────────────────────────────────────────

    @Test
    fun `direct envelope has null relay`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val envelope = seal(alice, bob)
        assertNull(envelope.relay)
    }

    @Test
    fun `relay envelope round-trip preserves metadata and plaintext`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()
        val plaintext = "relay message".encodeToByteArray()

        val envelope = seal(alice, bob, plaintext, relay = RelayOptions(ttl = 3, priority = 2))

        val relay = assertNotNull(envelope.relay)
        assertEquals(3, relay.ttl)
        assertEquals(2, relay.priority)
        assertEquals(32, relay.messageId.size)

        val (_, result) = StatelessEnvelopeCodec.open(envelope, bob.identity, lookup(alice))
        assertContentEquals(plaintext, result)
    }

    @Test
    fun `messageId equals sha256 of nonce concatenated with ephemeralKey`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val envelope = seal(alice, bob, relay = RelayOptions(ttl = 3))

        val expected = CryptoProvider.sha256(envelope.nonce + envelope.ephemeralKey)
        assertContentEquals(expected, envelope.relay!!.messageId)
    }

    @Test
    fun `relay ttl can be decremented without breaking open`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val envelope = seal(alice, bob, relay = RelayOptions(ttl = 3))
        // TTL is excluded from AAD — relay nodes may decrement it legitimately.
        val decremented = envelope.copy(relay = envelope.relay!!.copy(ttl = 2))

        val (_, result) = StatelessEnvelopeCodec.open(decremented, bob.identity, lookup(alice))
        assertContentEquals("hello".encodeToByteArray(), result)
    }

    @Test
    fun `open fails when relay messageId is tampered`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val envelope = seal(alice, bob, relay = RelayOptions(ttl = 3))
        val tampered = envelope.copy(
            relay = envelope.relay!!.copy(messageId = ByteArray(32) { 0xFF.toByte() })
        )

        assertFails { StatelessEnvelopeCodec.open(tampered, bob.identity, lookup(alice)) }
    }

    @Test
    fun `open fails when relay priority is tampered`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val envelope = seal(alice, bob, relay = RelayOptions(ttl = 3, priority = 1))
        val tampered = envelope.copy(relay = envelope.relay!!.copy(priority = 99))

        assertFails { StatelessEnvelopeCodec.open(tampered, bob.identity, lookup(alice)) }
    }

    // ── Failure cases ─────────────────────────────────────────────────────────

    @Test
    fun `open fails when envelope is addressed to a different receiver`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()
        val carol = makeTestPeer()

        val envelope = seal(alice, bob)
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
