package io.github.smyrgeorge.freepath.libble.gatt

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

actual class BleGattServer actual constructor() : BleGattServerPort {
    actual override val events: SharedFlow<BleGattServerPort.Event> = MutableSharedFlow()
    actual override suspend fun setEphemeralValue(bytes: ByteArray) {}
    actual override suspend fun setContactValue(bytes: ByteArray) {}
    actual override suspend fun sendResponse(reqId: Long, payload: ByteArray) {}
    actual override suspend fun sendResponseFailed(reqId: Long, error: String) {}
    actual override suspend fun start() {}
    actual override suspend fun stop() {}
}
