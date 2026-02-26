package io.github.smyrgeorge.freepath.transport.model

data class SessionState(
    val streamId: String,
    val sessionKey: ByteArray,
    /** Last seq sent on outbound direction; -1 means no frame sent yet. */
    var outboundSeq: Long = -1L,
    /** Last accepted seq on inbound direction; -1 means none accepted yet. */
    var inboundSeq: Long = -1L,
) {
    /** Returns the next outbound seq (uint32 range) and advances the counter. */
    fun nextOutboundSeq(): Long {
        val next = outboundSeq + 1L
        require(next <= SEQ_MAX) { "outboundSeq overflow: session must be torn down before seq reaches 0xFFFFFFFF" }
        outboundSeq = next
        return outboundSeq
    }

    /** Returns true if [seq] is a valid uint32 and strictly greater than the last accepted inbound seq. */
    fun isValidInboundSeq(seq: Long): Boolean = seq in 0L..SEQ_MAX && seq > inboundSeq

    fun acceptInboundSeq(seq: Long) {
        require(seq in 0L..SEQ_MAX) { "inboundSeq out of uint32 range: $seq" }
        inboundSeq = seq
    }

    companion object {
        /** Session MUST be torn down before seq reaches this value. */
        const val SEQ_ROLLOVER_THRESHOLD = 0xFFFFFFF0L
        const val SEQ_MAX = 0xFFFFFFFFL
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionState) return false
        return streamId == other.streamId && sessionKey.contentEquals(other.sessionKey)
    }

    override fun hashCode(): Int = 31 * streamId.hashCode() + sessionKey.contentHashCode()
}
