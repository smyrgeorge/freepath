package io.github.smyrgeorge.freepath.libble.gatt

import kotlinx.coroutines.flow.SharedFlow

expect class BleGattServer() : BleGattServerPort {
    override val events: SharedFlow<BleGattServerPort.Event>
    override suspend fun setEphemeralValue(bytes: ByteArray)
    override suspend fun setCardValue(bytes: ByteArray)
    override suspend fun start()
    override suspend fun stop()
}
