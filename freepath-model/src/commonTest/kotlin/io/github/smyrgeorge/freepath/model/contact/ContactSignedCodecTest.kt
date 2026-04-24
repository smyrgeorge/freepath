package io.github.smyrgeorge.freepath.model.contact

import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.crypto.KeyPair
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.time.Clock
import kotlin.time.Instant

class ContactSignedCodecTest {

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
    fun sealOpen_roundTrip() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)
        val signed = ContactSignedCodec.seal(contact, kp.privateKey)
        val opened = ContactSignedCodec.open(signed).getOrThrow()
        assertEquals(contact, opened)
    }

    @Test
    fun open_throwsForInvalidSignature() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)
        val wrongSig = ContactCodec.sign(contact, kp2.privateKey)
        assertFails {
            ContactSignedCodec.open(ContactSigned(contact, Base64.encode(wrongSig))).getOrThrow()
        }
    }

    @Test
    fun encodeDecodeSignedContact_roundTrip() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)
        val signed = ContactSignedCodec.seal(contact, kp.privateKey)
        assertEquals(signed, ContactSignedCodec.decode(ContactSignedCodec.encode(signed)))
    }
}
