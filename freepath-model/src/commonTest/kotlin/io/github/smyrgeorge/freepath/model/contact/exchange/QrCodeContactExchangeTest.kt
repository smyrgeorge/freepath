package io.github.smyrgeorge.freepath.model.contact.exchange

import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.model.contact.ContactSigned
import io.github.smyrgeorge.freepath.model.contact.ContactSignedCodec
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.crypto.KeyPair
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class QrCodeContactExchangeTest {

    private fun makeContact(
        sigKp: KeyPair,
        encKp: KeyPair,
        updatedAt: Instant = Clock.System.now(),
        name: String? = null,
    ): Contact =
        Contact(
            schema = Contact.SCHEMA,
            sigKey = Base64.encode(sigKp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt.toEpochMilliseconds()),
            name = name,
        )

    private fun makeQrCode(
        contact: Contact,
        sigKeyPrivate: ByteArray,
    ): String = QrCodeContactExchange.encode(contact, sigKeyPrivate).decodeToString()

    @Test
    fun encode_producesValidPrefix() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)
        val qrCode = makeQrCode(contact, kp.privateKey)

        assertTrue(qrCode.startsWith("freepath://contact/v1/"))
    }

    @Test
    fun encode_isDeterministic() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)
        val qrCode1 = makeQrCode(contact, kp.privateKey)
        val qrCode2 = makeQrCode(contact, kp.privateKey)

        // Apple CryptoKit uses hedged (randomized) Ed25519 for security, so the raw
        // QR strings may differ across calls on iOS. Both must still decode to the
        // same verified contact — that is the meaningful determinism guarantee.
        val decoded1 = QrCodeContactExchange.decode(qrCode1).getOrThrow()
        val decoded2 = QrCodeContactExchange.decode(qrCode2).getOrThrow()
        assertEquals(contact, decoded1)
        assertEquals(contact, decoded2)
    }

    @Test
    fun encode_includesAllContactFields() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp, name = "Alice")
        val qrCode = makeQrCode(contact, kp.privateKey)

        // Decode and verify all fields are preserved
        val decodedContact = QrCodeContactExchange.decode(qrCode).getOrThrow()
        assertEquals(contact.name, decodedContact.name)
    }

    @Test
    fun encode_withOptionalFieldsOmitted() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)
        val qrCode = makeQrCode(contact, kp.privateKey)

        val decodedContact = QrCodeContactExchange.decode(qrCode).getOrThrow()
        assertEquals(contact.name, decodedContact.name)
    }

    @Test
    fun decode_roundTrip() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)
        val qrCode = makeQrCode(contact, kp.privateKey)

        val decodedContact = QrCodeContactExchange.decode(qrCode).getOrThrow()

        assertEquals(contact, decodedContact)
    }

    @Test
    fun decode_withAllOptionalFields() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp, name = "Bob")
        val qrCode = makeQrCode(contact, kp.privateKey)

        val decodedContact = QrCodeContactExchange.decode(qrCode).getOrThrow()
        assertEquals("Bob", decodedContact.name)
    }

    @Test
    fun decode_failsForWrongPrefix() {
        val qrCode = "invalid://contact/v1/abc123"

        val result = QrCodeContactExchange.decode(qrCode)

        assertTrue(result.isFailure)
        assertEquals(result.exceptionOrNull()?.message?.contains("Invalid QR code format"), true)
    }

    @Test
    fun decode_failsForEmptyPayload() {
        val qrCode = "freepath://contact/v1/"

        val result = QrCodeContactExchange.decode(qrCode)

        assertTrue(result.isFailure)
        assertEquals(result.exceptionOrNull()?.message?.contains("Empty QR code payload"), true)
    }

    @Test
    fun decode_failsForInvalidBase64() {
        val qrCode = "freepath://contact/v1/!!!invalid!!!"

        val result = QrCodeContactExchange.decode(qrCode)

        assertTrue(result.isFailure)
        assertEquals(result.exceptionOrNull()?.message?.contains("Failed to decode Base64"), true)
    }

    @Test
    fun decode_failsForInvalidJson() {
        val invalidJson = Base64.encode("{ invalid json }".encodeToByteArray())
        val qrCode = "freepath://contact/v1/$invalidJson"

        val result = QrCodeContactExchange.decode(qrCode)

        assertTrue(result.isFailure)
        assertEquals(result.exceptionOrNull()?.message?.contains("Failed to parse"), true)
    }

    @Test
    fun decode_failsForInvalidSignature() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)

        // Sign with wrong key
        val signed = ContactSignedCodec.seal(contact, kp2.privateKey)
        val base64Url = Base64.encode(ContactSignedCodec.encode(signed))
        val qrCode = "freepath://contact/v1/$base64Url"

        val result = QrCodeContactExchange.decode(qrCode)

        assertTrue(result.isFailure)
        assertEquals(result.exceptionOrNull()?.message?.contains("Invalid contact signature"), true)
    }

    @Test
    fun decode_failsForTamperedContact() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp, name = "Original")

        // Create valid QR code
        val qrCode = makeQrCode(contact, kp.privateKey)

        // Tamper by decoding, modifying, and re-encoding (signature won't match)
        val raw = QrCodeContactExchange.decodeRaw(qrCode)!!
        val tamperedContact = raw.contact.copy(name = "Tampered")
        val tamperedSigned = ContactSigned(tamperedContact, raw.signature)
        val tamperedBase64 = Base64.encode(ContactSignedCodec.encode(tamperedSigned))
        val tamperedQrCode = "freepath://contact/v1/$tamperedBase64"

        val result = QrCodeContactExchange.decode(tamperedQrCode)

        assertTrue(result.isFailure)
        assertEquals(result.exceptionOrNull()?.message?.contains("Invalid contact signature"), true)
    }

    @Test
    fun decodeRaw_returnsNullForInvalidPrefix() {
        val result = QrCodeContactExchange.decodeRaw("invalid://contact/v1/abc")
        assertEquals(null, result)
    }

    @Test
    fun decodeRaw_returnsNullForEmptyPayload() {
        val result = QrCodeContactExchange.decodeRaw("freepath://contact/v1/")
        assertEquals(null, result)
    }

    @Test
    fun decodeRaw_returnsSignedContactWithoutVerification() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)
        val qrCode = makeQrCode(contact, kp.privateKey)

        val result = QrCodeContactExchange.decodeRaw(qrCode)

        assertEquals(contact, result?.contact)
    }

    @Test
    fun estimateQrCodeLength_returnsPositiveValue() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val contact = makeContact(kp, encKp)

        val length = QrCodeContactExchange.estimateQrCodeLength(contact)

        assertTrue(length > 0)
        assertTrue(length > "freepath://contact/v1/".length)
    }

    @Test
    fun estimateQrCodeLength_increasesWithOptionalFields() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val minimalContact = makeContact(kp, encKp)
        val fullContact = makeContact(kp, encKp, name = "Alice")

        val minimalLength = QrCodeContactExchange.estimateQrCodeLength(minimalContact)
        val fullLength = QrCodeContactExchange.estimateQrCodeLength(fullContact)

        assertTrue(fullLength > minimalLength)
    }
}