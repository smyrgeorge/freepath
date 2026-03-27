package io.github.smyrgeorge.freepath.libble.gatt

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

actual class BleGattServer actual constructor() : BleGattServerPort {
    actual override val events: SharedFlow<BleGattServerPort.Event> = MutableSharedFlow()
    actual override suspend fun setEphemeralValue(bytes: ByteArray) {}
    actual override suspend fun setCardValue(bytes: ByteArray) {}
    actual override suspend fun start() {}
    actual override suspend fun stop() {}
}
