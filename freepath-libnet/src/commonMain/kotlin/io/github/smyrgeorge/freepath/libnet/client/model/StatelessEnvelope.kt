package io.github.smyrgeorge.freepath.libnet.client.model

import io.github.smyrgeorge.freepath.util.serializer.InstantSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Instant

/**
 * The outer envelope transmitted over the wire. Only [receiverId] is exposed for routing;
 * sender identity, message type, and signature are all sealed inside [payload].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StatelessEnvelope(
    /** Schema version. Receivers MUST reject unsupported values without parsing [payload]. */
    @ProtoNumber(1) val schema: Int,
    /** Base58-encoded Node ID of the intended recipient. Visible to relays for routing. */
    @ProtoNumber(2) val receiverId: String,
    /** Unix epoch milliseconds. Used for replay protection and bound to the ciphertext via AAD. */
    @ProtoNumber(3) @Serializable(with = InstantSerializer::class) val timestamp: Instant,
    /** Random 12-byte nonce unique per envelope. */
    @ProtoNumber(4) val nonce: ByteArray,
    /**
     * Ephemeral X25519 public key (32 bytes) generated fresh for each envelope.
     * Allows the recipient to derive the message key without knowing the sender's identity.
     */
    @ProtoNumber(5) val ephemeralKey: ByteArray,
    /**
     * ChaCha20-Poly1305 ciphertext of a protobuf-encoded [SealedPayload].
     * Contains sender identity, message type, signature, and payload — all hidden from relays.
     */
    @ProtoNumber(6) val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as StatelessEnvelope

        if (schema != other.schema) return false
        if (receiverId != other.receiverId) return false
        if (timestamp != other.timestamp) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!ephemeralKey.contentEquals(other.ephemeralKey)) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = schema
        result = 31 * result + receiverId.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }

    override fun toString(): String =
        "StatelessEnvelope(schema=$schema, receiverId='$receiverId', timestamp=$timestamp)"
}
