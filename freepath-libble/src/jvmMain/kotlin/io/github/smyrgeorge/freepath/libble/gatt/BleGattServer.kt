package io.github.smyrgeorge.freepath.libble.gatt

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

actual class BleGattServer actual constructor() {
    actual val events: SharedFlow<BleGattServerEvent> = MutableSharedFlow()
    actual suspend fun setEphemeralValue(bytes: ByteArray) {}
    actual suspend fun setContactValue(bytes: ByteArray) {}
    actual suspend fun sendResponse(reqId: Long, payload: ByteArray) {}
    actual suspend fun sendResponseFailed(reqId: Long, error: String) {}
    actual suspend fun start() {}
    actual suspend fun stop() {}
}
