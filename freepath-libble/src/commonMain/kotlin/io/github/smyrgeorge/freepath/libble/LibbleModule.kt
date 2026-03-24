package io.github.smyrgeorge.freepath.libble

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import io.github.smyrgeorge.freepath.libble.BleConstants.FREEPATH_SERVICE_UUID
import io.github.smyrgeorge.freepath.libble.exchange.BleContactExchangeSession
import io.github.smyrgeorge.freepath.libble.gatt.BleConnection
import io.github.smyrgeorge.freepath.libble.gatt.BleGattServer
import io.github.smyrgeorge.freepath.libble.metrics.LibbleMetrics
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.impl.extensions.doEvery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalAtomicApi::class, ExperimentalUuidApi::class)
class LibbleModule {

    private val log = Logger.of(this::class)

    val metrics: LibbleMetrics = LibbleMetrics()

    private val started = AtomicBoolean(false)
    private val gattServerStarted = AtomicBoolean(false)
    private var gattServerCollector: kotlinx.coroutines.Job? = null
    private val advertiser: LibbleAdvertiser = LibbleAdvertiser()
    internal val gattServer: BleGattServer = BleGattServer()

    @Volatile
    private var handler: (suspend (LibbleEvent) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Job returned by doEvery (runs on its own EmptyScope); cancelled explicitly in stop().
    private val expiryJob: kotlinx.coroutines.Job

    private data class PeripheralEntry(
        val event: LibbleEvent.PeripheralDiscovered,
        val advertisement: Advertisement,
    )

    private val peripherals = mutableMapOf<String, PeripheralEntry>()
    private val peripheralsMutex = Mutex()

    init {
        expiryJob = doEvery(1.seconds) {
            val now = Clock.System.now()
            val expired = mutableListOf<String>()
            peripheralsMutex.withLock {
                peripherals
                    .filter { (_, e) -> now - e.event.discoveredAt > 5.seconds }
                    .keys
                    .forEach { id ->
                        peripherals.remove(id)
                        expired += id
                    }
            }
            expired.forEach { id ->
                sendEvent(LibbleEvent.PeripheralExpired(id))
                log.debug("Removed expired peripheral: $id")
            }
        }
    }

    fun setEventHandler(handler: suspend (LibbleEvent) -> Unit): LibbleModule {
        this.handler = handler
        return this
    }

    suspend fun start() {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        log.info("LibbleModule starting")
        advertiser.start()
        scope.launch {
            val scanner = Scanner {
                filters {
                    match {
                        services = listOf(FREEPATH_SERVICE_UUID)
                    }
                }
            }

            scanner.advertisements.collect { advertisement ->
                val event = LibbleEvent.PeripheralDiscovered(
                    discoveredAt = Clock.System.now(),
                    peripheralId = advertisement.identifier.toString(),
                    peripheralName = advertisement.peripheralName,
                    name = advertisement.name,
                    rssi = advertisement.rssi,
                    txPower = advertisement.txPower,
                    isConnectable = advertisement.isConnectable,
                )
                peripheralsMutex.withLock {
                    peripherals[event.peripheralId] = PeripheralEntry(event, advertisement)
                }
                sendEvent(event)
            }
        }
    }

    /**
     * Starts the GATT server hosting [localCardBytes] on the CARD_READ characteristic.
     * Each received peer card is forwarded as a [LibbleEvent.ContactCardReceived] event.
     * Idempotent: calling more than once is a no-op until [stopGattServer] is called.
     */
    suspend fun startGattServer(localCardBytes: ByteArray) {
        if (!gattServerStarted.compareAndSet(expectedValue = false, newValue = true)) return
        gattServer.start(localCardBytes)
        gattServerCollector = scope.launch {
            gattServer.receivedCards.collect { bytes ->
                sendEvent(LibbleEvent.ContactCardReceived(bytes))
            }
        }
    }

    /** Stops the GATT server. Call when the exchange screen is closed. */
    suspend fun stopGattServer() {
        if (!gattServerStarted.compareAndSet(expectedValue = true, newValue = false)) return
        gattServerCollector?.cancel()
        gattServerCollector = null
        gattServer.stop()
    }

    /**
     * Returns a [io.github.smyrgeorge.freepath.libble.gatt.BleConnection] to [peripheralId], ready to [io.github.smyrgeorge.freepath.libble.gatt.BleConnection.connect].
     * The peripheral must be present in the current discovery cache.
     */
    suspend fun connect(peripheralId: String): BleConnection {
        val entry = peripheralsMutex.withLock { peripherals[peripheralId] }
            ?: error("Unknown peripheralId: $peripheralId — not in discovery cache")
        return BleConnection(Peripheral(entry.advertisement))
    }

    /**
     * Creates an initiator session that will connect to [peripheralId].
     * Call [io.github.smyrgeorge.freepath.libble.exchange.BleContactExchangeSession.send] first to connect and push your card,
     * then [io.github.smyrgeorge.freepath.libble.exchange.BleContactExchangeSession.receive] to read the peer's card.
     */
    suspend fun initSession(peripheralId: String, pin: String): BleContactExchangeSession =
        BleContactExchangeSession(connect(peripheralId), pin)

    suspend fun stop() {
        if (!started.compareAndSet(expectedValue = true, newValue = false)) return
        log.info("LibbleModule stopping")
        stopGattServer()
        metrics.close()
        advertiser.stop()
        expiryJob.cancel()
        scope.cancel()
    }

    private suspend fun sendEvent(event: LibbleEvent) {
        metrics.onEvent(event)
        handler?.let { h -> runCatching { h(event) } }
    }
}
