package io.github.smyrgeorge.freepath.libnet.client.model

import io.github.smyrgeorge.freepath.util.serializer.InstantSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.time.Instant

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StatelessEnvelope(
    /** Schema version. Receivers MUST reject unsupported values without parsing [payload]. */
    @ProtoNumber(1) val schema: Int,
    /** Base58-encoded Node ID of the sender. */
    @ProtoNumber(2) val senderId: String,
    /** Base58-encoded Node ID of the intended recipient. */
    @ProtoNumber(3) val receiverId: String,
    /** Unix epoch milliseconds. Used for replay protection. */
    @ProtoNumber(4) @Serializable(with = InstantSerializer::class) val timestamp: Instant,
    /** Random 12-byte nonce unique per envelope. */
    @ProtoNumber(5) val nonce: ByteArray,
    /** Zero-based index of this fragment within a multi-envelope message. 0 if not fragmented. */
    @ProtoNumber(6) val fragmentIndex: Int,
    /** Total number of envelopes in this message. 1 means not fragmented. */
    @ProtoNumber(7) val fragmentCount: Int,
    /** ChaCha20-Poly1305 ciphertext. */
    @ProtoNumber(8) val payload: ByteArray,
    /** Ed25519 signature (64 bytes) over all other fields including [payload]. */
    @ProtoNumber(9) val signature: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as StatelessEnvelope

        if (schema != other.schema) return false
        if (timestamp != other.timestamp) return false
        if (fragmentIndex != other.fragmentIndex) return false
        if (fragmentCount != other.fragmentCount) return false
        if (senderId != other.senderId) return false
        if (receiverId != other.receiverId) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (!payload.contentEquals(other.payload)) return false
        if (!signature.contentEquals(other.signature)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = schema
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + fragmentIndex
        result = 31 * result + fragmentCount
        result = 31 * result + senderId.hashCode()
        result = 31 * result + receiverId.hashCode()
        return result
    }

    override fun toString(): String {
        return "StatelessEnvelope(schema=$schema, senderId='$senderId', receiverId='$receiverId', timestamp=$timestamp, fragmentIndex=$fragmentIndex, fragmentCount=$fragmentCount)"
    }
}
