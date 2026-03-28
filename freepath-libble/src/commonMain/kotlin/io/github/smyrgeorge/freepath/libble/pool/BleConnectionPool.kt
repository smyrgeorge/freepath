package io.github.smyrgeorge.freepath.libble.pool

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import io.github.smyrgeorge.freepath.libble.pool.BleConnectionPool.Companion.IDLE_TIMEOUT
import io.github.smyrgeorge.freepath.libble.pool.BleConnectionPool.Companion.KEEPALIVE_INTERVAL
import io.github.smyrgeorge.freepath.libble.pool.BleConnectionPool.Companion.MAX_RECONNECT_ATTEMPTS
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi

/**
 * Manages persistent BLE connections for peripherals involved in active data exchanges.
 *
 * A peripheral enters the pool the first time [withConnection] is called for it.
 * A keepalive coroutine (one per entry) pings the connection every [KEEPALIVE_INTERVAL].
 * If idle for [IDLE_TIMEOUT] the entry is evicted silently.
 * On ping failure the pool retries up to [MAX_RECONNECT_ATTEMPTS] times with exponential
 * backoff before evicting silently.
 *
 * All events (PeripheralDiscovered, PeripheralExpired) remain owned by the scanner in
 * LibbleModule. Pool eviction emits no events.
 *
 * Concurrent [withConnection] calls for the same peripheral are serialized via a per-entry
 * [Mutex]. Callers for different peripherals run concurrently.
 *
 * TODO: [withConnection] currently exposes the full [BleConnectionPort] (including lifecycle
 * methods connect/disconnect/ping). Consider a narrower BleChannel interface (data ops only)
 * for the block parameter in the future.
 */
internal class BleConnectionPool(
    private val scope: CoroutineScope,
    private val advertisementLookup: suspend (peripheralId: String) -> Advertisement?,
    // Called whenever a response notification arrives from a pooled connection.
    private val onResponseReceived: (peripheralId: String, bytes: ByteArray) -> Unit = { _, _ -> },
    // Injected for testability; defaults to creating a real Kable BleConnection.
    private val connectionFactory: (Advertisement) -> BleConnectionPort = { adv -> BleConnection(Peripheral(adv)) },
) {
    private val log = Logger.of(this::class)

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, PoolEntry>()

    private class PoolEntry(
        var connection: BleConnectionPort,
        val advertisement: Advertisement,
        var lastDataAt: Instant = Instant.DISTANT_PAST,
        var keepaliveJob: Job? = null,
        var responseCollectorJob: Job? = null,
    ) {
        private val mutex: Mutex = Mutex()
        suspend inline fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
    }

    /**
     * Execute [block] with a live connection to [peripheralId].
     *
     * If the peripheral is not yet in the pool, resolves its Advertisement via
     * [advertisementLookup], creates a BleConnectionPort, connects, and starts the
     * keepalive coroutine — all before running [block].
     *
     * Acquires a per-entry [Mutex] for the duration of [block] so concurrent callers for
     * the same peripheral are serialized. Callers for different peripherals run concurrently.
     *
     * Throws [IllegalStateException] if the peripheral is unknown or was evicted while
     * waiting for the mutex.
     */
    suspend fun <T> withConnection(peripheralId: String, block: suspend (BleConnectionPort) -> T): T {
        val entry = getOrCreate(peripheralId)
        return entry.withLock {
            // Re-check: the entry may have been evicted while we waited for the mutex.
            check(mutex.withLock { entries[peripheralId] } != null) {
                "Peripheral $peripheralId evicted from pool"
            }
            val result = block(entry.connection)
            mutex.withLock { entries[peripheralId]?.lastDataAt = Clock.System.now() }
            result
        }
    }

    /** Close all pool entries. Called from LibbleModule.stop(). */
    suspend fun close() {
        mutex.withLock { entries.keys.toList() }.forEach { remove(it) }
    }

    /**
     * Remove a peripheral from the pool: cancel its keepalive and response-collector coroutines,
     * then disconnect. Idempotent — safe to call if the peripheral is not in the pool.
     * Called by LibbleModule when an advertisement expires or [close] is called.
     */
    suspend fun remove(peripheralId: String) {
        val entry = mutex.withLock { entries.remove(peripheralId) } ?: return
        entry.keepaliveJob?.cancel()
        entry.responseCollectorJob?.cancel()
        withContext(NonCancellable) { runCatching { entry.connection.disconnect() } }
        log.debug("Pool: removed $peripheralId")
    }

    /**
     * Return an existing pool entry, or create one if none exists.
     *
     * The BLE connect() call happens outside [mutex] to avoid blocking the map
     * for the duration of a slow BLE operation. A double-check with getOrPut handles
     * the rare race where two coroutines both reach this point for the same peripheral:
     * the winner's entry is kept and the loser's connection is immediately disconnected.
     */
    private suspend fun getOrCreate(peripheralId: String): PoolEntry {
        mutex.withLock { entries[peripheralId] }?.let { return it }

        val adv = advertisementLookup(peripheralId)
            ?: throw IllegalStateException("Unknown peripheral: $peripheralId — not in discovery cache")

        val conn = connectionFactory(adv)
        try {
            conn.connect()
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { conn.disconnect() } }
            throw e
        }
        val newEntry = PoolEntry(connection = conn, advertisement = adv)

        // Insert under lock. If another coroutine beat us, discard our connection.
        val winner = mutex.withLock { entries.getOrPut(peripheralId) { newEntry } }
        if (winner !== newEntry) {
            withContext(NonCancellable) { runCatching { conn.disconnect() } }
            return winner
        }

        newEntry.keepaliveJob = scope.launch { keepaliveLoop(peripheralId) }
        newEntry.responseCollectorJob = scope.launch { collectResponses(peripheralId, conn) }
        log.debug("Pool: enrolled $peripheralId")
        return newEntry
    }

    /**
     * Keepalive loop for a single pool entry.
     *
     * Runs until either:
     * - The entry is removed externally (remove() cancels keepaliveJob).
     * - The entry is idle for [IDLE_TIMEOUT] (silent eviction).
     * - Reconnect attempts are exhausted (silent eviction).
     *
     * Holds PoolEntry.mutex while pinging and reconnecting so [withConnection] callers
     * for the same peripheral suspend during reconnect rather than racing on a broken connection.
     *
     * Map removal happens inside PoolEntry.mutex so that any [withConnection] caller
     * that was waiting on the mutex will see the entry is gone after acquiring the lock.
     */
    private suspend fun keepaliveLoop(peripheralId: String) {
        while (true) {
            delay(KEEPALIVE_INTERVAL)
            val entry = mutex.withLock { entries[peripheralId] } ?: return

            var evict = false
            entry.withLock {
                val pingOk = runCatching { entry.connection.ping() }.isSuccess
                if (pingOk) {
                    if (Clock.System.now() - entry.lastDataAt > IDLE_TIMEOUT) {
                        log.debug("Pool: idle timeout for $peripheralId")
                        mutex.withLock { entries.remove(peripheralId) }
                        evict = true
                    }
                } else {
                    log.debug("Pool: ping failed for $peripheralId, reconnecting")
                    withContext(NonCancellable) { runCatching { entry.connection.disconnect() } }
                    var reconnected = false
                    for (attempt in 0 until MAX_RECONNECT_ATTEMPTS) {
                        delay(RECONNECT_BACKOFF_BASE * (1 shl attempt))
                        val newConn = connectionFactory(entry.advertisement)
                        if (runCatching { newConn.connect() }.isSuccess) {
                            entry.connection = newConn
                            entry.responseCollectorJob?.cancel()
                            entry.responseCollectorJob = scope.launch { collectResponses(peripheralId, newConn) }
                            reconnected = true
                            log.debug("Pool: reconnected $peripheralId (attempt ${attempt + 1})")
                            break
                        }
                    }
                    if (!reconnected) {
                        log.debug("Pool: exhausted reconnect attempts for $peripheralId")
                        mutex.withLock { entries.remove(peripheralId) }
                        evict = true
                    }
                }
            }

            if (evict) {
                entry.responseCollectorJob?.cancel()
                withContext(NonCancellable) { runCatching { entry.connection.disconnect() } }
                return
            }
        }
    }

    /** Collects response notifications from [conn] and routes them to [onResponseReceived]. */
    private suspend fun collectResponses(peripheralId: String, conn: BleConnectionPort) {
        runCatching {
            conn.observeResponses().collect { bytes -> onResponseReceived(peripheralId, bytes) }
        }
    }

    companion object {
        /** Drop a pooled connection after this much idle time (no data exchange). */
        private val IDLE_TIMEOUT = 30.seconds

        /** Maximum number of reconnect attempts before evicting a peripheral. */
        private const val MAX_RECONNECT_ATTEMPTS = 3

        /** Base delay for exponential backoff: attempt 0 = 2s, 1 = 4s, 2 = 8s. */
        private val RECONNECT_BACKOFF_BASE = 2.seconds

        /** How often the keepalive coroutine pings a pooled connection. */
        private val KEEPALIVE_INTERVAL = 5.seconds
    }
}
