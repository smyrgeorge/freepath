package io.github.smyrgeorge.freepath.libble.gatt

import kotlinx.coroutines.flow.SharedFlow

/** Thin interface over BleGattServer for testability. */
interface BleGattServerPort {
    /** Emits each received card payload when a peer writes to CARD_WRITE. */
    val receivedCards: SharedFlow<ByteArray>
    suspend fun start(localCardBytes: ByteArray)
    suspend fun stop()
}