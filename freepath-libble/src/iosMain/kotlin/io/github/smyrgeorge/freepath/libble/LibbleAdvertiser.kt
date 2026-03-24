package io.github.smyrgeorge.freepath.libble

import io.github.smyrgeorge.freepath.libble.BleConstants.FREEPATH_SERVICE_UUID
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerStatePoweredOn
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSArray
import platform.Foundation.arrayWithObject
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalAtomicApi::class, ExperimentalUuidApi::class)
actual class LibbleAdvertiser actual constructor() {

    private val log = Logger.of(this::class)
    private val advertising = AtomicBoolean(false)
    private val manager get() = PeripheralManagerHolder.manager.manager

    private val stateListener: (CBPeripheralManager) -> Unit = { peripheral ->
        if (peripheral.state == CBPeripheralManagerStatePoweredOn && advertising.load()) {
            doAdvertise(peripheral)
        }
    }

    init {
        PeripheralManagerHolder.manager.addStateListener(stateListener)
    }

    actual suspend fun start() {
        if (!advertising.compareAndSet(expectedValue = false, newValue = true)) return
        withContext(Dispatchers.IO) {
            log.info("BleAdvertiser starting")
            if (manager.state == CBPeripheralManagerStatePoweredOn) doAdvertise(manager)
        }
    }

    actual suspend fun stop() {
        if (!advertising.compareAndSet(expectedValue = true, newValue = false)) return
        withContext(Dispatchers.IO) {
            manager.stopAdvertising()
            PeripheralManagerHolder.manager.removeStateListener(stateListener)
            log.info("BleAdvertiser stopped")
        }
    }

    private fun doAdvertise(peripheral: CBPeripheralManager) {
        val serviceUuid = CBUUID.UUIDWithString(FREEPATH_SERVICE_UUID.toString())
        val map = mapOf(CBAdvertisementDataServiceUUIDsKey to NSArray.arrayWithObject(serviceUuid))
        @Suppress("UNCHECKED_CAST")
        peripheral.startAdvertising(map as Map<Any?, *>)
    }
}
