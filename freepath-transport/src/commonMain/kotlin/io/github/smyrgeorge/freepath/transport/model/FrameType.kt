package io.github.smyrgeorge.freepath.transport.model

import kotlinx.serialization.Serializable

@Serializable
enum class FrameType {
    HANDSHAKE,
    DATA,
    ACK,
    CLOSE,

    /**
     * Sentinel for any frame type string not recognised by this implementation.
     * Per spec, receivers MUST silently discard such frames after AEAD verification
     * passes. UNKNOWN MUST NOT cause session teardown or any other side effect.
     */
    UNKNOWN;

    companion object {
        /**
         * Maps a raw wire type string to the corresponding [FrameType], falling back
         * to [UNKNOWN] for any value not recognised by this implementation version.
         * This preserves the original wire string in [Frame.wireType] so that AEAD
         * AAD computation uses the sender's exact type bytes, not the sentinel name.
         */
        fun fromWireString(s: String): FrameType =
            entries.firstOrNull { it.name == s } ?: UNKNOWN
    }
}
