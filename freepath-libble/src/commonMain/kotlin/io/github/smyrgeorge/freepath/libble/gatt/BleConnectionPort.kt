package io.github.smyrgeorge.freepath.libble.gatt

interface BleConnectionPort {
    suspend fun connect()

    /** Reads the peer's signed contact card bytes from CARD_READ. */
    suspend fun readCard(): ByteArray

    /** Writes [bytes] (own signed card) to the peer's CARD_WRITE characteristic. */
    suspend fun writeCard(bytes: ByteArray)
    suspend fun disconnect()
}