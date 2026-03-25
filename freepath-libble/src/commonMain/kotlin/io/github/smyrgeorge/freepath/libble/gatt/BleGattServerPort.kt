package io.github.smyrgeorge.freepath.libble.gatt

import kotlinx.coroutines.flow.SharedFlow

interface BleGattServerPort {
    val receivedCards: SharedFlow<ByteArray>
    suspend fun start(localCardBytes: ByteArray)
    suspend fun stop()
}