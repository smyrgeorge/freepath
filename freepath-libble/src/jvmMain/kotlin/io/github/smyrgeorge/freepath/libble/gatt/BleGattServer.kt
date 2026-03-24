package io.github.smyrgeorge.freepath.libble.gatt

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

actual class BleGattServer actual constructor() : BleGattServerPort {
    private val _receivedCards = MutableSharedFlow<ByteArray>()
    actual override val receivedCards: SharedFlow<ByteArray> = _receivedCards

    private val started = AtomicBoolean(false)

    actual override suspend fun start(localCardBytes: ByteArray) {
        started.compareAndSet(false, true)
        // Intentionally left blank — JVM has no BLE.
    }

    actual override suspend fun stop() {
        started.compareAndSet(true, false)
    }
}