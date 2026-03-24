package io.github.smyrgeorge.freepath.libble.exchange

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.exchange.ContactExchangeMethod
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class BleContactExchangeTest {

    private fun makeCard(): Pair<ContactCard, ByteArray> {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = ContactCard(
            schema = ContactCard.SCHEMA,
            sigKey = Base64.encode(kp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = Instant.fromEpochMilliseconds(1_000L),
        )
        return card to kp.privateKey
    }

    @Test
    fun encode_producesNonEmptyBytes() {
        val (card, key) = makeCard()
        assertTrue(BleContactExchange.encode(card, key).isNotEmpty())
    }

    @Test
    fun roundTrip_returnsEqualCard() {
        val (card, key) = makeCard()
        val bytes = BleContactExchange.encode(card, key)
        assertEquals(card, BleContactExchange.decode(bytes).getOrThrow())
    }

    @Test
    fun decode_failsForInvalidBytes() {
        assertTrue(BleContactExchange.decode("not a card".encodeToByteArray()).isFailure)
    }

    @Test
    fun method_isBluetooth() {
        assertEquals(ContactExchangeMethod.BLUETOOTH, BleContactExchange.method)
    }

    @Test
    fun encodeWithPin_roundTrip() {
        val (card, key) = makeCard()
        val (pin, decoded) = BleContactExchange.decodeWithPin(
            BleContactExchange.encodeWithPin(card, key, "4321")
        ).getOrThrow()
        assertEquals("4321", pin)
        assertEquals(card, decoded)
    }

    @Test
    fun decodeWithPin_failsForTooShortBytes() {
        assertTrue(BleContactExchange.decodeWithPin("123".encodeToByteArray()).isFailure)
    }
}