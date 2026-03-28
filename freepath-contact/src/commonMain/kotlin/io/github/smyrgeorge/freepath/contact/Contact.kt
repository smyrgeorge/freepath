package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.freepath.contact.Contact.Companion.SCHEMA
import io.github.smyrgeorge.freepath.util.serializer.InstantSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Contact(
    /** Schema version. Always [SCHEMA] for contacts produced by this implementation. */
    @ProtoNumber(1) val schema: Int,
    /** Base64-encoded Ed25519 public key. Used to verify the contact signature and all attributed content. */
    @ProtoNumber(2) val sigKey: String,
    /** Base64-encoded X25519 public key. Used to derive shared secrets for end-to-end encryption. */
    @ProtoNumber(3) val encKey: String,
    /** Unix epoch milliseconds of the last change to this contact. */
    @ProtoNumber(4) @Serializable(with = InstantSerializer::class) val updatedAt: Instant = Clock.System.now(),
    /** Human-readable display name chosen by the owner. Max 64 chars. */
    @ProtoNumber(5) val name: String? = null,
) {
    val peerId: String by lazy { ContactCodec.derivePeerId(Base64.decode(sigKey)) }
    val sigKeyPublic: ByteArray by lazy { Base64.decode(sigKey) }
    val encKeyPublic: ByteArray by lazy { Base64.decode(encKey) }

    init {
        require(schema == SCHEMA) { "Unsupported schema version: $schema (expected $SCHEMA)" }
        require(sigKey.isNotEmpty() && sigKey.length == BASE64_PUBKEY_LENGTH) {
            "sigKey must be a $BASE64_PUBKEY_LENGTH-character Base64-encoded Ed25519 public key"
        }
        require(encKey.isNotEmpty() && encKey.length == BASE64_PUBKEY_LENGTH) {
            "encKey must be a $BASE64_PUBKEY_LENGTH-character Base64-encoded X25519 public key"
        }
        require(updatedAt >= Instant.fromEpochMilliseconds(0)) {
            "updatedAt must be a non-negative Unix epoch milliseconds"
        }
        require(name.isNullOrEmpty() || name.isNotBlank()) { "name cannot be blank" }
        require(name == null || name.length <= MAX_NAME_LENGTH) {
            "name exceeds maximum length of $MAX_NAME_LENGTH characters"
        }
    }

    override fun toString(): String {
        return "Contact(name=$name, updatedAt=$updatedAt, peerId='$peerId', schema=$schema)"
    }

    companion object {
        const val SCHEMA = 1
        const val MAX_NAME_LENGTH = 64
        private const val BASE64_PUBKEY_LENGTH = 44 // 32 bytes base64-encoded
    }
}
