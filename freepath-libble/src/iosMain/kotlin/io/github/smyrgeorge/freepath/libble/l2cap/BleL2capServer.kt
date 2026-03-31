package io.github.smyrgeorge.freepath.libble.l2cap

import io.github.smyrgeorge.freepath.libble.CentralManagerHolder
import io.github.smyrgeorge.freepath.libble.PeripheralManagerHolder
import io.github.smyrgeorge.freepath.libble.pool.BleL2capChannel
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerStatePoweredOn
import platform.Foundation.NSError
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual class BleL2capServer actual constructor() {

    private val log = Logger.of(this::class)
    private val manager get() = PeripheralManagerHolder.manager

    private var _psm: Int = 0
    actual val psm: Int get() = _psm

    private val _incoming = MutableSharedFlow<BleL2capChannel>(replay = 0, extraBufferCapacity = 64)
    actual val incoming: Flow<BleL2capChannel> = _incoming

    private val openHandler: (CBL2CAPChannel?, NSError?) -> Unit = { channel, error ->
        if (error != null) {
            log.warn("L2CAP channel open error: $error")
        } else if (channel != null) {
            val peripheralId = channel.peer?.identifier?.UUIDString
            if (peripheralId != null) {
                val bleChannel = BleL2capChannel(peripheralId, channel)
                _incoming.tryEmit(bleChannel)
            } else {
                log.warn("L2CAP channel opened with null peer — dropping")
            }
        }
    }

    actual suspend fun start() {
        // Initialize the CentralManager early so its background scan starts discovering
        // peripherals before any outbound L2CAP connect is attempted.
        CentralManagerHolder.ensureInitialized()
        manager.addL2capOpenHandler(openHandler)
        runCatching {
            val assignedPsm = suspendCancellableCoroutine { cont ->
                var publishHandler: ((Int, NSError?) -> Unit)? = null
                var stateListener: ((CBPeripheralManager) -> Unit)? = null

                publishHandler = { psmValue, error ->
                    manager.removeL2capPublishHandler(publishHandler!!)
                    if (error != null) {
                        cont.resumeWithException(IllegalStateException("Failed to publish L2CAP channel: $error"))
                    } else {
                        cont.resume(psmValue)
                    }
                }
                manager.addL2capPublishHandler(publishHandler)

                cont.invokeOnCancellation {
                    manager.removeL2capPublishHandler(publishHandler)
                    stateListener?.let { manager.removeStateListener(it) }
                }

                fun doPublish() {
                    manager.manager.publishL2CAPChannelWithEncryption(false)
                }

                if (manager.manager.state == CBPeripheralManagerStatePoweredOn) {
                    doPublish()
                } else {
                    log.debug("CBPeripheralManager not yet powered on — waiting")
                    stateListener = { peripheral ->
                        if (peripheral.state == CBPeripheralManagerStatePoweredOn) {
                            manager.removeStateListener(stateListener!!)
                            stateListener = null
                            doPublish()
                        }
                    }
                    manager.addStateListener(stateListener!!)
                }
            }
            _psm = assignedPsm
            log.info("BleL2capServer started on psm=$_psm")
        }.onFailure { e ->
            manager.removeL2capOpenHandler(openHandler)
            throw e
        }
    }

    actual suspend fun stop() {
        manager.removeL2capOpenHandler(openHandler)
        manager.manager.unpublishL2CAPChannel(_psm.toUShort())
        _psm = 0
        log.info("BleL2capServer stopped")
    }
}
