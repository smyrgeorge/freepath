package io.github.smyrgeorge.freepath.libnet.client.model

/**
 * Call-site input for [io.github.smyrgeorge.freepath.libnet.client.codec.StatelessEnvelopeCodec.seal] when relay is needed.
 * `messageId` is computed internally by [io.github.smyrgeorge.freepath.libnet.client.codec.StatelessEnvelopeCodec.seal]
 * from nonce + ephemeralKey and is NOT a field here — use [RelayMetadata] to read it after sealing.
 */
data class RelayOptions(
    val ttl: Int = DEFAULT_RELAY_TTL,
    val priority: Int = 1,
    val pow: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as RelayOptions
        if (ttl != other.ttl) return false
        if (priority != other.priority) return false
        if (!pow.contentEquals(other.pow)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = ttl
        result = 31 * result + priority
        result = 31 * result + pow.contentHashCode()
        return result
    }

    override fun toString(): String =
        "RelayOptions(ttl=$ttl, priority=$priority, pow=${pow.size}B)"

    companion object {
        /**
         * Initial relay hop budget (TTL) for a store-and-forward copy. Each mesh forward attempt
         * decrements it; the entry is discarded once it reaches zero.
         */
        const val DEFAULT_RELAY_TTL: Int = 16
    }
}
