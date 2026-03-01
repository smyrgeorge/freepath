package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.freepath.contact.ContactCard.Companion.SCHEMA
import kotlinx.serialization.Serializable

@Serializable
data class ContactCard(
    /** Schema version. Always [SCHEMA] for cards produced by this implementation. */
    val schema: Int,
    /** Base58-encoded Node ID: Base58(SHA-256(sigKey)[0..15]). Convenience field; always verified locally. */
    val nodeId: String,
    /** Base64-encoded Ed25519 public key. Used to verify the card signature and all attributed content. */
    val sigKey: String,
    /** Base64-encoded X25519 public key. Used to derive shared secrets for end-to-end encryption. */
    val encKey: String,
    /** Unix epoch milliseconds of the last change to this card. */
    val updatedAt: Long,
    /** Human-readable display name chosen by the owner. Max 64 chars. */
    val name: String? = null,
    /** Short description the user writes about themselves. Max 256 chars. */
    val bio: String? = null,
    /** Base64-encoded WebP avatar image. Max 64 KB, square, no larger than 512×512 px. */
    val avatar: String? = null,
    /** Free-text location hint. Max 128 chars. Never verified. */
    val location: String? = null,
) {
    init {
        require(schema == SCHEMA) { "Unsupported schema version: $schema (expected $SCHEMA)" }
        require(name == null || name.length <= MAX_NAME_LENGTH) {
            "name exceeds maximum length of $MAX_NAME_LENGTH characters"
        }
        require(bio == null || bio.length <= MAX_BIO_LENGTH) {
            "bio exceeds maximum length of $MAX_BIO_LENGTH characters"
        }
        require(avatar == null || avatar.length <= MAX_AVATAR_SIZE) {
            "avatar exceeds maximum size of $MAX_AVATAR_SIZE bytes"
        }
        require(location == null || location.length <= MAX_LOCATION_LENGTH) {
            "location exceeds maximum length of $MAX_LOCATION_LENGTH characters"
        }
    }

    companion object {
        const val SCHEMA = 1
        const val MAX_NAME_LENGTH = 64
        const val MAX_BIO_LENGTH = 256
        const val MAX_AVATAR_SIZE = 65536 // 64 KB
        const val MAX_LOCATION_LENGTH = 128
    }
}
