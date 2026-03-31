package io.github.smyrgeorge.freepath.libble.l2cap

import io.github.smyrgeorge.freepath.libble.pool.BleL2capChannel
import kotlinx.coroutines.flow.Flow

/**
 * Accepts incoming BLE L2CAP CoC connections.
 *
 * Call [start] to bind the server socket and obtain a [psm] assigned by the OS.
 * Accepted connections are emitted on [incoming] — one emission per accepted channel.
 * Call [stop] to release the socket.
 */
expect class BleL2capServer() {
    /** Protocol/Service Multiplexer value. Valid only after [start]. */
    val psm: Int

    /** Emits one [BleL2capChannel] per accepted inbound connection. */
    val incoming: Flow<BleL2capChannel>

    suspend fun start()
    suspend fun stop()
}
