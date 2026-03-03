package io.github.smyrgeorge.freepath.contact.exchange

import io.github.smyrgeorge.freepath.contact.ContactCard
import kotlinx.coroutines.CompletableDeferred

/**
 * [ContactExchangeSession] adapter for codecs that only support one-shot payload
 * exchange (e.g. [QrCodeContactExchange]).
 *
 * Usage:
 * ```
 * val session = UnidirectionalContactExchangeSession(QrCodeContactExchange)
 *
 * // Sender side: encode and display
 * val qrBytes = session.send(localCard, sigKeyPrivate)
 *
 * // Later, when the user scans the peer's QR:
 * session.deliver(scannedBytes)
 *
 * // Receiver side: suspends until deliver() is called
 * val peerCard = session.receive().getOrThrow()
 * ```
 *
 * [send] returns immediately (encoding is synchronous).
 * [receive] suspends until [deliver] is called with the peer's payload, then
 * decodes and verifies the card.
 * [close] cancels any pending [receive].
 */
class UnidirectionalContactExchangeSession(
    private val codec: ContactExchange,
) : ContactExchangeSession {

    override val method: ContactExchangeMethod = codec.method

    private val incoming = CompletableDeferred<ByteArray>()

    override suspend fun send(localCard: ContactCard, sigKeyPrivate: ByteArray): ByteArray =
        codec.encode(localCard, sigKeyPrivate)

    override suspend fun receive(): Result<ContactCard> = runCatching {
        codec.decode(incoming.await()).getOrThrow()
    }

    /**
     * Delivers the peer's raw payload to the suspended [receive] call.
     *
     * Call this once the user has scanned the peer's QR code (or equivalent).
     * Has no effect if [receive] has already completed or [close] was called.
     */
    fun deliver(data: ByteArray) {
        incoming.complete(data)
    }

    override suspend fun close() {
        incoming.cancel()
    }
}
