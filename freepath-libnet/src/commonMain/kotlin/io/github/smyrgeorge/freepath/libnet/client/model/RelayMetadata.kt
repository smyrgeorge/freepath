package io.github.smyrgeorge.freepath.libnet.client.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RelayMetadata(
    /** Remaining hops. Decremented by each relay node. NOT bound to AAD — mutable by design. */
    @ProtoNumber(1) val ttl: Int,
    /**
     * sha256(nonce + ephemeralKey). Computed by [StatelessEnvelopeCodec.seal].
     * Bound to AAD — tamper-evident via AEAD authentication.
     */
    @ProtoNumber(2) val messageId: ByteArray,
    /** Priority hint. Bound to AAD — tamper-evident. */
    @ProtoNumber(3) val priority: Int = 1,
    /** Reserved for anti-spam proof-of-work. Not yet implemented. */
    @ProtoNumber(4) val pow: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as RelayMetadata
        if (ttl != other.ttl) return false
        if (!messageId.contentEquals(other.messageId)) return false
        if (priority != other.priority) return false
        if (!pow.contentEquals(other.pow)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = ttl
        result = 31 * result + messageId.contentHashCode()
        result = 31 * result + priority
        result = 31 * result + pow.contentHashCode()
        return result
    }

    override fun toString(): String =
        "RelayMetadata(ttl=$ttl, messageId=${messageId.size}B, priority=$priority, pow=${pow.size}B)"
}
