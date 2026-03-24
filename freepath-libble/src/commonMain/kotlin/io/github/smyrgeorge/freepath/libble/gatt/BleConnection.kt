package io.github.smyrgeorge.freepath.libble.gatt

import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import io.github.smyrgeorge.freepath.libble.BleConstants
import kotlin.uuid.ExperimentalUuidApi

/**
 * Wraps a Kable [com.juul.kable.Peripheral] as a GATT central for contact card exchange.
 *
 * Lifecycle: [connect] → [readCard] / [writeCard] → [disconnect].
 * Created via [io.github.smyrgeorge.freepath.libble.LibbleModule.connect].
 */
@OptIn(ExperimentalUuidApi::class)
class BleConnection internal constructor(
    private val peripheral: Peripheral,
) : BleConnectionPort {

    private val cardReadChar = characteristicOf(
        service = BleConstants.FREEPATH_SERVICE_UUID,
        characteristic = BleConstants.CARD_READ_UUID,
    )

    private val cardWriteChar = characteristicOf(
        service = BleConstants.FREEPATH_SERVICE_UUID,
        characteristic = BleConstants.CARD_WRITE_UUID,
    )

    override suspend fun connect() {
        peripheral.connect()
    }

    override suspend fun readCard(): ByteArray = peripheral.read(cardReadChar)

    override suspend fun writeCard(bytes: ByteArray) =
        peripheral.write(cardWriteChar, bytes, WriteType.WithResponse)

    override suspend fun disconnect() {
        peripheral.disconnect()
        peripheral.close()
    }
}