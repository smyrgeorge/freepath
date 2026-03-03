package io.github.smyrgeorge.freepath.contact.exchange

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.ContactCardCodec
import io.github.smyrgeorge.freepath.contact.ContactCardSigned
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.crypto.KeyPair
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class QrCodeContactExchangeTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeCard(
        sigKp: KeyPair,
        encKp: KeyPair,
        updatedAt: Instant = Clock.System.now(),
        name: String? = null,
        bio: String? = null,
        location: String? = null,
    ): ContactCard =
        ContactCard(
            schema = ContactCard.SCHEMA,
            nodeId = ContactCardCodec.deriveNodeId(sigKp.publicKey),
            sigKey = Base64.encode(sigKp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = updatedAt,
            name = name,
            bio = bio,
            location = location,
        )

    private fun makeQrCode(
        card: ContactCard,
        sigKeyPrivate: ByteArray,
    ): String = QrCodeContactExchange.encode(card, sigKeyPrivate).decodeToString()

    // ── encode ─────────────────────────────────────────────────────────────────

    @Test
    fun encode_producesValidPrefix() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val qrCode = makeQrCode(card, kp.privateKey)

        assertTrue(qrCode.startsWith("freepath://contact/v1/"))
    }

    @Test
    fun encode_isDeterministic() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val qrCode1 = makeQrCode(card, kp.privateKey)
        val qrCode2 = makeQrCode(card, kp.privateKey)

        // Apple CryptoKit uses hedged (randomized) Ed25519 for security, so the raw
        // QR strings may differ across calls on iOS. Both must still decode to the
        // same verified card — that is the meaningful determinism guarantee.
        val decoded1 = QrCodeContactExchange.decode(qrCode1).getOrThrow()
        val decoded2 = QrCodeContactExchange.decode(qrCode2).getOrThrow()
        assertEquals(card, decoded1)
        assertEquals(card, decoded2)
    }

    @Test
    fun encode_includesAllCardFields() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(
            kp,
            encKp,
            name = "Alice",
            bio = "Hello world",
            location = "NYC",
        )
        val qrCode = makeQrCode(card, kp.privateKey)

        // Decode and verify all fields are preserved
        val decodedCard = QrCodeContactExchange.decode(qrCode).getOrThrow()
        assertEquals(card.name, decodedCard.name)
        assertEquals(card.bio, decodedCard.bio)
        assertEquals(card.location, decodedCard.location)
    }

    @Test
    fun encode_withOptionalFieldsOmitted() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val qrCode = makeQrCode(card, kp.privateKey)

        val decodedCard = QrCodeContactExchange.decode(qrCode).getOrThrow()
        assertEquals(card.name, decodedCard.name)
        assertEquals(card.bio, decodedCard.bio)
        assertEquals(card.avatar, decodedCard.avatar)
        assertEquals(card.location, decodedCard.location)
    }

    // ── decode success ────────────────────────────────────────────────────────

    @Test
    fun decode_roundTrip() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val qrCode = makeQrCode(card, kp.privateKey)

        val decodedCard = QrCodeContactExchange.decode(qrCode).getOrThrow()

        assertEquals(card, decodedCard)
    }

    @Test
    fun decode_withAllOptionalFields() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(
            kp,
            encKp,
            name = "Bob",
            bio = "Developer",
            location = "San Francisco",
        )
        val qrCode = makeQrCode(card, kp.privateKey)

        val decodedCard = QrCodeContactExchange.decode(qrCode).getOrThrow()
        assertEquals("Bob", decodedCard.name)
        assertEquals("Developer", decodedCard.bio)
        assertEquals("San Francisco", decodedCard.location)
    }

    // ── decode failure: invalid format ────────────────────────────────────────

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

    // ── decode failure: signature verification ────────────────────────────────

    @Test
    fun decode_failsForInvalidSignature() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)

        // Sign with wrong key
        val signed = ContactCardCodec.seal(card, kp2.privateKey)
        val base64Url = Base64.encode(ContactCardCodec.encode(signed))
        val qrCode = "freepath://contact/v1/$base64Url"

        val result = QrCodeContactExchange.decode(qrCode)

        assertTrue(result.isFailure)
        assertEquals(result.exceptionOrNull()?.message?.contains("Invalid card signature"), true)
    }

    @Test
    fun decode_failsForTamperedCard() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp, name = "Original")

        // Create valid QR code
        val qrCode = makeQrCode(card, kp.privateKey)

        // Tamper by decoding, modifying, and re-encoding (signature won't match)
        val raw = QrCodeContactExchange.decodeRaw(qrCode)!!
        val tamperedCard = raw.card.copy(name = "Tampered")
        val tamperedSigned = ContactCardSigned(tamperedCard, raw.signature)
        val tamperedBase64 = Base64.encode(ContactCardCodec.encode(tamperedSigned))
        val tamperedQrCode = "freepath://contact/v1/$tamperedBase64"

        val result = QrCodeContactExchange.decode(tamperedQrCode)

        assertTrue(result.isFailure)
        assertEquals(result.exceptionOrNull()?.message?.contains("Invalid card signature"), true)
    }

    // ── decode failure: Node ID verification ──────────────────────────────────

    @Test
    fun decode_failsForMismatchedNodeId() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)

        // Create card with wrong nodeId but valid signature for the tampered card
        val wrongNodeIdCard = card.copy(nodeId = ContactCardCodec.deriveNodeId(kp2.publicKey))
        val signed =
            ContactCardSigned(wrongNodeIdCard, Base64.encode(ContactCardCodec.sign(wrongNodeIdCard, kp.privateKey)))
        val base64Url = Base64.encode(ContactCardCodec.encode(signed))
        val qrCode = "freepath://contact/v1/$base64Url"

        val result = QrCodeContactExchange.decode(qrCode)

        assertTrue(result.isFailure)
        assertEquals(result.exceptionOrNull()?.message?.contains("Node ID mismatch"), true)
    }

    // ── decodeRaw ─────────────────────────────────────────────────────────────

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
    fun decodeRaw_returnsSignedCardWithoutVerification() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val qrCode = makeQrCode(card, kp.privateKey)

        val result = QrCodeContactExchange.decodeRaw(qrCode)

        assertEquals(card, result?.card)
    }

    // ── estimateQrCodeLength ──────────────────────────────────────────────────

    @Test
    fun estimateQrCodeLength_returnsPositiveValue() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)

        val length = QrCodeContactExchange.estimateQrCodeLength(card)

        assertTrue(length > 0)
        assertTrue(length > "freepath://contact/v1/".length)
    }

    @Test
    fun estimateQrCodeLength_increasesWithOptionalFields() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val minimalCard = makeCard(kp, encKp)
        val fullCard = makeCard(
            kp,
            encKp,
            name = "Alice",
            bio = "Hello world",
            location = "New York",
        )

        val minimalLength = QrCodeContactExchange.estimateQrCodeLength(minimalCard)
        val fullLength = QrCodeContactExchange.estimateQrCodeLength(fullCard)

        assertTrue(fullLength > minimalLength)
    }
}