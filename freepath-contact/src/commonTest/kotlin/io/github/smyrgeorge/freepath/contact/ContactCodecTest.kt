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

class ContactCodecTest {

    private fun makeContact(
        sigKp: KeyPair,
        encKp: KeyPair,
        updatedAt: Instant = Clock.System.now()
    ): Contact =
        Contact(
            schema = ContactCodec.SCHEMA,
            sigKey = Base64.encode(sigKp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt.toEpochMilliseconds()),
        )

    @Test
    fun derivePeerId_produces52CharBase58String() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val peerId = ContactCodec.derivePeerId(kp.publicKey)
        assertEquals(52, peerId.length)
        assertTrue(peerId.all { it in "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz" })
    }

    @Test
    fun derivePeerId_isDeterministic() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        assertEquals(
            ContactCodec.derivePeerId(kp.publicKey),
            ContactCodec.derivePeerId(kp.publicKey),
        )
    }

    @Test
    fun derivePeerId_differsByKey() {
        val kp1 = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val id1 = ContactCodec.derivePeerId(kp1.publicKey)
        val id2 = ContactCodec.derivePeerId(kp2.publicKey)
        assertTrue(id1 != id2)
    }

    @Test
    fun signVerify_roundTrip() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)
        val sig = ContactCodec.sign(contact, kp.privateKey)
        assertTrue(ContactCodec.verify(contact, sig))
    }

    @Test
    fun verify_failsForTamperedName() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)
        val sig = ContactCodec.sign(contact, kp.privateKey)
        assertFalse(ContactCodec.verify(contact.copy(name = "tampered"), sig))
    }

    @Test
    fun verify_failsForTamperedUpdatedAt() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)
        val sig = ContactCodec.sign(contact, kp.privateKey)
        assertFalse(ContactCodec.verify(contact.copy(updatedAt = contact.updatedAt + 1.milliseconds), sig))
    }

    @Test
    fun verify_failsForWrongSigningKey() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)
        val sig = ContactCodec.sign(contact, kp2.privateKey)
        assertFalse(ContactCodec.verify(contact, sig))
    }

    @Test
    fun shouldUpdate_trueForNewerUpdatedAt() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val now = Clock.System.now()
        val stored = makeContact(kp, encKp, updatedAt = now)
        val incoming = makeContact(kp, encKp, updatedAt = now + 1000.milliseconds)
        assertTrue(ContactCodec.shouldUpdate(stored, incoming))
    }

    @Test
    fun shouldUpdate_falseForSameUpdatedAt() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val now = Clock.System.now()
        val stored = makeContact(kp, encKp, updatedAt = now)
        val incoming = makeContact(kp, encKp, updatedAt = now)
        assertFalse(ContactCodec.shouldUpdate(stored, incoming))
    }

    @Test
    fun shouldUpdate_falseForOlderUpdatedAt() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val now = Clock.System.now()
        val stored = makeContact(kp, encKp, updatedAt = now)
        val incoming = makeContact(kp, encKp, updatedAt = now - 1000.milliseconds)
        assertFalse(ContactCodec.shouldUpdate(stored, incoming))
    }

    @Test
    fun shouldUpdate_falseForDifferentSigKey() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val now = Clock.System.now()
        val stored = makeContact(kp, encKp, updatedAt = now)
        val incoming = makeContact(kp2, encKp, updatedAt = now + 1000.milliseconds)
        assertFalse(ContactCodec.shouldUpdate(stored, incoming))
    }

    @Test
    fun encodeDecodeContact_roundTripRequiredFieldsOnly() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)
        assertEquals(contact, ContactCodec.decode(ContactCodec.encode(contact)))
    }

    @Test
    fun encodeDecodeContact_roundTripWithOptionalFields() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp).copy(name = "Alice")
        assertEquals(contact, ContactCodec.decode(ContactCodec.encode(contact)))
    }

    @Test
    fun encodeContact_nullOptionalFieldsNotEncoded() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contactWithoutName = makeContact(kp, encKp)
        val contactWithName = contactWithoutName.copy(name = "Alice")
        assertTrue(ContactCodec.encode(contactWithoutName).size < ContactCodec.encode(contactWithName).size)
    }

    @Test
    fun contact_validation_acceptsValidContact() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp).copy(name = "Alice")
        assertEquals("Alice", contact.name)
    }

    @Test
    fun contact_validation_rejectsNameTooLong() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val longName = "a".repeat(Contact.MAX_NAME_LENGTH + 1)
        assertFails {
            makeContact(kp, encKp).copy(name = longName)
        }
    }

    @Test
    fun contact_validation_acceptsMaxNameLength() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val maxName = "a".repeat(Contact.MAX_NAME_LENGTH)
        val contact = makeContact(kp, encKp).copy(name = maxName)
        assertEquals(Contact.MAX_NAME_LENGTH, contact.name?.length)
    }

    @Test
    fun contact_validation_rejectsWrongSchema() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        assertFails {
            makeContact(kp, encKp).copy(schema = 99)
        }
    }
}
