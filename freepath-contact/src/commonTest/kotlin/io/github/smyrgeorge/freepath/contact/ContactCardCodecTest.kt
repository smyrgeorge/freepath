package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.crypto.KeyPair
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ContactCardCodecTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeCard(sigKp: KeyPair, encKp: KeyPair, updatedAt: Instant = Clock.System.now()): ContactCard =
        ContactCard(
            schema = ContactCardCodec.SCHEMA,
            sigKey = Base64.encode(sigKp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = updatedAt,
        )

    // ── deriveNodeId ──────────────────────────────────────────────────────────

    @Test
    fun deriveNodeId_produces52CharBase58String() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val nodeId = ContactCardCodec.deriveNodeId(kp.publicKey)
        assertEquals(52, nodeId.length)
        assertTrue(nodeId.all { it in "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz" })
    }

    @Test
    fun deriveNodeId_isDeterministic() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        assertEquals(
            ContactCardCodec.deriveNodeId(kp.publicKey),
            ContactCardCodec.deriveNodeId(kp.publicKey),
        )
    }

    @Test
    fun deriveNodeId_differsByKey() {
        val kp1 = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        // Two distinct keys should (with overwhelming probability) produce different Node IDs.
        val id1 = ContactCardCodec.deriveNodeId(kp1.publicKey)
        val id2 = ContactCardCodec.deriveNodeId(kp2.publicKey)
        assertTrue(id1 != id2)
    }

    // ── sign / verify ─────────────────────────────────────────────────────────

    @Test
    fun signVerify_roundTrip() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val sig = ContactCardCodec.sign(card, kp.privateKey)
        assertTrue(ContactCardCodec.verify(card, sig))
    }

    @Test
    fun verify_failsForTamperedName() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val sig = ContactCardCodec.sign(card, kp.privateKey)
        assertFalse(ContactCardCodec.verify(card.copy(name = "tampered"), sig))
    }

    @Test
    fun verify_failsForTamperedUpdatedAt() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val sig = ContactCardCodec.sign(card, kp.privateKey)
        assertFalse(ContactCardCodec.verify(card.copy(updatedAt = card.updatedAt + 1.milliseconds), sig))
    }

    @Test
    fun verify_failsForWrongSigningKey() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val sig = ContactCardCodec.sign(card, kp2.privateKey)
        assertFalse(ContactCardCodec.verify(card, sig))
    }

    // ── seal / open ───────────────────────────────────────────────────────────

    @Test
    fun sealOpen_roundTrip() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val signed = ContactCardCodec.seal(card, kp.privateKey)
        val opened = ContactCardCodec.open(signed).getOrThrow()
        assertEquals(card, opened)
    }

    @Test
    fun open_throwsForInvalidSignature() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val wrongSig = ContactCardCodec.sign(card, kp2.privateKey)
        assertFails { ContactCardCodec.open(ContactCardSigned(card, Base64.encode(wrongSig))).getOrThrow() }
    }

    // ── shouldUpdate ──────────────────────────────────────────────────────────

    @Test
    fun shouldUpdate_trueForNewerUpdatedAt() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val now = Clock.System.now()
        val stored = makeCard(kp, encKp, updatedAt = now)
        val incoming = makeCard(kp, encKp, updatedAt = now + 1000.milliseconds)
        assertTrue(ContactCardCodec.shouldUpdate(stored, incoming))
    }

    @Test
    fun shouldUpdate_falseForSameUpdatedAt() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val now = Clock.System.now()
        val stored = makeCard(kp, encKp, updatedAt = now)
        val incoming = makeCard(kp, encKp, updatedAt = now)
        assertFalse(ContactCardCodec.shouldUpdate(stored, incoming))
    }

    @Test
    fun shouldUpdate_falseForOlderUpdatedAt() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val now = Clock.System.now()
        val stored = makeCard(kp, encKp, updatedAt = now)
        val incoming = makeCard(kp, encKp, updatedAt = now - 1000.milliseconds)
        assertFalse(ContactCardCodec.shouldUpdate(stored, incoming))
    }

    @Test
    fun shouldUpdate_falseForDifferentSigKey() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val now = Clock.System.now()
        val stored = makeCard(kp, encKp, updatedAt = now)
        // incoming has a different sigKey (and therefore a different nodeId too)
        val incoming = makeCard(kp2, encKp, updatedAt = now + 1000.milliseconds)
        assertFalse(ContactCardCodec.shouldUpdate(stored, incoming))
    }

    // ── JSON encode/decode ────────────────────────────────────────────────────

    @Test
    fun encodeDecodeContactCard_roundTripRequiredFieldsOnly() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        assertEquals(card, ContactCardCodec.decode(ContactCardCodec.encode(card)))
    }

    @Test
    fun encodeDecodeContactCard_roundTripWithOptionalFields() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp).copy(name = "Alice")
        assertEquals(card, ContactCardCodec.decode(ContactCardCodec.encode(card)))
    }

    @Test
    fun encodeDecodeContactCard_nullFieldsOmittedFromJson() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val jsonStr = ContactCardCodec.encode(card).decodeToString()
        assertFalse(jsonStr.contains("name"))
    }

    @Test
    fun encodeDecodeSignedContactCard_roundTrip() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val signed = ContactCardCodec.seal(card, kp.privateKey)
        assertEquals(signed, ContactCardCodec.decodeSigned(ContactCardCodec.encode(signed)))
    }

    // ── ContactCard validation ────────────────────────────────────────────────

    @Test
    fun contactCard_validation_acceptsValidCard() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp).copy(name = "Alice")
        assertEquals("Alice", card.name)
    }

    @Test
    fun contactCard_validation_rejectsNameTooLong() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val longName = "a".repeat(ContactCard.MAX_NAME_LENGTH + 1)
        assertFails {
            makeCard(kp, encKp).copy(name = longName)
        }
    }

    @Test
    fun contactCard_validation_acceptsMaxNameLength() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val maxName = "a".repeat(ContactCard.MAX_NAME_LENGTH)
        val card = makeCard(kp, encKp).copy(name = maxName)
        assertEquals(ContactCard.MAX_NAME_LENGTH, card.name?.length)
    }

    @Test
    fun contactCard_validation_rejectsWrongSchema() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        assertFails {
            makeCard(kp, encKp).copy(schema = 99)
        }
    }
}
