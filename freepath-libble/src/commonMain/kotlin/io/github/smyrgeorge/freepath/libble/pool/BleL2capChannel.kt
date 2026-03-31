package io.github.smyrgeorge.freepath.libble.pool

import kotlinx.coroutines.flow.Flow

/**
 * A bidirectional framed channel over a BLE L2CAP CoC connection.
 *
 * The read loop runs internally and emits decoded [BleFrame]s on [incoming].
 * [send] writes a length-prefixed frame to the remote peer.
 * [close] terminates the underlying socket and the read loop.
 *
 * Use the companion [connect] factory to open an outbound connection.
 * Inbound connections are emitted by [BleL2capServer.incoming].
 */
expect class BleL2capChannel {
    /** Platform identifier of the remote peer (MAC on Android, UUID string on iOS). */
    val peripheralId: String

    /** Decoded frames from the remote peer. One collector only (pool's receive loop). */
    val incoming: Flow<BleFrame>

    suspend fun send(type: BleFrameType, payload: ByteArray)
    suspend fun close()

    companion object {
        /** Open an outbound L2CAP channel to [peripheralId] on [psm]. */
        suspend fun connect(peripheralId: String, psm: Int): BleL2capChannel
    }
}
