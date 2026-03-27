package io.github.smyrgeorge.freepath.libble.gatt

import kotlinx.coroutines.flow.SharedFlow

interface BleGattServerPort {
    /** Stream of exchange-related write events received from the initiator. */
    val events: SharedFlow<Event>

    /** Sets the value returned for EPHEMERAL reads (responder's ephemeral public key). */
    suspend fun setEphemeralValue(bytes: ByteArray)

    /** Sets the value returned for CARD reads (responder's pinConfirm + encrypted card). */
    suspend fun setCardValue(bytes: ByteArray)

    suspend fun start()
    suspend fun stop()

    sealed class Event {
        /** Initiator wrote its ephemeral X25519 public key to EPHEMERAL. */
        class EphemeralReceived(val bytes: ByteArray) : Event()

        /** Initiator wrote its pinConfirm + encrypted card to CARD. */
        class CardReceived(val bytes: ByteArray) : Event()

        /** Initiator wrote the exchange result byte to STATUS. */
        class StatusReceived(val status: Byte) : Event()
    }
}
