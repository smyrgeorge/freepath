package io.github.smyrgeorge.freepath.libble.l2cap

import io.github.smyrgeorge.freepath.libble.pool.BleL2capChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

actual class BleL2capServer actual constructor() {
    actual val psm: Int = 0
    actual val incoming: Flow<BleL2capChannel> = emptyFlow()
    actual suspend fun start() = Unit
    actual suspend fun stop() = Unit
}
