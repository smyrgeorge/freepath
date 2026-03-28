package io.github.smyrgeorge.freepath.libble.gatt

import kotlinx.coroutines.flow.SharedFlow

interface BleGattServerPort {
    /** Stream of write events received from connected centrals. */
    val events: SharedFlow<Event>

    /** Sets the value returned for EPHEMERAL reads (responder's ephemeral public key). */
    suspend fun setEphemeralValue(bytes: ByteArray)

    /** Sets the value returned for CARD reads (responder's pinConfirm + encrypted card). */
    suspend fun setCardValue(bytes: ByteArray)

    /**
     * Sends a success response notification to the central that sent request [reqId].
     * Frame: reqId (8 bytes LE) + 0x00 + payload.
     */
    suspend fun sendResponse(reqId: Long, payload: ByteArray)

    /**
     * Sends a failure response notification to the central that sent request [reqId].
     * Frame: reqId (8 bytes LE) + 0x01 + error (UTF-8).
     */
    suspend fun sendResponseFailed(reqId: Long, error: String)

    suspend fun start()
    suspend fun stop()

    sealed class Event {
        /** Initiator wrote its ephemeral X25519 public key to EPHEMERAL. */
        class EphemeralReceived(val bytes: ByteArray) : Event()

        /** Initiator wrote its pinConfirm + encrypted card to CARD. */
        class CardReceived(val bytes: ByteArray) : Event()

        /** Initiator wrote the exchange result byte to STATUS. */
        class StatusReceived(val status: Byte) : Event()

        /**
         * A central wrote a generic request frame to REQUEST.
         * [peripheralId] identifies the remote device (Bluetooth address on Android, UUID on iOS).
         */
        class RequestReceived(val peripheralId: String, val reqId: Long, val payload: ByteArray) : Event()
    }
}
