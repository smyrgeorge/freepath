package io.github.smyrgeorge.freepath.libble

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.libble.BleConstants.FREEPATH_SERVICE_UUID
import io.github.smyrgeorge.freepath.libble.exchange.SecureExchangeInitiator
import io.github.smyrgeorge.freepath.libble.exchange.SecureExchangeResponder
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
    internal val gattServer: BleGattServer = BleGattServer()

    private val expiryJob: Job
    private lateinit var pingJob: Job
    private val pingSemaphore = Semaphore(64)

    private val peripherals = mutableMapOf<String, PeripheralEntry>()
    private val peripheralsMutex = Mutex()

    private val sessionActive = AtomicBoolean(false)

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

    suspend fun start() {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        log.info("LibbleModule starting")
        advertiser.start()
        startGattServer()
        pingJob = scope.launch { pingLoop() }
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

    fun setEventHandler(handler: suspend (LibbleEvent) -> Unit): LibbleModule {
        this.handler = handler
        return this
    }

    suspend fun stop() {
        if (!started.compareAndSet(expectedValue = true, newValue = false)) return
        log.info("LibbleModule stopping")
        pingJob.cancel()
        stopGattServer()
        metrics.close()
        advertiser.stop()
        expiryJob.cancel()
        scope.cancel()
    }

    /**
     * Runs the initiator side of the secure exchange with [peripheralId].
     * Emits [LibbleEvent.ContactExchange] events via the registered event handler.
     * Returns [Result.failure] if a session is already in progress.
     */
    suspend fun beginInitiatorExchange(
        peripheralId: String,
        pin: String,
        localCard: ContactCard,
        sigKeyPrivate: ByteArray,
    ): Result<ContactCard> {
        if (!sessionActive.compareAndSet(expectedValue = false, newValue = true))
            return Result.failure(IllegalStateException("Exchange session already in progress"))
        return try {
            // Wait one ping interval so any in-flight ping coroutines that passed the
            // sessionActive check can finish their connect/disconnect cycle before we start.
            delay(PING_INTERVAL)
            val conn = connection(peripheralId)
            SecureExchangeInitiator(conn, pin).run(localCard, sigKeyPrivate) { event ->
                sendEvent(event)
            }
        } finally {
            sessionActive.store(false)
        }
    }

    /**
     * Runs the responder side of the secure exchange.
     * The GATT server must be started before calling this.
     * Emits [LibbleEvent.ContactExchange] events via the registered event handler.
     * Returns [Result.failure] if a session is already in progress.
     */
    suspend fun beginResponderExchange(
        pin: String,
        localCard: ContactCard,
        sigKeyPrivate: ByteArray,
    ): Result<ContactCard> {
        if (!sessionActive.compareAndSet(expectedValue = false, newValue = true))
            return Result.failure(IllegalStateException("Exchange session already in progress"))
        return try {
            SecureExchangeResponder(gattServer, pin).run(localCard, sigKeyPrivate) { event ->
                sendEvent(event)
            }
        } finally {
            sessionActive.store(false)
        }
    }

    private suspend fun connection(peripheralId: String): BleConnection {
        val entry = peripheralsMutex.withLock { peripherals[peripheralId] }
            ?: error("Unknown peripheralId: $peripheralId — not in discovery cache")
        return BleConnection(Peripheral(entry.advertisement))
    }

    private suspend fun startGattServer() {
        if (!gattServerStarted.compareAndSet(expectedValue = false, newValue = true)) return
        gattServer.start()
        log.info("BLE GATT server started")
    }

    private suspend fun stopGattServer() {
        if (!gattServerStarted.compareAndSet(expectedValue = true, newValue = false)) return
        gattServer.stop()
        log.info("BLE GATT server stopped")
    }

    private suspend fun pingLoop() {
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

    private suspend fun ping(peripheralId: String): Boolean {
        // Never ping during an active exchange — connecting/disconnecting a Kable Peripheral
        // for the same physical device kills the exchange connection.
        if (sessionActive.load()) return false
        return runCatching {
            withTimeoutOrNull(PING_TIMEOUT) {
                val conn = connection(peripheralId)
                try {
                    conn.connect()
                    conn.ping()
                    true
                } finally {
                    // Don't disconnect if an exchange session became active while we were pinging:
                    // calling disconnect here would tear down the exchange's underlying BLE connection.
                    if (!sessionActive.load()) {
                        withContext(NonCancellable) { conn.disconnect() }
                    }
                }
            } ?: false
        }.onFailure {
            log.debug("Ping failed for $peripheralId: $it")
        }.getOrDefault(false)
    }

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
