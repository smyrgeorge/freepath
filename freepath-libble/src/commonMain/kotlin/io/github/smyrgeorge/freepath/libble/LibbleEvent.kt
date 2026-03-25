package io.github.smyrgeorge.freepath.libble

import kotlin.time.Instant

sealed class LibbleEvent {
    data class PeripheralDiscovered(
        val discoveredAt: Instant,
        val peripheralId: String,
        val peripheralName: String?,
        val name: String?,
        val rssi: Int,
        val txPower: Int?,
        val isConnectable: Boolean?,
    ) : LibbleEvent()

    data class PeripheralExpired(val peripheralId: String) : LibbleEvent()
    data class PeripheralConnected(val peripheralId: String) : LibbleEvent()
    data class PeripheralDisconnected(val peripheralId: String) : LibbleEvent()
    class ContactCardReceived(val cardBytes: ByteArray) : LibbleEvent()
}
