package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.freepath.contact.ContactCard.Companion.SCHEMA
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.time.Clock
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
    val updatedAt: Instant = Clock.System.now(),
    /** Human-readable display name chosen by the owner. Max 64 chars. */
    val name: String? = null,
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
    }

    override fun toString(): String {
        return "ContactCard(name=$name, updatedAt=$updatedAt, nodeId='$nodeId', schema=$schema)"
    }

    companion object {
        const val SCHEMA = 1
        const val MAX_NAME_LENGTH = 64
        private const val BASE64_PUBKEY_LENGTH = 44 // 32 bytes base64-encoded
    }
}
