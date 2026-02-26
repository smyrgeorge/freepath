package io.github.smyrgeorge.freepath.transport.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class Frame(
    val schema: Int,
    val streamId: String,
    /** Sequence number (uint32, range 0..0xFFFFFFFF represented as Long). */
    val seq: Long,
    /**
     * Raw type string as received on the wire. Serialised as the JSON "type" field.
     * Using the raw string (rather than the enum name) in AEAD AAD computation ensures
     * that a future-version sender's type bytes are reproduced exactly, allowing AEAD
     * verification to succeed even for frame types unknown to this implementation.
     */
    @SerialName("type") val wireType: String,
    /** Base64-encoded payload bytes. Interpretation depends on [type]. */
    val payload: String,
) {
    /**
     * Parsed frame type. [FrameType.UNKNOWN] for any wire value not recognised by
     * this implementation. Use [wireType] (not this property's name) for AEAD AAD.
     */
    @Transient
    val type: FrameType = FrameType.fromWireString(wireType)

    /** Convenience constructor for creating outgoing frames from a known [FrameType]. */
    constructor(schema: Int, streamId: String, seq: Long, type: FrameType, payload: String) :
            this(schema, streamId, seq, type.name, payload)
}
