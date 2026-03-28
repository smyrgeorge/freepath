package io.github.smyrgeorge.freepath.libble.pool

import kotlinx.coroutines.flow.Flow

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
    suspend fun writeContactCard(bytes: ByteArray)

    /** Reads the peer's response (pinConfirm + encrypted card) from CARD. */
    suspend fun readContactCard(): ByteArray

    /** Writes the exchange [status] byte to STATUS. */
    suspend fun writeStatus(status: Byte)

    /**
     * Writes a generic request frame to REQUEST: reqId (8 bytes LE) + payload.
     * Used by the initiator side of the BLE RPC protocol.
     */
    suspend fun writeRequest(reqId: Long, payload: ByteArray)

    /**
     * Returns a [Flow] that emits raw response frames from the RESPONSE notify characteristic.
     * Each frame: reqId (8 bytes LE) + status (1 byte) + payload/error bytes.
     * Subscribes to BLE notifications when collected.
     */
    fun observeResponses(): Flow<ByteArray>
}