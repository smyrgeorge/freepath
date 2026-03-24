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

    private val _receivedCards = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    actual override val receivedCards: SharedFlow<ByteArray> = _receivedCards

    private val started = AtomicBoolean(false)

    @Volatile
    private var localCardBytes: ByteArray = byteArrayOf()
    private val manager: CBPeripheralManager get() = PeripheralManagerHolder.manager.manager

    private val cardReadChar = CBMutableCharacteristic(
        type = CBUUID.UUIDWithString(BleConstants.CARD_READ_UUID.toString()),
        properties = CBCharacteristicPropertyRead,
        value = null,
        permissions = CBAttributePermissionsReadable,
    )

    private val cardWriteChar = CBMutableCharacteristic(
        type = CBUUID.UUIDWithString(BleConstants.CARD_WRITE_UUID.toString()),
        properties = CBCharacteristicPropertyWrite,
        value = null,
        permissions = CBAttributePermissionsWriteable,
    )

    private val readHandler: (CBATTRequest) -> Unit = readHandler@{ request ->
        if (!started.load()) return@readHandler
        if (request.characteristic.UUID == cardReadChar.UUID) {
            val data = localCardBytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), localCardBytes.size.toULong())
            }
            request.value = data
            manager.respondToRequest(request, CBATTErrorSuccess)
        } else {
            manager.respondToRequest(request, CBATTErrorRequestNotSupported)
        }
    }

    private val writeHandler: (List<CBATTRequest>) -> Unit = writeHandler@{ requests ->
        if (!started.load()) return@writeHandler
        requests.forEach { req ->
            if (req.characteristic.UUID == cardWriteChar.UUID) {
                req.value?.let { nsData ->
                    val bytes = nsData.bytes?.readBytes(nsData.length.toInt()) ?: byteArrayOf()
                    _receivedCards.tryEmit(bytes)
                }
                manager.respondToRequest(req, CBATTErrorSuccess)
            } else {
                manager.respondToRequest(req, CBATTErrorRequestNotSupported)
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

    actual override suspend fun start(localCardBytes: ByteArray) {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        this.localCardBytes = localCardBytes
        withContext(Dispatchers.IO) {
            log.info("BleGattServer starting")
            if (manager.state == CBPeripheralManagerStatePoweredOn) addService()
        }
    }

    actual override suspend fun stop() {
        if (!started.compareAndSet(expectedValue = true, newValue = false)) return
        withContext(Dispatchers.IO) {
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
        service.setCharacteristics(listOf(cardReadChar, cardWriteChar))
        manager.addService(service)
    }
}