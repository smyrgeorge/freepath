package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.freepath.util.codec.ProtobufCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.io.encoding.Base64

@OptIn(ExperimentalSerializationApi::class)
object ContactCardSignedCodec {
    /** Creates a [ContactCardSigned] by signing [card] with [sigKeyPrivate]. */
    fun seal(card: ContactCard, sigKeyPrivate: ByteArray): ContactCardSigned =
        ContactCardSigned(card, Base64.encode(ContactCardCodec.sign(card, sigKeyPrivate)))

    /**
     * Fully verifies a [ContactCardSigned] per spec-3:
     * (1) schema check, (2) signature verification.
     * Node ID is always correct by construction (derived lazily from sigKey).
     */
    fun open(signed: ContactCardSigned): Result<ContactCard> = runCatching {
        val card = signed.card
        require(card.schema == ContactCard.SCHEMA) { "Unsupported card schema: ${card.schema}" }
        val signatureBytes = Base64.decode(signed.signature)
        require(ContactCardCodec.verify(card, signatureBytes)) { "Invalid card signature" }
        card
    }

    fun encode(signed: ContactCardSigned): ByteArray = ProtobufCodec.protobuf.encodeToByteArray(signed)
    fun decode(bytes: ByteArray): ContactCardSigned = ProtobufCodec.protobuf.decodeFromByteArray(bytes)
}
