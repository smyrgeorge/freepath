package io.github.smyrgeorge.freepath.libnet.client.codec

import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.model.contact.ContactCodec
import io.github.smyrgeorge.freepath.model.contact.Identity
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LibnetClientCodecTest {

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
        type: Byte = LibnetClientCodec.TYPE_CHAT,
    ) = LibnetClientCodec.seal(sender.identity, receiver.contact, type, plaintext)

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test
    fun `seal decode open round-trip returns original plaintext and type`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()
        val plaintext = "hello from alice".encodeToByteArray()

        val bytes = seal(alice, bob, plaintext, type = LibnetClientCodec.TYPE_CHAT)
        val envelope = assertNotNull(LibnetClientCodec.decode(bytes))
        val (type, result) = assertNotNull(LibnetClientCodec.open(envelope, bob.identity, lookup(alice)))

        assertEquals(LibnetClientCodec.TYPE_CHAT, type)
        assertContentEquals(plaintext, result)
    }

    @Test
    fun `round-trip works with empty plaintext`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val bytes = seal(alice, bob, plaintext = ByteArray(0))
        val envelope = assertNotNull(LibnetClientCodec.decode(bytes))
        val (_, result) = assertNotNull(LibnetClientCodec.open(envelope, bob.identity, lookup(alice)))

        assertContentEquals(ByteArray(0), result)
    }

    @Test
    fun `type is preserved through seal and open`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        for (t in listOf(LibnetClientCodec.TYPE_CHAT, LibnetClientCodec.TYPE_CONTENT)) {
            val bytes = seal(alice, bob, type = t)
            val envelope = assertNotNull(LibnetClientCodec.decode(bytes))
            val (returnedType, _) = assertNotNull(LibnetClientCodec.open(envelope, bob.identity, lookup(alice)))
            assertEquals(t, returnedType)
        }
    }

    // ── decode ────────────────────────────────────────────────────────────────

    @Test
    fun `decode returns receiverIdHash matching sha256 of receiver peerIdRaw`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val bytes = seal(alice, bob)
        val envelope = assertNotNull(LibnetClientCodec.decode(bytes))

        assertContentEquals(CryptoProvider.sha256(bob.identity.peerIdRaw), envelope.receiverIdHash)
    }

    @Test
    fun `decode returns null for bytes that are too short`() {
        assertNull(LibnetClientCodec.decode(ByteArray(3)))
        assertNull(LibnetClientCodec.decode(ByteArray(0)))
    }

    @Test
    fun `decode returns null for wrong version byte`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val bytes = seal(alice, bob).also { it[0] = (LibnetClientCodec.VERSION + 1).toByte() }
        assertNull(LibnetClientCodec.decode(bytes))
    }

    @Test
    fun `decode returns null for garbage payload after valid header`() {
        val header = byteArrayOf(LibnetClientCodec.VERSION, 0, 0, 0)
        val garbage = ByteArray(32) { it.toByte() }
        assertNull(LibnetClientCodec.decode(header + garbage))
    }

    @Test
    fun `each seal produces different bytes`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val b1 = seal(alice, bob, "msg1".encodeToByteArray())
        val b2 = seal(alice, bob, "msg2".encodeToByteArray())

        assertFalse(b1.contentEquals(b2))
    }

    // ── open ──────────────────────────────────────────────────────────────────

    @Test
    fun `open returns null when envelope is for a different receiver`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()
        val carol = makeTestPeer()

        val bytes = seal(alice, bob)
        val envelope = assertNotNull(LibnetClientCodec.decode(bytes))

        // Carol tries to open an envelope addressed to Bob.
        assertNull(LibnetClientCodec.open(envelope, carol.identity, lookup(alice)))
    }

    @Test
    fun `open returns null when payload is tampered`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val bytes = seal(alice, bob)
        val envelope = assertNotNull(LibnetClientCodec.decode(bytes))
        val tampered = envelope.copy(
            payload = envelope.payload.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        )

        assertNull(LibnetClientCodec.open(tampered, bob.identity, lookup(alice)))
    }

    @Test
    fun `open returns null when sender is not in contact list`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val bytes = seal(alice, bob)
        val envelope = assertNotNull(LibnetClientCodec.decode(bytes))

        assertNull(LibnetClientCodec.open(envelope, bob.identity) { null })
    }

    @Test
    fun `open returns null when sender sigKey does not match`() {
        val alice = makeTestPeer()
        val mallory = makeTestPeer()
        val bob = makeTestPeer()

        val bytes = seal(alice, bob)
        val envelope = assertNotNull(LibnetClientCodec.decode(bytes))

        val wrongContact = Contact(
            schema = ContactCodec.SCHEMA,
            sigKey = Base64.encode(mallory.identity.sigKeyPublic),
            encKey = Base64.encode(mallory.identity.encKeyPublic),
        )
        assertNull(LibnetClientCodec.open(envelope, bob.identity) {
            if (it == alice.identity.peerId) wrongContact else null
        })
    }

    @Test
    fun `open returns null when ephemeral key is replaced`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()

        val bytes = seal(alice, bob)
        val envelope = assertNotNull(LibnetClientCodec.decode(bytes))
        val tampered = envelope.copy(ephemeralKey = CryptoProvider.generateX25519KeyPair().publicKey)

        assertNull(LibnetClientCodec.open(tampered, bob.identity, lookup(alice)))
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    @Test
    fun `decoded receiverIdHash identifies relay vs own packet`() {
        val alice = makeTestPeer()
        val bob = makeTestPeer()
        val carol = makeTestPeer()

        val forBob = assertNotNull(LibnetClientCodec.decode(seal(alice, bob)))
        val forCarol = assertNotNull(LibnetClientCodec.decode(seal(alice, carol)))

        // Bob can distinguish packets for him from relay candidates via receiverIdHash.
        assertContentEquals(CryptoProvider.sha256(bob.identity.peerIdRaw), forBob.receiverIdHash)
        assertContentEquals(CryptoProvider.sha256(carol.identity.peerIdRaw), forCarol.receiverIdHash)
        assertFalse(forCarol.receiverIdHash.contentEquals(CryptoProvider.sha256(bob.identity.peerIdRaw)))
    }
}
