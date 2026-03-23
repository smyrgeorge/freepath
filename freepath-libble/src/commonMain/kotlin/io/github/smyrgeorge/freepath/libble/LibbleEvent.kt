package io.github.smyrgeorge.freepath.libble

import kotlin.time.Instant

sealed class LibbleEvent {
    data class BlePeripheralDiscovered(
        val discoveredAt: Instant,
        val peripheralId: String,
        val peripheralName: String?,
        val name: String?,
        val rssi: Int,
        val txPower: Int?,
        val isConnectable: Boolean?,
    ) : LibbleEvent()

    data class BlePeripheralExpired(val peripheralId: String) : LibbleEvent()
}
