package io.github.smyrgeorge.freepath.libble

import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.Foundation.NSLock
import platform.darwin.NSObject

internal class PeripheralManager : NSObject(), CBPeripheralManagerDelegateProtocol {

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

    fun addStateListener(listener: (CBPeripheralManager) -> Unit) = locked { stateListeners += listener }
    fun removeStateListener(listener: (CBPeripheralManager) -> Unit) = locked { stateListeners.remove(listener) }

    fun addReadRequestHandler(handler: (CBATTRequest) -> Unit) = locked { readRequestHandlers += handler }
    fun removeReadRequestHandler(handler: (CBATTRequest) -> Unit) = locked { readRequestHandlers.remove(handler) }

    fun addWriteRequestsHandler(handler: (List<CBATTRequest>) -> Unit) = locked { writeRequestsHandlers += handler }
    fun removeWriteRequestsHandler(handler: (List<CBATTRequest>) -> Unit) =
        locked { writeRequestsHandlers.remove(handler) }

    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) =
        locked { stateListeners.toList() }.forEach { it(peripheral) }

    override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveReadRequest: CBATTRequest) =
        locked { readRequestHandlers.toList() }.forEach { it(didReceiveReadRequest) }

    override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveWriteRequests: List<*>) {
        @Suppress("UNCHECKED_CAST")
        val requests = didReceiveWriteRequests as List<CBATTRequest>
        locked { writeRequestsHandlers.toList() }.forEach { it(requests) }
    }
}
