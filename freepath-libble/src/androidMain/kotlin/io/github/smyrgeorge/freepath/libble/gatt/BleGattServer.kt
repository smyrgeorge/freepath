package io.github.smyrgeorge.freepath.libble.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.smyrgeorge.freepath.libble.BleConstants
import io.github.smyrgeorge.freepath.util.AndroidContextHolder
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalAtomicApi::class, ExperimentalUuidApi::class)
actual class BleGattServer actual constructor() : BleGattServerPort {

    private val log = Logger.of(this::class)

    private val _events = MutableSharedFlow<BleGattServerPort.Event>(extraBufferCapacity = 8)
    actual override val events: SharedFlow<BleGattServerPort.Event> = _events

    private val started = AtomicBoolean(false)

    @Volatile
    private var ephemeralValue: ByteArray = byteArrayOf()

    @Volatile
    private var cardValue: ByteArray = byteArrayOf()

    private var server: BluetoothGattServer? = null
    private val preparedWriteBuffers = ConcurrentHashMap<String, ByteArray>()
    private val preparedWriteChars = ConcurrentHashMap<String, UUID>()

    actual override suspend fun setEphemeralValue(bytes: ByteArray) {
        ephemeralValue = bytes
    }

    actual override suspend fun setCardValue(bytes: ByteArray) {
        cardValue = bytes
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val data = when (characteristic.uuid) {
                EPHEMERAL_JVM_UUID -> ephemeralValue
                CARD_JVM_UUID -> cardValue
                else -> null
            }
            if (data != null) {
                val slice = if (offset < data.size) data.copyOfRange(offset, data.size) else byteArrayOf()
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            when (characteristic.uuid) {
                PING_JVM_UUID -> {
                    if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    return
                }

                EPHEMERAL_JVM_UUID, CARD_JVM_UUID, STATUS_JVM_UUID -> {
                    if (value == null) {
                        if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        return
                    }
                    if (preparedWrite) {
                        val key = device.address
                        val existing = preparedWriteBuffers[key] ?: byteArrayOf()
                        val grown = if (offset <= existing.size) {
                            existing.copyOf(offset + value.size)
                                .also { System.arraycopy(value, 0, it, offset, value.size) }
                        } else {
                            existing + value
                        }
                        preparedWriteBuffers[key] = grown
                        preparedWriteChars[key] = characteristic.uuid
                    } else {
                        emitWrite(characteristic.uuid, value)
                    }
                    if (responseNeeded) server?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        null
                    )
                }

                else -> {
                    if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            if (execute) {
                val key = device.address
                val bytes = preparedWriteBuffers.remove(key)
                val uuid = preparedWriteChars.remove(key)
                if (bytes != null && uuid != null) emitWrite(uuid, bytes)
            } else {
                preparedWriteBuffers.remove(device.address)
                preparedWriteChars.remove(device.address)
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    private fun emitWrite(uuid: UUID, bytes: ByteArray) {
        val event = when (uuid) {
            EPHEMERAL_JVM_UUID -> BleGattServerPort.Event.EphemeralReceived(bytes)
            CARD_JVM_UUID -> BleGattServerPort.Event.CardReceived(bytes)
            STATUS_JVM_UUID -> BleGattServerPort.Event.StatusReceived(bytes.firstOrNull() ?: 0x02)
            else -> return
        }
        _events.tryEmit(event)
    }

    @SuppressLint("MissingPermission")
    actual override suspend fun start() {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        withContext(Dispatchers.IO) {
            val ctx = requireNotNull(AndroidContextHolder.applicationContext) {
                "BleGattServer: AndroidContextHolder.applicationContext must be set"
            }
            val bt = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val gattServer = bt.openGattServer(ctx, callback)

            val service = BluetoothGattService(SERVICE_JVM_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    PING_JVM_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                )
            )
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    EPHEMERAL_JVM_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
                )
            )
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    CARD_JVM_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
                )
            )
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    STATUS_JVM_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                )
            )
            gattServer.addService(service)
            server = gattServer
            log.info("BleGattServer started")
        }
    }

    @SuppressLint("MissingPermission")
    actual override suspend fun stop() {
        if (!started.compareAndSet(expectedValue = true, newValue = false)) return
        withContext(Dispatchers.IO) {
            val s = server
            server = null
            ephemeralValue = byteArrayOf()
            cardValue = byteArrayOf()
            preparedWriteBuffers.clear()
            preparedWriteChars.clear()
            s?.close()
            log.info("BleGattServer stopped")
        }
    }

    companion object {
        private val SERVICE_JVM_UUID = UUID.fromString(BleConstants.FREEPATH_SERVICE_UUID.toString())
        private val PING_JVM_UUID = UUID.fromString(BleConstants.PING_UUID.toString())
        private val EPHEMERAL_JVM_UUID = UUID.fromString(BleConstants.EPHEMERAL_UUID.toString())
        private val CARD_JVM_UUID = UUID.fromString(BleConstants.CARD_UUID.toString())
        private val STATUS_JVM_UUID = UUID.fromString(BleConstants.STATUS_UUID.toString())
    }
}
