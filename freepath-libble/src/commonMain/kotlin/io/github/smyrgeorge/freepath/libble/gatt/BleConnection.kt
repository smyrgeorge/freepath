package io.github.smyrgeorge.freepath.libble.gatt

import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import io.github.smyrgeorge.freepath.libble.BleConstants
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
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

    override suspend fun connect() {
        peripheral.connect()
    }

    override suspend fun disconnect() {
        runCatching { peripheral.disconnect() }
        peripheral.close()
    }

    override suspend fun ping(): Unit =
        peripheral.write(pingChar, byteArrayOf(), WriteType.WithResponse)

    override suspend fun writeEphemeral(bytes: ByteArray): Unit =
        peripheral.write(ephemeralChar, bytes, WriteType.WithResponse)

    override suspend fun readEphemeral(): ByteArray =
        peripheral.read(ephemeralChar)

    override suspend fun writeCard(bytes: ByteArray): Unit =
        peripheral.write(cardChar, bytes, WriteType.WithResponse)

    override suspend fun readCard(): ByteArray =
        peripheral.read(cardChar)

    override suspend fun writeStatus(status: Byte): Unit =
        peripheral.write(statusChar, byteArrayOf(status), WriteType.WithResponse)
}
