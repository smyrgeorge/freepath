package io.github.smyrgeorge.freepath.client.codec

import io.github.smyrgeorge.freepath.client.model.ContactInfo
import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/**
 * Wire format: [VERSION (1 byte)][TYPE (1 byte)][RESERVED (2 bytes)] | StatelessEnvelope Protobuf bytes
 */
object AppClientCodec {

    const val VERSION: Byte = 1

    fun seal(
        identity: Identity,
        receiverCard: ContactCard,
        type: Byte,
        plaintext: ByteArray,
    ): ByteArray {
        val receiverIdRaw = CryptoProvider.sha256(Base64.decode(receiverCard.sigKey))
        val receiverEncKeyPublic = Base64.decode(receiverCard.encKey)
        val envelope = StatelessEnvelopeCodec.seal(
            sender = identity,
            receiverIdRaw = receiverIdRaw,
            receiverEncKeyPublic = receiverEncKeyPublic,
            plaintext = plaintext,
            timestamp = Clock.System.now(),
        )
        val envelopeBytes = StatelessEnvelopeCodec.encode(envelope)
        return byteArrayOf(VERSION, type, 0, 0) + envelopeBytes
    }

    fun open(
        bytes: ByteArray,
        identity: Identity,
        contactLookup: (ByteArray) -> ContactInfo?,
    ): Pair<Byte, ByteArray>? = try {
        require(bytes.size >= 4) { "Message too short: ${bytes.size} bytes" }
        require(bytes[0] == VERSION) { "Unsupported version: ${bytes[0].toInt()}" }
        val type = bytes[1]
        val envelope = StatelessEnvelopeCodec.decode(bytes.drop(4).toByteArray())
        val plaintext = StatelessEnvelopeCodec.open(envelope, identity, contactLookup)
        type to plaintext
    } catch (_: Exception) {
        null
    }
}
