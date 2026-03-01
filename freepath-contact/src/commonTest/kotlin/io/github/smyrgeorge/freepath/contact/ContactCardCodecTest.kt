package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactCardCodecTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeCard(
        sigKp: io.github.smyrgeorge.freepath.crypto.KeyPair,
        encKp: io.github.smyrgeorge.freepath.crypto.KeyPair,
        updatedAt: Long = 1_000L,
    ): ContactCard = ContactCard(
        schema = ContactCardCodec.SCHEMA,
        nodeId = ContactCardCodec.deriveNodeId(sigKp.publicKey),
        sigKey = Base64.encode(sigKp.publicKey),
        encKey = Base64.encode(encKp.publicKey),
        updatedAt = updatedAt,
    )

    // ── deriveNodeId ──────────────────────────────────────────────────────────

    @Test
    fun deriveNodeId_produces22CharBase58String() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val nodeId = ContactCardCodec.deriveNodeId(kp.publicKey)
        assertEquals(22, nodeId.length)
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

    // ── validateNodeId ────────────────────────────────────────────────────────

    @Test
    fun validateNodeId_passesForCorrectNodeId() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        assertTrue(ContactCardCodec.validateNodeId(makeCard(kp, encKp)))
    }

    @Test
    fun validateNodeId_failsForMismatchedNodeId() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp).copy(nodeId = ContactCardCodec.deriveNodeId(kp2.publicKey))
        assertFalse(ContactCardCodec.validateNodeId(card))
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
        assertFalse(ContactCardCodec.verify(card.copy(updatedAt = card.updatedAt + 1), sig))
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
        val opened = ContactCardCodec.open(signed)
        assertEquals(card, opened)
    }

    @Test
    fun open_throwsForInvalidSignature() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val wrongSig = ContactCardCodec.sign(card, kp2.privateKey)
        assertFails { ContactCardCodec.open(SignedContactCard(card, Base64.encode(wrongSig))) }
    }

    // ── shouldUpdate ──────────────────────────────────────────────────────────

    @Test
    fun shouldUpdate_trueForNewerUpdatedAt() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val stored = makeCard(kp, encKp, updatedAt = 1_000L)
        val incoming = makeCard(kp, encKp, updatedAt = 2_000L)
        assertTrue(ContactCardCodec.shouldUpdate(stored, incoming))
    }

    @Test
    fun shouldUpdate_falseForSameUpdatedAt() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val stored = makeCard(kp, encKp, updatedAt = 1_000L)
        val incoming = makeCard(kp, encKp, updatedAt = 1_000L)
        assertFalse(ContactCardCodec.shouldUpdate(stored, incoming))
    }

    @Test
    fun shouldUpdate_falseForOlderUpdatedAt() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val stored = makeCard(kp, encKp, updatedAt = 2_000L)
        val incoming = makeCard(kp, encKp, updatedAt = 1_000L)
        assertFalse(ContactCardCodec.shouldUpdate(stored, incoming))
    }

    @Test
    fun shouldUpdate_falseForDifferentSigKey() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val stored = makeCard(kp, encKp, updatedAt = 1_000L)
        // incoming has a different sigKey (and therefore a different nodeId too)
        val incoming = makeCard(kp2, encKp, updatedAt = 2_000L)
        assertFalse(ContactCardCodec.shouldUpdate(stored, incoming))
    }

    @Test
    fun shouldUpdate_falseForBadNodeId() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val stored = makeCard(kp, encKp, updatedAt = 1_000L)
        // Corrupt the nodeId on an otherwise-valid incoming card
        val incoming = makeCard(kp, encKp, updatedAt = 2_000L)
            .copy(nodeId = ContactCardCodec.deriveNodeId(kp2.publicKey))
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
        val card = makeCard(kp, encKp).copy(name = "Alice", bio = "Hello world", location = "NYC")
        assertEquals(card, ContactCardCodec.decode(ContactCardCodec.encode(card)))
    }

    @Test
    fun encodeDecodeContactCard_nullFieldsOmittedFromJson() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val jsonStr = ContactCardCodec.encode(card).decodeToString()
        assertFalse(jsonStr.contains("name"))
        assertFalse(jsonStr.contains("bio"))
        assertFalse(jsonStr.contains("avatar"))
        assertFalse(jsonStr.contains("location"))
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
        val card = makeCard(kp, encKp).copy(
            name = "Alice",
            bio = "Hello world",
            location = "New York"
        )
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
    fun contactCard_validation_rejectsBioTooLong() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val longBio = "a".repeat(ContactCard.MAX_BIO_LENGTH + 1)
        assertFails {
            makeCard(kp, encKp).copy(bio = longBio)
        }
    }

    @Test
    fun contactCard_validation_acceptsMaxBioLength() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val maxBio = "a".repeat(ContactCard.MAX_BIO_LENGTH)
        val card = makeCard(kp, encKp).copy(bio = maxBio)
        assertEquals(ContactCard.MAX_BIO_LENGTH, card.bio?.length)
    }

    @Test
    fun contactCard_validation_rejectsAvatarTooLarge() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val largeAvatar = "a".repeat(ContactCard.MAX_AVATAR_SIZE + 1)
        assertFails {
            makeCard(kp, encKp).copy(avatar = largeAvatar)
        }
    }

    @Test
    fun contactCard_validation_acceptsMaxAvatarSize() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val maxAvatar = "a".repeat(ContactCard.MAX_AVATAR_SIZE)
        val card = makeCard(kp, encKp).copy(avatar = maxAvatar)
        assertEquals(ContactCard.MAX_AVATAR_SIZE, card.avatar?.length)
    }

    @Test
    fun contactCard_validation_rejectsLocationTooLong() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val longLocation = "a".repeat(ContactCard.MAX_LOCATION_LENGTH + 1)
        assertFails {
            makeCard(kp, encKp).copy(location = longLocation)
        }
    }

    @Test
    fun contactCard_validation_acceptsMaxLocationLength() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val maxLocation = "a".repeat(ContactCard.MAX_LOCATION_LENGTH)
        val card = makeCard(kp, encKp).copy(location = maxLocation)
        assertEquals(ContactCard.MAX_LOCATION_LENGTH, card.location?.length)
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
