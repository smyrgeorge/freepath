package io.github.smyrgeorge.freepath.contact.exchange

import io.github.smyrgeorge.freepath.contact.Contact

/**
 * Sync encode/decode interface for contact exchange methods that operate on
 * discrete byte payloads (e.g. QR code, alphanumeric string).
 *
 * Exchange modes supported: **unidirectional** (one party sends their contact only).
 */
interface ContactExchange {

    /**
     * The exchange method used by this implementation.
     */
    val method: ContactExchangeMethod

    /**
     * Encodes a [Contact] for transmission via this exchange method.
     *
     * @param contact The contact contact to share.
     * @param sigKeyPrivate The Ed25519 private key for signing the contact.
     * @return Encoded data ready for transmission via this exchange method.
     */
    fun encode(contact: Contact, sigKeyPrivate: ByteArray): ByteArray

    /**
     * Decodes and verifies a received contact.
     *
     * Performs the following verification checks per spec 3:
     * 1. Schema check - verifies the contact schema is supported.
     * 2. Node ID verification - derives Node ID from sigKey and compares.
     * 3. Signature verification - verifies the contact signature.
     *
     * @param data The encoded data received from the exchange method.
     * @return [Result.success] with the verified contact, or [Result.failure] on error.
     */
    fun decode(data: ByteArray): Result<Contact>
}
