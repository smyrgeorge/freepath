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

class LanContactExchangeTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeCard(
        sigKp: KeyPair,
        encKp: KeyPair,
        updatedAt: Instant = Clock.System.now(),
        name: String? = null,
    ): ContactCard =
        ContactCard(
            schema = ContactCard.SCHEMA,
            sigKey = Base64.encode(sigKp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = updatedAt,
            name = name,
        )

    // ── method ────────────────────────────────────────────────────────────────

    @Test
    fun method_isLan() {
        assertEquals(ContactExchangeMethod.LAN, LanContactExchange.method)
    }

    @Test
    fun method_isBidirectional() {
        assertTrue(LanContactExchange.method.isBidirectional)
    }

    // ── encode then decode round-trip ─────────────────────────────────────────

    @Test
    fun encode_then_decode_roundTripsCorrectly() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)

        val encoded = LanContactExchange.encode(card, kp.privateKey)
        val decoded = LanContactExchange.decode(encoded).getOrThrow()

        assertEquals(card, decoded)
    }

    @Test
    fun encode_then_decode_preservesOptionalFields() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp, name = "Alice")

        val encoded = LanContactExchange.encode(card, kp.privateKey)
        val decoded = LanContactExchange.decode(encoded).getOrThrow()

        assertEquals(card.name, decoded.name)
    }

    @Test
    fun encode_producesValidJson() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)

        val encoded = LanContactExchange.encode(card, kp.privateKey)

        // Should be parseable as a ContactCardSigned
        val signed = ContactCardCodec.decodeSigned(encoded)
        assertEquals(card, signed.card)
    }

    // ── decode rejects tampered card ──────────────────────────────────────────

    @Test
    fun decode_rejectsTamperedCard() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp, name = "Original")

        val encoded = LanContactExchange.encode(card, kp.privateKey)

        // Decode the signed card, tamper with the name, re-encode without re-signing
        val signed = ContactCardCodec.decodeSigned(encoded)
        val tamperedCard = signed.card.copy(name = "Tampered")
        val tamperedSigned = ContactCardSigned(tamperedCard, signed.signature)
        val tamperedEncoded = ContactCardCodec.encode(tamperedSigned)

        val result = LanContactExchange.decode(tamperedEncoded)

        assertTrue(result.isFailure)
    }

    @Test
    fun decode_rejectsWrongSigningKey() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)

        // Sign with wrong key
        val signed = ContactCardCodec.seal(card, kp2.privateKey)
        val encoded = ContactCardCodec.encode(signed)

        val result = LanContactExchange.decode(encoded)

        assertTrue(result.isFailure)
    }

    @Test
    fun decode_rejectsInvalidJson() {
        val result = LanContactExchange.decode("{ invalid json }".encodeToByteArray())

        assertTrue(result.isFailure)
    }

    @Test
    fun decode_rejectsWrongSchema() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)

        // Build a signed card with an unsupported schema value, re-signed to pass sig check
        // Note: ContactCard constructor validates schema, so we use a known-good card and
        // manipulate the JSON directly — instead, sign a card with wrong nodeId derived
        // from a second keypair to trigger the nodeId check. For schema, we encode raw JSON.
        val validEncoded = LanContactExchange.encode(card, kp.privateKey)
        val jsonStr = validEncoded.decodeToString()
        val tamperedJson = jsonStr.replace("\"schema\":1", "\"schema\":99")

        val result = LanContactExchange.decode(tamperedJson.encodeToByteArray())

        // Should fail — either schema check or signature check (the tampered JSON won't verify)
        assertTrue(result.isFailure)
    }
}
