package io.github.smyrgeorge.freepath.libble.gatt

sealed class BleGattServerEvent {
    /** Initiator wrote its ephemeral X25519 public key to EPHEMERAL. */
    class EphemeralReceived(val centralId: String, val bytes: ByteArray) : BleGattServerEvent()

    /** Initiator wrote its pinConfirm + encrypted card to CARD. */
    class CardReceived(val bytes: ByteArray) : BleGattServerEvent()

    /** Initiator wrote the exchange result byte to STATUS. */
    class StatusReceived(val status: Byte) : BleGattServerEvent()

    /**
     * A central wrote a generic request frame to REQUEST.
     * [senderId] identifies the remote central (Bluetooth address on Android, UUID on iOS).
     */
    class RequestReceived(val senderId: String, val reqId: Long, val payload: ByteArray) : BleGattServerEvent()
}
