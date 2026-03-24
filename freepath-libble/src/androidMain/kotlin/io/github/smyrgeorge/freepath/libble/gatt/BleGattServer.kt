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
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalAtomicApi::class, ExperimentalUuidApi::class)
actual class BleGattServer actual constructor() : BleGattServerPort {

    private val log = Logger.of(this::class)

    private val _receivedCards = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    actual override val receivedCards: SharedFlow<ByteArray> = _receivedCards

    private val started = AtomicBoolean(false)

    @Volatile
    private var localCardBytes: ByteArray = byteArrayOf()
    private var server: BluetoothGattServer? = null

    private val preparedWriteBuffers = ConcurrentHashMap<String, ByteArray>()

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == CARD_READ_JVM_UUID) {
                val slice = localCardBytes.let {
                    if (offset < it.size) it.copyOfRange(offset, it.size) else byteArrayOf()
                }
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
            if (characteristic.uuid != CARD_WRITE_JVM_UUID) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            if (value == null) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                return
            }
            if (preparedWrite) {
                // Accumulate chunk at offset
                val existing = preparedWriteBuffers[device.address] ?: byteArrayOf()
                val grown = if (offset <= existing.size) {
                    existing.copyOf(offset + value.size).also { System.arraycopy(value, 0, it, offset, value.size) }
                } else {
                    existing + value
                }
                preparedWriteBuffers[device.address] = grown
            } else {
                // Direct (short) write — emit immediately
                _receivedCards.tryEmit(value)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            if (execute) {
                preparedWriteBuffers.remove(device.address)?.let { _receivedCards.tryEmit(it) }
            } else {
                preparedWriteBuffers.remove(device.address)
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    @SuppressLint("MissingPermission")
    actual override suspend fun start(localCardBytes: ByteArray) {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        this.localCardBytes = localCardBytes
        withContext(Dispatchers.IO) {
            val ctx = requireNotNull(AndroidContextHolder.applicationContext) {
                "BleGattServer: AndroidContextHolder.applicationContext must be set"
            }
            val bt = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val gattServer = bt.openGattServer(ctx, callback)

            val service = BluetoothGattService(
                SERVICE_JVM_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY,
            )
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    CARD_READ_JVM_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                )
            )
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    CARD_WRITE_JVM_UUID,
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
            preparedWriteBuffers.clear()
            s?.close()
            log.info("BleGattServer stopped")
        }
    }

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        private val CARD_READ_JVM_UUID = UUID.fromString(BleConstants.CARD_READ_UUID.toString())

        @OptIn(ExperimentalUuidApi::class)
        private val CARD_WRITE_JVM_UUID = UUID.fromString(BleConstants.CARD_WRITE_UUID.toString())

        @OptIn(ExperimentalUuidApi::class)
        private val SERVICE_JVM_UUID = UUID.fromString(BleConstants.FREEPATH_SERVICE_UUID.toString())
    }
}