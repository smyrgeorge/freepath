package io.github.smyrgeorge.freepath.libble.pool

import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import io.github.smyrgeorge.freepath.libble.BleConstants
import kotlinx.coroutines.flow.Flow

class BleConnection internal constructor(
    private val peripheral: Peripheral,
) : BleConnectionPort {

    private val pingChar = characteristicOf(
        service = BleConstants.FREEPATH_SERVICE_UUID,
        characteristic = BleConstants.PING_UUID,
    )
    private val ephemeralChar = characteristicOf(
        service = BleConstants.FREEPATH_SERVICE_UUID,
        characteristic = BleConstants.EPHEMERAL_UUID,
    )
    private val cardChar = characteristicOf(
        service = BleConstants.FREEPATH_SERVICE_UUID,
        characteristic = BleConstants.CARD_UUID,
    )
    private val statusChar = characteristicOf(
        service = BleConstants.FREEPATH_SERVICE_UUID,
        characteristic = BleConstants.STATUS_UUID,
    )
    private val requestChar = characteristicOf(
        service = BleConstants.FREEPATH_SERVICE_UUID,
        characteristic = BleConstants.REQUEST_UUID,
    )
    private val responseChar = characteristicOf(
        service = BleConstants.FREEPATH_SERVICE_UUID,
        characteristic = BleConstants.RESPONSE_UUID,
    )

    override suspend fun connect() {
        peripheral.connect()
    }

    override suspend fun disconnect() {
        try {
            peripheral.disconnect()
        } finally {
            peripheral.close()
        }
    }

    override suspend fun ping(): Unit =
        peripheral.write(pingChar, byteArrayOf(), WriteType.WithResponse)

    override suspend fun writeEphemeral(bytes: ByteArray): Unit =
        peripheral.write(ephemeralChar, bytes, WriteType.WithResponse)

    override suspend fun readEphemeral(): ByteArray =
        peripheral.read(ephemeralChar)

    override suspend fun writeContact(bytes: ByteArray): Unit =
        peripheral.write(cardChar, bytes, WriteType.WithResponse)

    override suspend fun readContact(): ByteArray =
        peripheral.read(cardChar)

    override suspend fun writeStatus(status: Byte): Unit =
        peripheral.write(statusChar, byteArrayOf(status), WriteType.WithResponse)

    override suspend fun writeRequest(reqId: Long, payload: ByteArray) {
        val frame = ByteArray(8) { i -> (reqId shr (i * 8)).toByte() } + payload
        peripheral.write(requestChar, frame, WriteType.WithResponse)
    }

    override fun observeResponses(): Flow<ByteArray> = peripheral.observe(responseChar)
}