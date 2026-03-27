package io.github.smyrgeorge.freepath.libble

import io.github.smyrgeorge.freepath.contact.ContactCard
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

    sealed class ContactExchange : LibbleEvent() {
        /** Exchange handshake has started. */
        data object Started : ContactExchange()

        /** PIN confirmed by both parties; card decryption succeeded. */
        data object PinConfirmed : ContactExchange()

        /** Exchange completed successfully. */
        data class Completed(val peerCard: ContactCard) : ContactExchange()

        /** Exchange failed or was aborted. */
        data class Failed(val reason: String) : ContactExchange()
    }
}
