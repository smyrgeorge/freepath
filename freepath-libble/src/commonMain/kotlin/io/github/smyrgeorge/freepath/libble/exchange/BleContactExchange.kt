package io.github.smyrgeorge.freepath.libble.exchange

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.ContactCardSignedCodec
import io.github.smyrgeorge.freepath.contact.exchange.ContactExchange
import io.github.smyrgeorge.freepath.contact.exchange.ContactExchangeMethod

/**
 * BLE contact card exchange codec.
 */
object BleContactExchange : ContactExchange {

    override val method: ContactExchangeMethod = ContactExchangeMethod.BLE

    /**
     * Encodes a [card] by signing it with [sigKeyPrivate] and returns the raw JSON bytes
     * of the [io.github.smyrgeorge.freepath.contact.ContactCardSigned].
     *
     * @param card The contact card to share.
     * @param sigKeyPrivate The Ed25519 private key for signing.
     * @return Raw UTF-8 JSON bytes of the signed card, ready for transmission over BLE.
     */
    override fun encode(card: ContactCard, sigKeyPrivate: ByteArray): ByteArray {
        val signed = ContactCardSignedCodec.seal(card, sigKeyPrivate)
        return ContactCardSignedCodec.encode(signed)
    }

    /**
     * Decodes and verifies a received signed contact card from raw JSON bytes.
     *
     * Performs the following verification checks per spec 3:
     * 1. Schema check - verifies the card schema is supported.
     * 2. Node ID verification - derives Node ID from sigKey and compares.
     * 3. Signature verification - verifies the card signature.
     *
     * @param data The raw JSON bytes of the signed card received over BLE.
     * @return [Result.success] with the verified card, or [Result.failure] on error.
     */
    override fun decode(data: ByteArray): Result<ContactCard> = runCatching {
        val signed = ContactCardSignedCodec.decode(data)
        ContactCardSignedCodec.open(signed).getOrThrow()
    }

    /**
     * Encodes [card] with [pin] prepended as 4 ASCII bytes.
     * Used by the initiator to include the PIN in the GATT write payload.
     */
    fun encodeWithPin(card: ContactCard, sigKeyPrivate: ByteArray, pin: String): ByteArray {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be exactly 4 digits" }
        return pin.encodeToByteArray() + encode(card, sigKeyPrivate)
    }

    /**
     * Decodes bytes produced by [encodeWithPin].
     * Returns a [Pair] of the 4-digit PIN and the decoded [ContactCard].
     */
    fun decodeWithPin(data: ByteArray): Result<Pair<String, ContactCard>> = runCatching {
        require(data.size > 4) { "payload too short for PIN-prefixed card" }
        val pin = data.copyOfRange(0, 4).decodeToString()
        val card = decode(data.copyOfRange(4, data.size)).getOrThrow()
        pin to card
    }
}
