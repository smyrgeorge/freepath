package io.github.smyrgeorge.freepath.libble

import io.github.smyrgeorge.log4k.Logger
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerStatePoweredOn
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject

/**
 * Manages outbound BLE L2CAP connections on iOS.
 *
 * Runs a background scan for the Freepath service UUID so that discovered peripherals
 * are cached in this CBCentralManager instance. This is necessary because Kable uses
 * its own CBCentralManager for scanning, and `retrievePeripheralsWithIdentifiers` only
 * returns peripherals previously discovered by the **same** CBCentralManager instance.
 *
 * Flow: background scan caches peripherals → connect() → connectPeripheral → openL2CAPChannel → callback
 */
internal class CentralManager : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

    private val log = Logger.of(this::class)
    val manager: CBCentralManager = CBCentralManager(this, null)

    private val lock = NSLock()
    private fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    // peripheralId (UUID string) → (psm, callback)
    private val pending = mutableMapOf<String, Pair<Int, (CBL2CAPChannel?, NSError?) -> Unit>>()

    // Cache of discovered CBPeripheral objects from our background scan.
    // We must hold strong references — CoreBluetooth deallocates peripherals if no one retains them.
    private val discoveredPeripherals = mutableMapOf<String, CBPeripheral>()
    private var scanning = false

    /** Start the background scan. Called once after CBCentralManager reaches PoweredOn state. */
    private fun startBackgroundScan() {
        if (scanning) return
        scanning = true
        val serviceUuid = CBUUID.UUIDWithString(
            BleConstants.FREEPATH_SERVICE_UUID.toString()
        )
        log.debug("CentralManager: starting background scan for Freepath peripherals")
        manager.scanForPeripheralsWithServices(listOf(serviceUuid), null)
    }

    /**
     * Connect to [peripheralId] and open an L2CAP channel on [psm].
     * [onOpen] is called on a CoreBluetooth callback thread with either a channel or an error.
     */
    fun connectAndOpenL2CAP(
        peripheralId: String,
        psm: Int,
        onOpen: (CBL2CAPChannel?, NSError?) -> Unit,
    ) {
        log.info("CentralManager: connectAndOpenL2CAP id=$peripheralId psm=$psm")
        // First check our discovered cache (populated by the background scan).
        val peripheral = locked { discoveredPeripherals[peripheralId] }
            ?: run {
                // Fallback to retrievePeripherals (works for previously connected/bonded devices).
                @Suppress("UNCHECKED_CAST")
                val known = manager.retrievePeripheralsWithIdentifiers(
                    listOf(NSUUID(uUIDString = peripheralId))
                ) as List<CBPeripheral>
                log.debug("CentralManager: retrievePeripherals found ${known.size} peripheral(s)")
                known.firstOrNull()
            }

        if (peripheral == null) {
            log.warn("CentralManager: peripheral $peripheralId not found — not discovered or out of range")
            onOpen(null, null)
            return
        }
        log.info("CentralManager: connecting to $peripheralId (state=${peripheral.state})")
        peripheral.setDelegate(this)
        locked { pending[peripheralId] = Pair(psm, onOpen) }
        manager.connectPeripheral(peripheral, null)
    }

    /** Cancel a pending connection (called when the coroutine is cancelled). */
    fun cancelConnection(peripheralId: String) {
        locked { pending.remove(peripheralId) } ?: return
        log.info("CentralManager: cancelling connection to $peripheralId")
        val peripheral = locked { discoveredPeripherals[peripheralId] }
            ?: run {
                @Suppress("UNCHECKED_CAST")
                val known = manager.retrievePeripheralsWithIdentifiers(
                    listOf(NSUUID(uUIDString = peripheralId))
                ) as List<CBPeripheral>
                known.firstOrNull()
            }
        peripheral?.let { manager.cancelPeripheralConnection(it) }
    }

    // ── CBCentralManagerDelegate ────────────────────────────────────────────

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        log.info("CentralManager: state=${central.state}")
        if (central.state == CBCentralManagerStatePoweredOn) {
            startBackgroundScan()
        }
    }

    @Suppress("LocalVariableName")
    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) {
        val peripheralId = didDiscoverPeripheral.identifier.UUIDString.lowercase()
        locked { discoveredPeripherals[peripheralId] = didDiscoverPeripheral }
        log.debug("CentralManager: discovered $peripheralId (cache size=${locked { discoveredPeripherals.size }})")
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        val peripheralId = didConnectPeripheral.identifier.UUIDString.lowercase()
        log.info("CentralManager: didConnect $peripheralId — opening L2CAP channel")
        val psm = locked { pending[peripheralId]?.first } ?: run {
            log.info("CentralManager: no pending entry for $peripheralId, ignoring")
            return
        }
        didConnectPeripheral.openL2CAPChannel(psm.toUShort())
    }

    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?,
    ) {
        val peripheralId = didFailToConnectPeripheral.identifier.UUIDString.lowercase()
        log.info("CentralManager: didFailToConnect $peripheralId error=$error")
        val (_, onOpen) = locked { pending.remove(peripheralId) } ?: return
        onOpen(null, error)
    }

    // ── CBPeripheralDelegate ────────────────────────────────────────────────

    override fun peripheral(
        peripheral: CBPeripheral,
        didOpenL2CAPChannel: CBL2CAPChannel?,
        error: NSError?,
    ) {
        val peripheralId = peripheral.identifier.UUIDString.lowercase()
        log.info("CentralManager: didOpenL2CAPChannel id=$peripheralId channel=$didOpenL2CAPChannel error=$error")
        val (_, onOpen) = locked { pending.remove(peripheralId) } ?: run {
            log.info("CentralManager: no pending entry for $peripheralId on L2CAP open, ignoring")
            return
        }
        onOpen(didOpenL2CAPChannel, error)
    }
}
