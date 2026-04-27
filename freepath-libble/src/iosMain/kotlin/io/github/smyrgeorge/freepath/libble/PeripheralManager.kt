package io.github.smyrgeorge.freepath.libble

import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.classic.debug
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.darwin.NSObject

internal class PeripheralManager : NSObject(), CBPeripheralManagerDelegateProtocol {

    private val log = Logger.of(this::class)
    val manager: CBPeripheralManager = CBPeripheralManager(this, null)

    private val lock = NSLock()
    private fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private val stateListeners = mutableListOf<(CBPeripheralManager) -> Unit>()
    private val readRequestHandlers = mutableListOf<(CBATTRequest) -> Unit>()
    private val writeRequestsHandlers = mutableListOf<(List<CBATTRequest>) -> Unit>()
    private val l2capPublishHandlers = mutableListOf<(psm: Int, error: NSError?) -> Unit>()
    private val l2capOpenHandlers = mutableListOf<(CBL2CAPChannel?, NSError?) -> Unit>()

    fun addStateListener(listener: (CBPeripheralManager) -> Unit) = locked { stateListeners += listener }
    fun removeStateListener(listener: (CBPeripheralManager) -> Unit) = locked { stateListeners.remove(listener) }

    fun addL2capPublishHandler(handler: (psm: Int, error: NSError?) -> Unit) = locked { l2capPublishHandlers += handler }
    fun removeL2capPublishHandler(handler: (psm: Int, error: NSError?) -> Unit) = locked { l2capPublishHandlers.remove(handler) }

    fun addL2capOpenHandler(handler: (CBL2CAPChannel?, NSError?) -> Unit) = locked { l2capOpenHandlers += handler }
    fun removeL2capOpenHandler(handler: (CBL2CAPChannel?, NSError?) -> Unit) = locked { l2capOpenHandlers.remove(handler) }

    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        log.debug("PeripheralManager: state=${peripheral.state}")
        locked { stateListeners.toList() }.forEach { it(peripheral) }
    }

    override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveReadRequest: CBATTRequest) =
        locked { readRequestHandlers.toList() }.forEach { it(didReceiveReadRequest) }

    override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveWriteRequests: List<*>) {
        @Suppress("UNCHECKED_CAST")
        val requests = didReceiveWriteRequests as List<CBATTRequest>
        locked { writeRequestsHandlers.toList() }.forEach { it(requests) }
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didPublishL2CAPChannel: UShort,
        error: NSError?,
    ) {
        log.debug("PeripheralManager: didPublishL2CAP psm=$didPublishL2CAPChannel error=$error")
        locked { l2capPublishHandlers.toList() }.forEach { it(didPublishL2CAPChannel.toInt(), error) }
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didOpenL2CAPChannel: CBL2CAPChannel?,
        error: NSError?,
    ) {
        log.debug("PeripheralManager: didOpenL2CAP channel=${didOpenL2CAPChannel != null} error=$error")
        locked { l2capOpenHandlers.toList() }.forEach { it(didOpenL2CAPChannel, error) }
    }
}
