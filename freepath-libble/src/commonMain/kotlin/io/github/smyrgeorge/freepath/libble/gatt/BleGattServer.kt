package io.github.smyrgeorge.freepath.libble.gatt

import kotlinx.coroutines.flow.SharedFlow

expect class BleGattServer() : BleGattServerPort {
    override val receivedCards: SharedFlow<ByteArray>
    override suspend fun start(localCardBytes: ByteArray)
    override suspend fun stop()
}