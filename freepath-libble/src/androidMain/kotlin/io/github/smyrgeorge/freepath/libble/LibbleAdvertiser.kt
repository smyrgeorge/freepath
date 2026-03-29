package io.github.smyrgeorge.freepath.libble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import io.github.smyrgeorge.freepath.libble.BleConstants.FREEPATH_SERVICE_UUID
import io.github.smyrgeorge.freepath.util.AndroidContextHolder
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
actual class LibbleAdvertiser actual constructor() {

    private val log = Logger.of(this::class)

    private val advertising = AtomicBoolean(false)
    private var advertiser: BluetoothLeAdvertiser? = null

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            log.info("BleAdvertiser started")
        }

        override fun onStartFailure(errorCode: Int) {
            log.error("BleAdvertiser start failed: errorCode=$errorCode")
        }
    }

    actual suspend fun start(bleBeaconId: ByteArray) {
        if (!advertising.compareAndSet(expectedValue = false, newValue = true)) return

        withContext(Dispatchers.IO) {
            val ctx = requireNotNull(AndroidContextHolder.applicationContext) {
                "BleAdvertiser: AndroidContextHolder.applicationContext must be set before start()"
            }

            val bt = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val leAdvertiser = bt.adapter?.bluetoothLeAdvertiser
            if (leAdvertiser == null) {
                log.warn("BleAdvertiser: BluetoothLeAdvertiser not available (BLE advertising unsupported?)")
                advertising.store(false)
                return@withContext
            }
            advertiser = leAdvertiser

            val settings = AdvertiseSettings.Builder().build()
            val serviceUuid = ParcelUuid(UUID.fromString(FREEPATH_SERVICE_UUID.toString()))
            val data = AdvertiseData.Builder()
                .addServiceUuid(serviceUuid)
                .build()
            leAdvertiser.startAdvertising(settings, data, callback)
        }
    }

    actual suspend fun stop() {
        if (!advertising.compareAndSet(expectedValue = true, newValue = false)) return
        withContext(Dispatchers.IO) {
            @SuppressLint("MissingPermission")
            advertiser?.stopAdvertising(callback)
            advertiser = null
            log.info("BleAdvertiser stopped")
        }
    }
}
