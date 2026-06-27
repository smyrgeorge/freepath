package io.github.smyrgeorge.freepath.libnet.client.model

import io.github.smyrgeorge.freepath.util.serializer.InstantSerializer
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Instant

/**
 * Routing metadata for a store-and-forward (relay) envelope.
 *
 * The mutable counter [copies] is deliberately NOT bound to AAD — relay nodes rewrite it (and it is
 * clamped to a protocol-wide constant on receipt). The remaining fields are bound to AAD and
 * are tamper-evident. Propagation depth is bounded by the copy count itself (binary distribute-and-wait),
 * so there is no separate hop counter.
 */
@Serializable
data class RelayMetadata(
    /**
     * sha256(nonce + ephemeralKey). Computed by [io.github.smyrgeorge.freepath.libnet.client.codec.StatelessEnvelopeCodec.seal].
     * Bound to AAD — tamper-evident via AEAD authentication.
     */
    @Contextual @ProtoNumber(1) val messageId: ByteArray,
    /** Priority hint. Bound to AAD — tamper-evident. */
    @ProtoNumber(2) val priority: Int = 1,
    /** Remaining copies on THIS replica (binary distribute-and-wait). NOT bound to AAD — mutable per distribute. */
    @ProtoNumber(3) val copies: Int,
    /** Absolute expiry. Clamped to MAX_TTL_DURATION on receipt. Bound to AAD — tamper-evident. */
    @ProtoNumber(4) @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as RelayMetadata
        if (!messageId.contentEquals(other.messageId)) return false
        if (priority != other.priority) return false
        if (copies != other.copies) return false
        if (expiresAt != other.expiresAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = messageId.contentHashCode()
        result = 31 * result + priority
        result = 31 * result + copies
        result = 31 * result + expiresAt.hashCode()
        return result
    }

    override fun toString(): String =
        "RelayMetadata(messageId=${messageId.size}B, priority=$priority, copies=$copies, expiresAt=$expiresAt)"
}
