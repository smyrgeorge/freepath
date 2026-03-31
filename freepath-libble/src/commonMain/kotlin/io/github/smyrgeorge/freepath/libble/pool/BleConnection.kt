package io.github.smyrgeorge.freepath.libble.pool

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import io.github.smyrgeorge.freepath.libble.BleConstants
import kotlinx.coroutines.flow.Flow

class BleConnection(
    private val peripheral: Peripheral,
) {

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

    suspend fun connect() {
        peripheral.connect()
    }

    suspend fun disconnect() {
        try {
            peripheral.disconnect()
        } finally {
            peripheral.close()
        }
    }

    suspend fun ping(): Unit =
        peripheral.write(pingChar, byteArrayOf(), WriteType.WithResponse)

    suspend fun writeEphemeral(bytes: ByteArray): Unit =
        peripheral.write(ephemeralChar, bytes, WriteType.WithResponse)

    suspend fun readEphemeral(): ByteArray =
        peripheral.read(ephemeralChar)

    suspend fun writeContact(bytes: ByteArray): Unit =
        peripheral.write(cardChar, bytes, WriteType.WithResponse)

    suspend fun readContact(): ByteArray =
        peripheral.read(cardChar)

    suspend fun writeStatus(status: Byte): Unit =
        peripheral.write(statusChar, byteArrayOf(status), WriteType.WithResponse)

    suspend fun writeRequest(reqId: Long, payload: ByteArray) {
        val frame = ByteArray(8) { i -> (reqId shr (i * 8)).toByte() } + payload
        peripheral.write(requestChar, frame, WriteType.WithResponse)
    }

    fun observe(): Flow<ByteArray> = peripheral.observe(responseChar)

    companion object {
        fun of(advertisement: Advertisement): BleConnection = BleConnection(Peripheral(advertisement))
    }
}