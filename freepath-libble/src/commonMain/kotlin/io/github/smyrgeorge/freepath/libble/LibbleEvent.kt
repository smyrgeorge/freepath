package io.github.smyrgeorge.freepath.libble

import io.github.smyrgeorge.freepath.contact.Contact
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

    /**
     * A discovered peripheral has been matched to a known contact via the routing table.
     * Emitted once per peripheral, the first time the match is found.
     */
    data class PeerIdentified(val peerId: String, val peripheralId: String) : LibbleEvent()

    /**
     * A previously identified peripheral has expired and is no longer in range.
     */
    data class PeerLost(val peerId: String) : LibbleEvent()

    sealed class ContactExchange : LibbleEvent() {
        /** Exchange handshake has started. */
        data object Started : ContactExchange()

        /** PIN confirmed by both parties; card decryption succeeded. */
        data object PinConfirmed : ContactExchange()

        /** Exchange completed successfully. */
        data class Completed(val contact: Contact) : ContactExchange()

        /** Exchange failed or was aborted. */
        data class Failed(val reason: String) : ContactExchange()
    }

    /**
     * A central wrote a request frame to the REQUEST characteristic on our GATT server.
     * [peripheralId] is the OS-assigned BLE address/UUID of the remote central.
     */
    class RequestReceived(val peripheralId: String, val reqId: Long, val payload: ByteArray) : LibbleEvent()

    sealed class Response : LibbleEvent()

    data class RequestFailed(
        val reqId: Long,
        val peripheralId: String,
        val error: String
    ) : Response()

    data class ResponseReceived(
        val reqId: Long,
        val peripheralId: String,
        val payload: ByteArray
    ) : Response() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as ResponseReceived
            return reqId == other.reqId
        }

        override fun hashCode(): Int = reqId.hashCode()
        override fun toString(): String =
            "ResponseReceived(reqId=$reqId, peripheralId='$peripheralId', payload=${payload.size} bytes)"
    }
}
