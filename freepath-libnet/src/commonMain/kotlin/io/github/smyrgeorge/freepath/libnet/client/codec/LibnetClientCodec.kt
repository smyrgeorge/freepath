package io.github.smyrgeorge.freepath.libnet.client.codec

import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import kotlin.time.Clock

/**
 * Wire format: [VERSION (1 byte)][RESERVED (3 bytes)] | StatelessEnvelope Protobuf bytes
 *
 * The message type is now sealed inside the encrypted [io.github.smyrgeorge.freepath.libnet.client.model.SealedPayload]
 * so relay nodes cannot distinguish chat messages from content or any future message types.
 */
object LibnetClientCodec {
    const val VERSION: Byte = 1

    fun seal(
        identity: Identity,
        receiverContact: Contact,
        type: Byte,
        plaintext: ByteArray,
    ): ByteArray {
        val receiverIdRaw = CryptoProvider.sha256(receiverContact.sigKeyPublic)
        val envelope = StatelessEnvelopeCodec.seal(
            sender = identity,
            receiverIdRaw = receiverIdRaw,
            receiverEncKey = receiverContact.encKeyPublic,
            type = type,
            plaintext = plaintext,
            timestamp = Clock.System.now(),
        )
        return byteArrayOf(VERSION, 0, 0, 0) + StatelessEnvelopeCodec.encode(envelope)
    }

    fun open(
        bytes: ByteArray,
        identity: Identity,
        contactLookup: (String) -> Contact?,
    ): Pair<Byte, ByteArray>? = try {
        require(bytes.size >= 4) { "Message too short: ${bytes.size} bytes" }
        require(bytes[0] == VERSION) { "Unsupported version: ${bytes[0].toInt()}" }
        val envelope = StatelessEnvelopeCodec.decode(bytes.drop(4).toByteArray())
        StatelessEnvelopeCodec.open(envelope, identity, contactLookup)
    } catch (_: Exception) {
        null
    }
}
