package io.github.smyrgeorge.freepath.libble

import io.github.smyrgeorge.freepath.libble.BleConstants.FREEPATH_SERVICE_UUID
import io.github.smyrgeorge.freepath.libble.BleConstants.toHex
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerStatePoweredOn
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSArray
import platform.Foundation.arrayWithObject
import kotlin.concurrent.atomics.AtomicBoolean

actual class LibbleAdvertiser actual constructor() {

    private val log = Logger.of(this::class)
    private val advertising = AtomicBoolean(false)
    private val manager get() = PeripheralManagerHolder.manager.manager
    private var currentPsm: Int = 0
    private var currentToken: ByteArray? = null

    private val stateListener: (CBPeripheralManager) -> Unit = { peripheral ->
        if (peripheral.state == CBPeripheralManagerStatePoweredOn && advertising.load()) {
            doAdvertise(peripheral, currentPsm, currentToken)
        }
    }

    actual suspend fun start(psm: Int, identityToken: ByteArray?) {
        if (!advertising.compareAndSet(expectedValue = false, newValue = true)) return
        currentPsm = psm
        currentToken = identityToken
        withContext(Dispatchers.IO) {
            log.info("BleAdvertiser starting (psm=$psm, token=${identityToken != null})")
            PeripheralManagerHolder.manager.addStateListener(stateListener)
            if (manager.state == CBPeripheralManagerStatePoweredOn) doAdvertise(manager, psm, identityToken)
        }
    }

    actual suspend fun stop() {
        if (!advertising.compareAndSet(expectedValue = true, newValue = false)) return
        withContext(Dispatchers.IO) {
            manager.stopAdvertising()
            PeripheralManagerHolder.manager.removeStateListener(stateListener)
            currentPsm = 0
            currentToken = null
            log.info("BleAdvertiser stopped")
        }
    }

    private fun doAdvertise(peripheral: CBPeripheralManager, psm: Int, token: ByteArray?) {
        val serviceUuid = CBUUID.UUIDWithString(FREEPATH_SERVICE_UUID.toString())
        // iOS startAdvertising() only allows ServiceUUIDs and LocalName keys.
        // Encode PSM + optional identity token in the local name.
        // Format: "fp:PPPP" or "fp:PPPP:TTTTTTTTTTTTTTTT" (token as 16 hex chars)
        val psmHex = psm.toString(16).padStart(4, '0')
        val localName = if (token != null) {
            val tokenHex = token.toHex()
            "fp:$psmHex:$tokenHex"
        } else {
            "fp:$psmHex"
        }

        val advertisementData: Map<Any?, *> = mapOf(
            CBAdvertisementDataServiceUUIDsKey to NSArray.arrayWithObject(serviceUuid),
            CBAdvertisementDataLocalNameKey to localName,
        )
        peripheral.startAdvertising(advertisementData)
    }
}
