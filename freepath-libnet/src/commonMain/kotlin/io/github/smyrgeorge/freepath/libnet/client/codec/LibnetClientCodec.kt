package io.github.smyrgeorge.freepath.libnet.client.codec

import io.github.smyrgeorge.freepath.libnet.client.model.RelayOptions
import io.github.smyrgeorge.freepath.libnet.client.model.StatelessEnvelope
import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.model.contact.Identity
import kotlin.time.Clock

/**
 * Wire format: [VERSION (1 byte)][RESERVED (3 bytes)] | StatelessEnvelope Protobuf bytes
 *
 * The message type is now sealed inside the encrypted [io.github.smyrgeorge.freepath.libnet.client.model.SealedPayload]
 * so relay nodes cannot distinguish chat messages from content or any future message types.
 */
object LibnetClientCodec {
    const val VERSION: Byte = 1
    const val TYPE_CHAT: Byte = 1
    const val TYPE_CONTENT: Byte = 2

    /**
     * Seals [plaintext] into a [StatelessEnvelope] addressed to [receiverContact], optionally with
     * [relay] metadata for store-and-forward. Frame it for the wire with [encode], or persist the
     * envelope itself (e.g. in the relay queue) and frame it later.
     */
    fun seal(
        identity: Identity,
        receiverContact: Contact,
        type: Byte,
        plaintext: ByteArray,
        relay: RelayOptions? = null,
    ): StatelessEnvelope = StatelessEnvelopeCodec.seal(
        sender = identity,
        receiverIdRaw = receiverContact.peerIdRaw,
        receiverEncKey = receiverContact.encKeyPublic,
        type = type,
        plaintext = plaintext,
        timestamp = Clock.System.now(),
        relay = relay,
    )

    /** Frames [envelope] for the wire: 4-byte header + protobuf bytes. Inverse of [decode]. */
    fun encode(envelope: StatelessEnvelope): ByteArray =
        byteArrayOf(VERSION, 0, 0, 0) + StatelessEnvelopeCodec.encode(envelope)

    /**
     * Strips the 4-byte header and decodes the protobuf envelope without decrypting the payload.
     * Returns `null` if the bytes are malformed or the version is unsupported.
     */
    fun decode(bytes: ByteArray): StatelessEnvelope? = try {
        require(bytes.size >= 4) { "Message too short: ${bytes.size} bytes" }
        require(bytes[0] == VERSION) { "Unsupported version: ${bytes[0].toInt()}" }
        StatelessEnvelopeCodec.decode(bytes.copyOfRange(4, bytes.size))
    } catch (_: Exception) {
        null
    }

    /**
     * Decrypts and authenticates an already-decoded [envelope].
     * Returns `(type, plaintext)` on success, or `null` on any failure.
     */
    fun open(
        envelope: StatelessEnvelope,
        identity: Identity,
        contactLookup: (String) -> Contact?,
    ): Pair<Byte, ByteArray>? = try {
        StatelessEnvelopeCodec.open(envelope, identity, contactLookup)
    } catch (_: Exception) {
        null
    }
}
