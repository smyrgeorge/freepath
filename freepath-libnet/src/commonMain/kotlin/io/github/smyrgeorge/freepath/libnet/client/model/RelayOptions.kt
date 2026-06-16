package io.github.smyrgeorge.freepath.libnet.client.model

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Call-site input for [io.github.smyrgeorge.freepath.libnet.client.codec.StatelessEnvelopeCodec.seal] when relay is needed.
 * `messageId` is computed internally by the codec (from nonce + ephemeralKey) and is NOT a field
 * here — read it from [RelayMetadata] after sealing.
 */
data class RelayOptions(
    /** Initial copy budget (L) for binary Spray-and-Wait. Clamped to GLOBAL_MAX_COPIES on receipt. */
    val copies: Int = DEFAULT_COPIES,
    /** Priority hint. */
    val priority: Int = 1,
    /** Absolute expiry for the relay copy. Clamped to MAX_TTL_DURATION on receipt. */
    val expiresAt: Instant = Clock.System.now() + DEFAULT_TTL_DURATION,
) {
    companion object {
        /** Initial number of copies (L) sprayed into the mesh per binary Spray-and-Wait. */
        const val DEFAULT_COPIES: Int = 8

        /** Default lifetime of a store-and-forward copy before it expires. */
        val DEFAULT_TTL_DURATION: Duration = 7.days
    }
}
