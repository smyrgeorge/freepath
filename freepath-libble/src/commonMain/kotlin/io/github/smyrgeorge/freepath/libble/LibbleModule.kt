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
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalAtomicApi::class, ExperimentalUuidApi::class)
class LibbleModule {

    private val log = Logger.of(this::class)

    val metrics: LibbleMetrics = LibbleMetrics()

    private val started = AtomicBoolean(false)

    @Volatile
    private var handler: (suspend (LibbleEvent) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val advertiser: LibbleAdvertiser = LibbleAdvertiser()

    private val gattServerStarted = AtomicBoolean(false)
    private var gattServerCollector: Job? = null
    internal val gattServer: BleGattServer = BleGattServer()

    private val expiryJob: Job
    private val pingSemaphore = Semaphore(64)

    private val peripherals = mutableMapOf<String, PeripheralEntry>()
    private val peripheralsMutex = Mutex()

    init {
        expiryJob = doEvery(1.seconds) {
            val now = Clock.System.now()
            val expired = mutableListOf<String>()
            val disconnected = mutableListOf<String>()

            peripheralsMutex.withLock {
                peripherals
                    .filter { (_, e) -> now - e.event.discoveredAt > ADVERTISMENT_EXPIRE_THRESHOLD }
                    .forEach { (id, entry) ->
                        peripherals.remove(id)
                        expired += id
                        disconnected += id
                    }

                peripherals
                    .filter { (_, e) -> now - e.pingedAt > PING_DISCONNECT_THRESHOLD }
                    .forEach { (id, entry) ->
                        peripherals[id] = entry.copy(connected = false)
                        disconnected += id
                    }
            }

            disconnected.forEach { id ->
                sendEvent(LibbleEvent.PeripheralDisconnected(id))
                log.debug("Peripheral disconnected (ping timeout): $id")
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
        scope.launch { runPingLoop() }
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
                sendEvent(event)
                peripheralsMutex.withLock {
                    val prev = peripherals[event.peripheralId]
                    peripherals[event.peripheralId] = PeripheralEntry(
                        event = event,
                        advertisement = advertisement,
                        pingedAt = prev?.pingedAt ?: Instant.DISTANT_PAST,
                        connected = prev?.connected ?: false,
                    )
                }
            }
        }
    }

    suspend fun startGattServer(localCardBytes: ByteArray) {
        if (!gattServerStarted.compareAndSet(expectedValue = false, newValue = true)) return
        gattServer.start(localCardBytes)
        gattServerCollector = scope.launch {
            gattServer.receivedCards.collect { bytes ->
                sendEvent(LibbleEvent.ContactCardReceived(bytes))
            }
        }
    }

    suspend fun stopGattServer() {
        if (!gattServerStarted.compareAndSet(expectedValue = true, newValue = false)) return
        gattServerCollector?.cancel()
        gattServerCollector = null
        gattServer.stop()
    }

    suspend fun connection(peripheralId: String): BleConnection {
        val entry = peripheralsMutex.withLock { peripherals[peripheralId] }
            ?: error("Unknown peripheralId: $peripheralId — not in discovery cache")
        return BleConnection(Peripheral(entry.advertisement))
    }

    suspend fun initSession(peripheralId: String, pin: String): BleContactExchangeSession {
        val session = BleContactExchangeSession(connection(peripheralId), pin)
        peripheralsMutex.withLock {
            val entry = peripherals[peripheralId] ?: return@withLock
            peripherals[peripheralId] = entry.copy(
                pingedAt = Clock.System.now(),
                connected = true,
            )
        }
        return session
    }

    suspend fun stop() {
        if (!started.compareAndSet(expectedValue = true, newValue = false)) return
        log.info("LibbleModule stopping")
        stopGattServer()
        metrics.close()
        advertiser.stop()
        expiryJob.cancel()
        scope.cancel()
    }

    private suspend fun runPingLoop() {
        while (true) {
            peripheralsMutex.withLock { peripherals.keys.toList() }.forEach { id ->
                scope.launch {
                    pingSemaphore.withPermit {
                        val success = ping(id)
                        if (success) {
                            var wasConnected = false
                            peripheralsMutex.withLock {
                                val entry = peripherals[id] ?: return@withLock
                                wasConnected = entry.connected
                                peripherals[id] = entry.copy(
                                    pingedAt = Clock.System.now(),
                                    connected = true,
                                )
                            }
                            if (!wasConnected) {
                                sendEvent(LibbleEvent.PeripheralConnected(id))
                                log.debug("Peripheral connected (ping OK): $id")
                            }
                        }
                    }
                }
            }
            delay(PING_INTERVAL)
        }
    }

    private suspend fun ping(peripheralId: String): Boolean = runCatching {
        withTimeoutOrNull(PING_TIMEOUT) {
            val conn = connection(peripheralId)
            try {
                conn.connect()
                conn.ping()
                true
            } finally {
                withContext(NonCancellable) { conn.disconnect() }
            }
        } ?: false
    }.onFailure {
        log.debug("Ping failed for $peripheralId: $it")
    }.getOrDefault(false)

    private suspend fun sendEvent(event: LibbleEvent) {
        metrics.onEvent(event)
        handler?.let { h -> runCatching { h(event) } }
    }

    private data class PeripheralEntry(
        val event: LibbleEvent.PeripheralDiscovered,
        val advertisement: Advertisement,
        val pingedAt: Instant = Instant.DISTANT_PAST,
        val connected: Boolean = false,
    )

    companion object {
        private val ADVERTISMENT_EXPIRE_THRESHOLD = 3.seconds
        private val PING_DISCONNECT_THRESHOLD = 3.seconds
        private val PING_INTERVAL = 1.seconds
        private val PING_TIMEOUT = 3.seconds
    }
}
