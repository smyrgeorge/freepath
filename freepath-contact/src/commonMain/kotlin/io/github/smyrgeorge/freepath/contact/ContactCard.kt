package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.freepath.contact.ContactCard.Companion.SCHEMA
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.time.Instant

@Serializable
data class ContactCard(
    /** Schema version. Always [SCHEMA] for cards produced by this implementation. */
    val schema: Int,
    /** Base64-encoded Ed25519 public key. Used to verify the card signature and all attributed content. */
    val sigKey: String,
    /** Base64-encoded X25519 public key. Used to derive shared secrets for end-to-end encryption. */
    val encKey: String,
    /** Unix epoch milliseconds of the last change to this card. */
    val updatedAt: Instant,
    /** Human-readable display name chosen by the owner. Max 64 chars. */
    val name: String? = null,
    /** Short description the user writes about themselves. Max 256 chars. */
    val bio: String? = null,
    /** Base64-encoded WebP avatar image. Max 64 KB, square, no larger than 512×512 px. */
    val avatar: String? = null,
    /** Free-text location hint. Max 128 chars. Never verified. */
    val location: String? = null,
) {
    /** Base58-encoded Node ID derived locally from [sigKey]: Base58(SHA-256(sigKey)). Never transmitted. */
    val nodeId: String by lazy {
        ContactCardCodec.deriveNodeId(Base64.decode(sigKey))
    }

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
        require(bio.isNullOrEmpty() || bio.isNotBlank()) { "bio cannot be blank" }
        require(bio == null || bio.length <= MAX_BIO_LENGTH) {
            "bio exceeds maximum length of $MAX_BIO_LENGTH characters"
        }
        require(avatar.isNullOrEmpty() || avatar.isNotBlank()) { "avatar cannot be blank" }
        require(avatar == null || avatar.length <= MAX_AVATAR_SIZE) {
            "avatar exceeds maximum size of $MAX_AVATAR_SIZE bytes"
        }
        require(location.isNullOrEmpty() || location.isNotBlank()) { "location cannot be blank" }
        require(location == null || location.length <= MAX_LOCATION_LENGTH) {
            "location exceeds maximum length of $MAX_LOCATION_LENGTH characters"
        }
    }

    override fun toString(): String {
        return "ContactCard(location=$location, avatar=$avatar, bio=$bio, name=$name, updatedAt=$updatedAt, nodeId='$nodeId', schema=$schema)"
    }

    companion object {
        const val SCHEMA = 1
        const val MAX_NAME_LENGTH = 64
        const val MAX_BIO_LENGTH = 256
        const val MAX_AVATAR_SIZE = 65536 // 64 KB
        const val MAX_LOCATION_LENGTH = 128
        private const val BASE64_PUBKEY_LENGTH = 44 // 32 bytes base64-encoded
    }
}
