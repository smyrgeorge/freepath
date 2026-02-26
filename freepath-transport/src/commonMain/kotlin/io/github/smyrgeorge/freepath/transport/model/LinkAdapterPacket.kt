package io.github.smyrgeorge.freepath.transport.model

data class LinkAdapterPacket(
    /** Matches the [Frame.seq] of the Frame being transmitted. */
    val seq: Long,
    /** Zero-based fragment index; 0 if not fragmented. */
    val fragIndex: Int,
    /** Total fragment count; 1 if not fragmented. */
    val fragCount: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinkAdapterPacket) return false
        return seq == other.seq
                && fragIndex == other.fragIndex
                && fragCount == other.fragCount
                && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = seq.hashCode()
        result = 31 * result + fragIndex
        result = 31 * result + fragCount
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object {
        /** Fixed header size in bytes: SEQ(4) + FRAG_INDEX(2) + FRAG_COUNT(2) + LENGTH(4). */
        const val HEADER_SIZE = 12
        const val MAX_FRAG_COUNT = 1024

        /** Reassembly timeout in milliseconds. */
        const val REASSEMBLY_TIMEOUT_MS = 30_000L

        /** Max concurrent SEQ values awaiting reassembly. */
        const val MAX_PENDING_REASSEMBLY = 64
    }
}
