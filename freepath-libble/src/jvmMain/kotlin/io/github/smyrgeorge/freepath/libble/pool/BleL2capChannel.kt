package io.github.smyrgeorge.freepath.libble.pool

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

actual class BleL2capChannel(actual val peripheralId: String) {
    actual val incoming: Flow<BleFrame> = emptyFlow()
    actual suspend fun send(type: BleFrameType, payload: ByteArray) = Unit
    actual suspend fun close() = Unit
    actual companion object {
        actual suspend fun connect(peripheralId: String, psm: Int): BleL2capChannel =
            error("BLE L2CAP not supported on JVM")
    }
}
