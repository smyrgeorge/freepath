package io.github.smyrgeorge.freepath.libnet.client.model

import io.github.smyrgeorge.freepath.util.serializer.InstantSerializer
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Instant

/**
 * The outer envelope transmitted over the wire.
 * [receiverIdHash] (sha256 of the receiver's raw peer ID) is exposed for routing;
 * sender identity, message type, and signature are all sealed inside [payload].
 * [relay] is null for direct peer-to-peer messages.
 */
@Serializable
data class StatelessEnvelope(
    /** Schema version. Receivers MUST reject unsupported values without parsing [payload]. */
    @ProtoNumber(1) val schema: Int,
    /**
     * sha256(receiverIdRaw) — 32 bytes.
     * Relay nodes can check if this envelope is addressed to them without learning the peerId.
     */
    @Contextual @ProtoNumber(2) val receiverIdHash: ByteArray,
    /** Unix epoch milliseconds. Used for replay protection and bound to the ciphertext via AAD. */
    @ProtoNumber(3) @Serializable(with = InstantSerializer::class) val timestamp: Instant,
    /** Random 12-byte nonce unique per envelope. */
    @Contextual @ProtoNumber(4) val nonce: ByteArray,
    /**
     * Ephemeral X25519 public key (32 bytes) generated fresh for each envelope.
     * Allows the recipient to derive the message key without knowing the sender's identity.
     */
    @Contextual @ProtoNumber(5) val ephemeralKey: ByteArray,
    /**
     * ChaCha20-Poly1305 ciphertext of a protobuf-encoded [SealedPayload].
     * Contains sender identity, message type, signature, and payload — all hidden from relays.
     */
    @Contextual @ProtoNumber(6) val payload: ByteArray,
    /** Null for direct peer-to-peer messages. Present only when relay is needed. */
    @ProtoNumber(7) val relay: RelayMetadata? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as StatelessEnvelope
        if (schema != other.schema) return false
        if (!receiverIdHash.contentEquals(other.receiverIdHash)) return false
        if (timestamp != other.timestamp) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!ephemeralKey.contentEquals(other.ephemeralKey)) return false
        if (!payload.contentEquals(other.payload)) return false
        if (relay != other.relay) return false
        return true
    }

    override fun hashCode(): Int {
        var result = schema
        result = 31 * result + receiverIdHash.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }

    override fun toString(): String =
        "StatelessEnvelope(schema=$schema, receiverIdHash=${receiverIdHash.size} bytes, timestamp=$timestamp, relay=$relay)"
}
