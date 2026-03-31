package io.github.smyrgeorge.freepath.libble

import com.juul.kable.Advertisement
import com.juul.kable.Scanner
import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.libble.BleConstants.FREEPATH_SERVICE_UUID
import io.github.smyrgeorge.freepath.libble.LibbleEvent.Response
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeInitiator
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeResponder
import io.github.smyrgeorge.freepath.libble.gatt.BleGattServer
import io.github.smyrgeorge.freepath.libble.gatt.BleGattServerEvent
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class LibbleModule {

    private val log = Logger.of(this::class)

    val metrics: LibbleMetrics = LibbleMetrics()
    val requests: Channel<LibbleEvent.RequestReceived> = Channel(
        capacity = 1000,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )
    private val started = AtomicBoolean(false)

    @Volatile
    private var handler: (suspend (LibbleEvent) -> Unit)? = null

    private lateinit var peerIdLookup: suspend (peripheralId: String) -> String?
    private lateinit var peripheralIdLookup: suspend (peerId: String) -> String?
    private lateinit var peerIdByRawBytesLookup: suspend (rawBytes: ByteArray) -> String?
    private lateinit var onNewPeripheralId: suspend (peerId: String, peripheralId: String) -> Unit

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rpc = RpcManager<Response>()

    private val expiryJob: Job
    private val advertiser: LibbleAdvertiser = LibbleAdvertiser()

    private val gattServer: BleGattServer = BleGattServer()
    private val gattServerStarted = AtomicBoolean(false)

    private val peripherals = mutableMapOf<String, PeripheralEntry>()
    private val peripheralsMutex = Mutex()

    private val pool = BleConnectionPool(
        scope = scope,
        rpc = rpc,
        advertisementLookup = { peripheralsMutex.withLock { peripherals[it]?.advertisement } },
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
                peerIdLookup(id)?.let { peerId -> sendEvent(LibbleEvent.PeerLost(peerId)) }
                log.debug("Removed expired peripheral: $id")
            }
        }
    }

    suspend fun start(
        bleBeaconId: ByteArray,
        peripheralIdLookup: suspend (peerId: String) -> String?,
        peerIdLookup: suspend (peripheralId: String) -> String?,
        peerIdByRawBytesLookup: suspend (rawBytes: ByteArray) -> String?,
        onNewPeripheralId: suspend (peerId: String, peripheralId: String) -> Unit,
    ) {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        this.peripheralIdLookup = peripheralIdLookup
        this.peerIdLookup = peerIdLookup
        this.peerIdByRawBytesLookup = peerIdByRawBytesLookup
        this.onNewPeripheralId = onNewPeripheralId
        log.info("LibbleModule starting")
        advertiser.start(bleBeaconId)
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
                if (isNew) {
                    sendEvent(event)
                    // Primary: look up by peripheralId in the routing table (fast).
                    val peerId = peerIdLookup(peripheralId)
                        ?: run {
                            // Fallback: read the BLE beacon ID from service data — works even
                            // when Android randomizes its BLE MAC address on restart.
                            val raw = advertisement.serviceData(FREEPATH_SERVICE_UUID)
                                ?.takeIf { it.size >= 8 }
                                ?.copyOfRange(0, 8)
                                ?: return@run null
                            peerIdByRawBytesLookup(raw)?.also { found ->
                                // Update routing table so future peripheralId lookups hit directly.
                                onNewPeripheralId(found, peripheralId)
                            }
                        }
                    peerId?.let { sendEvent(LibbleEvent.PeerIdentified(it, peripheralId)) }
                }
            }
        }
    }

    fun setEventHandler(handler: suspend (LibbleEvent) -> Unit): LibbleModule {
        this.handler = handler
        return this
    }

    suspend fun request(
        timeout: Duration,
        peerId: String,
        payload: ByteArray
    ): Response = rpc.request(timeout) { sendRequest(peerId, it, payload) }

    suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray) {
        val peripheralId = peripheralIdLookup(peerId) ?: error("No BLE peripheral ID found for peerId=$peerId")
        pool.withConnection(peripheralId) { conn -> conn.writeRequest(reqId, payload) }
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
        localContact: Contact,
        sigKeyPrivate: ByteArray,
    ): Result<Contact> = runCatching {
        pool.withConnection(peripheralId) { conn ->
            BleExchangeInitiator(conn, pin)
                .run(localContact, sigKeyPrivate) { sendEvent(it) }
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
        localContact: Contact,
        sigKeyPrivate: ByteArray,
    ): Result<Pair<Contact, String>> =
        BleExchangeResponder(gattServer, pin).run(localContact, sigKeyPrivate) { event ->
            sendEvent(event)
        }

    private suspend fun startGattServer() {
        if (!gattServerStarted.compareAndSet(expectedValue = false, newValue = true)) return
        gattServer.start()
        scope.launch {
            gattServer.events.collect { event ->
                when (event) {
                    is BleGattServerEvent.RequestReceived -> {
                        val req = LibbleEvent.RequestReceived(event.senderId, event.reqId, event.payload)
                        requests.trySend(req).onFailure {
                            log.error { "Failed to send message to requests channel: $it" }
                        }
                        sendEvent(req)
                    }

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
