package io.github.smyrgeorge.freepath.contact.exchange

/**
 * Represents the method used for exchanging contact cards.
 *
 * Each method has different capabilities and user experience characteristics.
 */
enum class ContactExchangeMethod(
    /**
     * Whether this exchange method supports bidirectional exchange.
     *
     * - `true`: Both parties can exchange cards simultaneously (e.g., NFC, Bluetooth).
     * - `false`: Only unidirectional exchange is supported (e.g., QR code).
     */
    val isBidirectional: Boolean,
) {
    /**
     * QR code-based exchange.
     *
     * Unidirectional by default. One device displays a QR code, the other scans it.
     * Suitable for cases where only one party needs to share their identity.
     *
     * For bidirectional exchange, the flow must be repeated in the opposite direction.
     */
    QR(isBidirectional = false),

    /**
     * NFC tap-based exchange.
     *
     * Bidirectional by default. Users tap their devices together to initiate exchange.
     * On iOS, NFC is used to bootstrap a Bluetooth connection since third-party apps
     * cannot push NDEF data to another device.
     */
    NFC(isBidirectional = true),

    /**
     * Bluetooth LE-based exchange.
     *
     * Bidirectional by default. Both devices advertise and discover each other,
     * then exchange contact cards over a Bluetooth LE connection.
     */
    BLUETOOTH(isBidirectional = true),
}
