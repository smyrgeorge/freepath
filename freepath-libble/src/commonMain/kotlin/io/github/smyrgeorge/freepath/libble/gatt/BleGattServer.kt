package io.github.smyrgeorge.freepath.libble.gatt

import kotlinx.coroutines.flow.SharedFlow

expect class BleGattServer() {
    val events: SharedFlow<BleGattServerEvent>
    suspend fun setEphemeralValue(bytes: ByteArray)
    suspend fun setContactValue(bytes: ByteArray)
    suspend fun sendResponse(reqId: Long, payload: ByteArray)
    suspend fun sendResponseFailed(reqId: Long, error: String)
    suspend fun start()
    suspend fun stop()
}
