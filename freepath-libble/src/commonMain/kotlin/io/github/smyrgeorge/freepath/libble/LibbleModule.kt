package io.github.smyrgeorge.freepath.libble

import com.juul.kable.Scanner
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
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalAtomicApi::class, ExperimentalUuidApi::class)
class LibbleModule {

    private val log = Logger.of(this::class)

    val metrics: LibbleMetrics = LibbleMetrics()

    private val started = AtomicBoolean(false)
    private val advertiser: LibbleAdvertiser = LibbleAdvertiser()
    private var handler: (suspend (LibbleEvent) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val peripherals = mutableMapOf<String, LibbleEvent.BlePeripheralDiscovered>()
    private val peripheralsMutex = Mutex()

    init {
        doEvery(1.seconds) {
            val now = Clock.System.now()
            peripheralsMutex.withLock {
                peripherals
                    .filter { (_, peripheral) -> now - peripheral.discoveredAt > 5.seconds }
                    .keys
                    .forEach { peripheralId ->
                        peripherals.remove(peripheralId)
                        val event = LibbleEvent.BlePeripheralExpired(peripheralId)
                        sendEvent(event)
                        log.debug("Removed expired peripheral: $peripheralId")
                    }
            }
        }
    }

    fun setEventHandler(handler: suspend (LibbleEvent) -> Unit): LibbleModule {
        this.handler = handler
        return this
    }

    suspend fun start() {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        log.info("BleModule starting scan")
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
                val event = LibbleEvent.BlePeripheralDiscovered(
                    discoveredAt = Clock.System.now(),
                    peripheralId = advertisement.identifier.toString(),
                    peripheralName = advertisement.peripheralName,
                    name = advertisement.name,
                    rssi = advertisement.rssi,
                    txPower = advertisement.txPower,
                    isConnectable = advertisement.isConnectable,
                )
                peripheralsMutex.withLock { peripherals[event.peripheralId] = event }
                sendEvent(event)
            }
        }
    }

    suspend fun stop() {
        if (!started.compareAndSet(expectedValue = true, newValue = false)) return
        log.info("BleModule stopping scan")
        metrics.close()
        advertiser.stop()
        scope.cancel()
    }

    private suspend fun sendEvent(event: LibbleEvent) {
        metrics.onEvent(event)
        handler?.let { h -> runCatching { h(event) } }
    }

    companion object {
        val FREEPATH_SERVICE_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf11")
    }
}
