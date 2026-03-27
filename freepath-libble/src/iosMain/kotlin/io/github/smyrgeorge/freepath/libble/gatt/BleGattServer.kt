package io.github.smyrgeorge.freepath.libble.gatt

import io.github.smyrgeorge.freepath.libble.BleConstants
import io.github.smyrgeorge.freepath.libble.PeripheralManagerHolder
import io.github.smyrgeorge.log4k.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import platform.CoreBluetooth.CBATTErrorRequestNotSupported
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerStatePoweredOn
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalAtomicApi::class, ExperimentalUuidApi::class, ExperimentalForeignApi::class)
actual class BleGattServer actual constructor() : BleGattServerPort {

    private val log = Logger.of(this::class)

    private val _events = MutableSharedFlow<BleGattServerPort.Event>(extraBufferCapacity = 8)
    actual override val events: SharedFlow<BleGattServerPort.Event> = _events

    private val started = AtomicBoolean(false)

    @Volatile
    private var ephemeralValue: ByteArray = byteArrayOf()

    @Volatile
    private var cardValue: ByteArray = byteArrayOf()

    private val manager: CBPeripheralManager get() = PeripheralManagerHolder.manager.manager

    private val pingChar = CBMutableCharacteristic(
        type = CBUUID.UUIDWithString(BleConstants.PING_UUID.toString()),
        properties = CBCharacteristicPropertyWrite,
        value = null,
        permissions = CBAttributePermissionsWriteable,
    )
    private val ephemeralChar = CBMutableCharacteristic(
        type = CBUUID.UUIDWithString(BleConstants.EPHEMERAL_UUID.toString()),
        properties = CBCharacteristicPropertyRead or CBCharacteristicPropertyWrite,
        value = null,
        permissions = CBAttributePermissionsReadable or CBAttributePermissionsWriteable,
    )
    private val cardChar = CBMutableCharacteristic(
        type = CBUUID.UUIDWithString(BleConstants.CARD_UUID.toString()),
        properties = CBCharacteristicPropertyRead or CBCharacteristicPropertyWrite,
        value = null,
        permissions = CBAttributePermissionsReadable or CBAttributePermissionsWriteable,
    )
    private val statusChar = CBMutableCharacteristic(
        type = CBUUID.UUIDWithString(BleConstants.STATUS_UUID.toString()),
        properties = CBCharacteristicPropertyWrite,
        value = null,
        permissions = CBAttributePermissionsWriteable,
    )

    actual override suspend fun setEphemeralValue(bytes: ByteArray) {
        ephemeralValue = bytes
    }

    actual override suspend fun setCardValue(bytes: ByteArray) {
        cardValue = bytes
    }

    private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
    }

    private val readHandler: (CBATTRequest) -> Unit = readHandler@{ request ->
        if (!started.load()) return@readHandler
        val data = when (request.characteristic.UUID) {
            ephemeralChar.UUID -> ephemeralValue
            cardChar.UUID -> cardValue
            else -> null
        }
        if (data != null) {
            request.value = data.toNSData()
            manager.respondToRequest(request, CBATTErrorSuccess)
        } else {
            manager.respondToRequest(request, CBATTErrorRequestNotSupported)
        }
    }

    private val writeHandler: (List<CBATTRequest>) -> Unit = writeHandler@{ requests ->
        if (!started.load()) return@writeHandler
        requests.forEach { req ->
            when (req.characteristic.UUID) {
                pingChar.UUID -> {
                    manager.respondToRequest(req, CBATTErrorSuccess)
                }

                ephemeralChar.UUID -> {
                    val bytes = req.value?.bytes?.readBytes(req.value!!.length.toInt()) ?: byteArrayOf()
                    _events.tryEmit(BleGattServerPort.Event.EphemeralReceived(bytes))
                    manager.respondToRequest(req, CBATTErrorSuccess)
                }

                cardChar.UUID -> {
                    val bytes = req.value?.bytes?.readBytes(req.value!!.length.toInt()) ?: byteArrayOf()
                    _events.tryEmit(BleGattServerPort.Event.CardReceived(bytes))
                    manager.respondToRequest(req, CBATTErrorSuccess)
                }

                statusChar.UUID -> {
                    val status = req.value?.bytes?.readBytes(1)?.firstOrNull() ?: 0x02
                    _events.tryEmit(BleGattServerPort.Event.StatusReceived(status))
                    manager.respondToRequest(req, CBATTErrorSuccess)
                }

                else -> manager.respondToRequest(req, CBATTErrorRequestNotSupported)
            }
        }
    }

    private val stateListener: (CBPeripheralManager) -> Unit = { peripheral ->
        if (peripheral.state == CBPeripheralManagerStatePoweredOn && started.load()) {
            addService()
        }
    }

    init {
        PeripheralManagerHolder.manager.addStateListener(stateListener)
        PeripheralManagerHolder.manager.addReadRequestHandler(readHandler)
        PeripheralManagerHolder.manager.addWriteRequestsHandler(writeHandler)
    }

    actual override suspend fun start() {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        withContext(Dispatchers.IO) {
            log.info("BleGattServer starting")
            if (manager.state == CBPeripheralManagerStatePoweredOn) addService()
        }
    }

    actual override suspend fun stop() {
        if (!started.compareAndSet(expectedValue = true, newValue = false)) return
        withContext(Dispatchers.IO) {
            ephemeralValue = byteArrayOf()
            cardValue = byteArrayOf()
            manager.removeAllServices()
            PeripheralManagerHolder.manager.removeStateListener(stateListener)
            PeripheralManagerHolder.manager.removeReadRequestHandler(readHandler)
            PeripheralManagerHolder.manager.removeWriteRequestsHandler(writeHandler)
            log.info("BleGattServer stopped")
        }
    }

    private fun addService() {
        val service = CBMutableService(
            type = CBUUID.UUIDWithString(BleConstants.FREEPATH_SERVICE_UUID.toString()),
            primary = true,
        )
        service.setCharacteristics(listOf(pingChar, ephemeralChar, cardChar, statusChar))
        manager.addService(service)
    }
}
