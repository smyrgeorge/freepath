package io.github.smyrgeorge.freepath.contact.exchange

import io.github.smyrgeorge.freepath.contact.ContactCard

/**
 * Session abstraction for contact card exchange.
 *
 * Covers both unidirectional (QR code) and bidirectional (NFC, Bluetooth LE) flows
 * with a uniform `send` / `receive` / `close` API.
 *
 * - **Unidirectional** — see [UnidirectionalContactExchangeSession]: `send` encodes
 *   the local card; `receive` suspends until the caller delivers the peer's scanned bytes.
 * - **Bidirectional** — NFC/BLE implementations in transport modules: both `send` and
 *   `receive` operate over the underlying transport connection.
 */
interface ContactExchangeSession {

    /** The exchange method used by this session. */
    val method: ContactExchangeMethod

    /**
     * Encodes and transmits [localCard] to the peer.
     *
     * @param localCard The contact card to share.
     * @param sigKeyPrivate The Ed25519 private key used to sign [localCard].
     * @return The encoded bytes that were sent (e.g. QR payload, BLE packet).
     */
    suspend fun send(localCard: ContactCard, sigKeyPrivate: ByteArray): ByteArray

    /**
     * Receives and verifies the peer's contact card.
     *
     * Suspends until the peer's card arrives — either delivered externally
     * (unidirectional, via [UnidirectionalContactExchangeSession.deliver]) or
     * received over the transport (bidirectional).
     *
     * Performs spec-3 verification (schema, Node ID, signature) before returning.
     *
     * @return [Result.success] with the verified peer card, or [Result.failure].
     */
    suspend fun receive(): Result<ContactCard>

    /** Closes the session and releases any underlying resources. */
    suspend fun close()
}
