package io.github.smyrgeorge.freepath.transport.model

import kotlinx.serialization.Serializable

@Serializable
data class StatelessEnvelope(
    /** Schema version. Receivers MUST reject unsupported values without parsing [payload]. */
    val schema: Int,
    /** Base58-encoded Node ID of the sender. */
    val senderId: String,
    /** Base58-encoded Node ID of the intended recipient. */
    val receiverId: String,
    /** Unix epoch milliseconds. Used for replay protection. */
    val timestamp: Long,
    /** Base64-encoded random 12-byte nonce unique per envelope. */
    val nonce: String,
    /** Zero-based index of this fragment within a multi-envelope message. 0 if not fragmented. */
    val fragmentIndex: Int,
    /** Total number of envelopes in this message. 1 means not fragmented. */
    val fragmentCount: Int,
    /** Base64-encoded ChaCha20-Poly1305 ciphertext. */
    val payload: String,
    /** Base64-encoded Ed25519 signature (64 bytes) over all other fields including [payload]. */
    val signature: String,
)
