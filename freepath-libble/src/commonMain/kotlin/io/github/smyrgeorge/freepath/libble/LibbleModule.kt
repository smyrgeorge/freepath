package io.github.smyrgeorge.freepath.libble

import com.juul.kable.Advertisement
import com.juul.kable.Scanner
import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.libble.BleConstants.FREEPATH_SERVICE_UUID
import io.github.smyrgeorge.freepath.libble.exchange.SecureExchangeInitiator
import io.github.smyrgeorge.freepath.libble.exchange.SecureExchangeResponder
import io.github.smyrgeorge.freepath.libble.gatt.BleGattServer
import io.github.smyrgeorge.freepath.libble.gatt.BleGattServerPort
import io.github.smyrgeorge.freepath.libble.metrics.LibbleMetrics
import io.github.smyrgeorge.freepath.libble.pool.BleConnectionPool
import io.github.smyrgeorge.freepath.util.rpc.RpcManager
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.impl.extensions.doEvery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
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

@OptIn(ExperimentalAtomicApi::class)
class LibbleModule {

    private val log = Logger.of(this::class)

    val metrics: LibbleMetrics = LibbleMetrics()
    private val started = AtomicBoolean(false)

    @Volatile
    private var handler: (suspend (LibbleEvent) -> Unit)? = null

    @Volatile
    private var peripheralIdLookup: (suspend (peerId: String) -> String?)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rpcManager = RpcManager<Result<ByteArray>>()

    private val expiryJob: Job
    private val advertiser: LibbleAdvertiser = LibbleAdvertiser()

    private val gattServer: BleGattServer = BleGattServer()
    private val gattServerStarted = AtomicBoolean(false)

    private val peripherals = mutableMapOf<String, PeripheralEntry>()
    private val peripheralsMutex = Mutex()

    private val pool = BleConnectionPool(
        scope = scope,
        advertisementLookup = { peripheralsMutex.withLock { peripherals[it]?.advertisement } },
        onResponseReceived = { _, bytes -> decodeAndRouteResponse(bytes) },
    )

    init {
        // init{} guarantees peripheralsMutex and pool are fully initialized before doEvery starts.
        // Note: doEvery runs on a detached scope (EmptyScope / Dispatchers.Default), independent
        // of LibbleModule.scope. This is why expiryJob must be cancelled explicitly in stop()
        // before pool.close() — otherwise the timer can fire concurrently with pool draining.
        expiryJob = doEvery(1.seconds) {
            val now = Clock.System.now()
            val expired = mutableListOf<String>()

            peripheralsMutex.withLock {
                peripherals
                    .filter { (_, e) -> now - e.event.discoveredAt > ADVERTISEMENT_EXPIRE_THRESHOLD }
                    .forEach { (id, _) ->
                        peripherals.remove(id)
                        expired += id
                    }
            }

            expired.forEach { id ->
                pool.remove(id)
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
        scope.launch {
            val scanner = Scanner {
                filters {
                    match {
                        services = listOf(FREEPATH_SERVICE_UUID)
                    }
                }
            }

            scanner.advertisements.collect { advertisement ->
                val peripheralId = advertisement.identifier.toString()
                val isNew = peripheralsMutex.withLock { peripheralId !in peripherals }
                val event = LibbleEvent.PeripheralDiscovered(
                    discoveredAt = Clock.System.now(),
                    peripheralId = peripheralId,
                    peripheralName = advertisement.peripheralName,
                    name = advertisement.name,
                    rssi = advertisement.rssi,
                    txPower = advertisement.txPower,
                    isConnectable = advertisement.isConnectable,
                )
                peripheralsMutex.withLock {
                    peripherals[peripheralId] = PeripheralEntry(
                        event = event,
                        advertisement = advertisement,
                    )
                }
                if (isNew) sendEvent(event)
            }
        }
    }

    fun setEventHandler(handler: suspend (LibbleEvent) -> Unit): LibbleModule {
        this.handler = handler
        return this
    }

    fun setPeripheralIdLookup(lookup: suspend (peerId: String) -> String?): LibbleModule {
        this.peripheralIdLookup = lookup
        return this
    }

    suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray): Result<ByteArray> = runCatching {
        val lookup = peripheralIdLookup ?: error("peripheralIdLookup not set")
        val peripheralId = lookup(peerId) ?: error("No BLE peripheral ID found for peerId=$peerId")
        rpcManager.request(reqId) {
            pool.withConnection(peripheralId) { conn -> conn.writeRequest(reqId, payload) }
        }.getOrThrow()
    }

    /** Sends a success response to the central that sent the GATT request with [reqId]. */
    suspend fun sendResponse(reqId: Long, payload: ByteArray) {
        gattServer.sendResponse(reqId, payload)
    }

    /** Sends a failure response to the central that sent the GATT request with [reqId]. */
    suspend fun sendResponseFailed(reqId: Long, error: String) {
        gattServer.sendResponseFailed(reqId, error)
    }

    suspend fun stop() {
        if (!started.compareAndSet(expectedValue = true, newValue = false)) return
        log.info("LibbleModule stopping")
        expiryJob.cancel()   // stop timer before draining pool
        pool.close()
        stopGattServer()
        metrics.close()
        advertiser.stop()
        scope.cancel()
    }

    /**
     * Runs the initiator side of the secure exchange with [peripheralId].
     * Emits [LibbleEvent.ContactExchange] events via the registered event handler.
     * Returns [Result.failure] if the peripheral is unknown or the exchange fails.
     */
    suspend fun beginInitiatorExchange(
        peripheralId: String,
        pin: String,
        localCard: ContactCard,
        sigKeyPrivate: ByteArray,
    ): Result<ContactCard> = runCatching {
        pool.withConnection(peripheralId) { conn ->
            SecureExchangeInitiator(conn, pin)
                .run(localCard, sigKeyPrivate) { sendEvent(it) }
                .getOrThrow()
        }
    }.also { result ->
        result.exceptionOrNull()?.let {
            // SecureExchangeInitiator.run already re-throws CancellationException from within
            // the exchange, but a CE thrown by pool.withConnection itself (e.g. during mutex
            // wait) is wrapped by the outer runCatching. This guard ensures that path also
            // propagates so coroutine cancellation is never swallowed.
            if (it is CancellationException) throw it
        }
    }

    /**
     * Runs the responder side of the secure exchange.
     * The GATT server must be started before calling this.
     * Emits [LibbleEvent.ContactExchange] events via the registered event handler.
     */
    suspend fun beginResponderExchange(
        pin: String,
        localCard: ContactCard,
        sigKeyPrivate: ByteArray,
    ): Result<ContactCard> =
        SecureExchangeResponder(gattServer, pin).run(localCard, sigKeyPrivate) { event ->
            sendEvent(event)
        }

    private suspend fun startGattServer() {
        if (!gattServerStarted.compareAndSet(expectedValue = false, newValue = true)) return
        gattServer.start()
        scope.launch {
            gattServer.events.collect { event ->
                when (event) {
                    is BleGattServerPort.Event.RequestReceived ->
                        sendEvent(LibbleEvent.RequestReceived(event.peripheralId, event.reqId, event.payload))

                    else -> Unit
                }
            }
        }
        log.info("BLE GATT server started")
    }

    private suspend fun stopGattServer() {
        if (!gattServerStarted.compareAndSet(expectedValue = true, newValue = false)) return
        gattServer.stop()
        log.info("BLE GATT server stopped")
    }

    private fun decodeAndRouteResponse(bytes: ByteArray) {
        if (bytes.size < 9) return
        val reqId = (0 until 8).fold(0L) { acc, i -> acc or ((bytes[i].toLong() and 0xFF) shl (i * 8)) }
        val status = bytes[8]
        val body = bytes.copyOfRange(9, bytes.size)
        val result = if (status == 0x00.toByte()) Result.success(body)
        else Result.failure(RuntimeException(body.decodeToString()))
        scope.launch { rpcManager.response(reqId, result) }
    }

    private suspend fun sendEvent(event: LibbleEvent) {
        metrics.onEvent(event)
        handler?.let { h -> runCatching { h(event) } }
    }

    private data class PeripheralEntry(
        val event: LibbleEvent.PeripheralDiscovered,
        val advertisement: Advertisement,
    )

    companion object {
        private val ADVERTISEMENT_EXPIRE_THRESHOLD = 3.seconds
    }
}
