package io.github.smyrgeorge.freepath.contact.exchange

import io.github.smyrgeorge.freepath.contact.ContactCard

/**
 * Sync encode/decode interface for contact card exchange methods that operate on
 * discrete byte payloads (e.g. QR code, alphanumeric string).
 *
 * Exchange modes supported: **unidirectional** (one party sends their card only).
 * For session-based bidirectional exchange (NFC, Bluetooth LE) see [ContactExchangeSession].
 */
interface ContactExchange {

    /**
     * The exchange method used by this implementation.
     */
    val method: ContactExchangeMethod

    /**
     * Encodes a [ContactCard] for transmission via this exchange method.
     *
     * @param card The contact card to share.
     * @param sigKeyPrivate The Ed25519 private key for signing the card.
     * @return Encoded data ready for transmission via this exchange method.
     */
    fun encode(card: ContactCard, sigKeyPrivate: ByteArray): ByteArray

    /**
     * Decodes and verifies a received contact card.
     *
     * Performs the following verification checks per spec 3:
     * 1. Schema check - verifies the card schema is supported.
     * 2. Node ID verification - derives Node ID from sigKey and compares.
     * 3. Signature verification - verifies the card signature.
     *
     * @param data The encoded data received from the exchange method.
     * @return [Result.success] with the verified card, or [Result.failure] on error.
     */
    fun decode(data: ByteArray): Result<ContactCard>
}
