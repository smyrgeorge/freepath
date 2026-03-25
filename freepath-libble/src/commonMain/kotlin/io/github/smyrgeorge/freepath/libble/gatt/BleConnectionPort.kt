package io.github.smyrgeorge.freepath.libble.gatt

interface BleConnectionPort {
    suspend fun connect()
    suspend fun disconnect()

    /** Writes an empty payload to the PING characteristic and awaits the GATT ACK. */
    suspend fun ping()

    /** Reads the peer's signed contact card bytes from CARD_READ. */
    suspend fun readCard(): ByteArray

    /** Writes [bytes] (own signed card) to the peer's CARD_WRITE characteristic. */
    suspend fun writeCard(bytes: ByteArray)
}