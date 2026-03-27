package io.github.smyrgeorge.freepath.libble.gatt

interface BleConnectionPort {
    suspend fun connect()
    suspend fun disconnect()

    /** Writes an empty payload to the PING characteristic and awaits the GATT ACK. */
    suspend fun ping()

    /** Writes the local ephemeral X25519 public key (32 bytes) to EPHEMERAL. */
    suspend fun writeEphemeral(bytes: ByteArray)

    /** Reads the peer's ephemeral X25519 public key (32 bytes) from EPHEMERAL. */
    suspend fun readEphemeral(): ByteArray

    /** Writes [bytes] (pinConfirm + encrypted card) to CARD. */
    suspend fun writeCard(bytes: ByteArray)

    /** Reads the peer's response (pinConfirm + encrypted card) from CARD. */
    suspend fun readCard(): ByteArray

    /** Writes the exchange [status] byte to STATUS. */
    suspend fun writeStatus(status: Byte)
}
