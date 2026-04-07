package io.github.smyrgeorge.freepath.libnet.client.model

import kotlin.time.Clock

internal class ReassemblyBuffer(
    val frameCount: Int,
) {
    var updatedAt = Clock.System.now()
        private set

    private val frames = arrayOfNulls<ByteArray>(frameCount)
    private var receivedCount = 0

    /**
     * Stores [chunk] at [frameIndex]. Returns true when all frames have been received.
     * If [frameIndex] was already received, the existing chunk is replaced (last-write-wins).
     */
    fun add(frameIndex: Int, chunk: ByteArray): Boolean {
        if (frames[frameIndex] == null) receivedCount++
        frames[frameIndex] = chunk
        updatedAt = Clock.System.now()
        return receivedCount == frameCount
    }

    /** Concatenates all frames in order. Call only after [add] has returned true. */
    fun assemble(): ByteArray {
        val totalSize = frames.sumOf { it?.size ?: 0 }
        val result = ByteArray(totalSize)
        var off = 0
        frames.forEachIndexed { idx, frame ->
            val b = checkNotNull(frame) { "Frame $idx missing during assembly (frameCount=$frameCount)" }
            b.copyInto(result, off)
            off += b.size
        }
        return result
    }
}