package io.github.smyrgeorge.freepath.model.contact

import io.github.smyrgeorge.freepath.util.codec.ProtobufCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.io.encoding.Base64

@OptIn(ExperimentalSerializationApi::class)
object ContactSignedCodec {
    /** Creates a [ContactSigned] by signing [contact] with [sigKeyPrivate]. */
    fun seal(contact: Contact, sigKeyPrivate: ByteArray): ContactSigned =
        ContactSigned(contact, Base64.encode(ContactCodec.sign(contact, sigKeyPrivate)))

    /**
     * Fully verifies a [ContactSigned] per spec-3:
     * (1) schema check, (2) signature verification.
     * Node ID is always correct by construction (derived lazily from sigKey).
     */
    fun open(signed: ContactSigned): Result<Contact> = runCatching {
        val contact = signed.contact
        require(contact.schema == Contact.SCHEMA) { "Unsupported card schema: ${contact.schema}" }
        val signatureBytes = Base64.decode(signed.signature)
        require(ContactCodec.verify(contact, signatureBytes)) { "Invalid card signature" }
        contact
    }

    fun encode(signed: ContactSigned): ByteArray = ProtobufCodec.protobuf.encodeToByteArray(signed)
    fun decode(bytes: ByteArray): ContactSigned = ProtobufCodec.protobuf.decodeFromByteArray(bytes)
}
