package io.github.smyrgeorge.freepath.core.testing.fake

import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.libble.LibbleModule
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeResult
import io.github.smyrgeorge.freepath.libble.metrics.LibbleMetrics
import io.github.smyrgeorge.freepath.model.contact.Contact
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration

/**
 * A [io.github.smyrgeorge.freepath.libble.LibbleModule] that is "not supported": every *operation* throws.
 *
 * Only [metrics], [requests] and [setEventHandler] return real (inert) values, because they are read
 * eagerly by code that always runs: `AbstractAppState` reads `libble.metrics.value` to build
 * `nearbyPeers`/`onlinePeers`, and `LibnetModule.start()` consumes `libble.requests`.
 *
 * On JVM `Transport.LIBBLE.supported == false`, so `AbstractAppResources` never calls
 * `start()`/`stop()` and `LibnetModule` never routes to BLE — the throwing methods are unreachable
 * in a healthy test. If one is ever hit, that's a real bug and the exception surfaces it loudly.
 */
class FakeLibbleModule : LibbleModule {
    override val metrics: LibbleMetrics = LibbleMetrics()
    override val requests: Channel<LibbleEvent.RequestReceived> = Channel(Channel.UNLIMITED)

    override fun setEventHandler(handler: suspend (LibbleEvent) -> Unit): LibbleModule = this

    override suspend fun start(
        localPeerId: String,
        contactSecretsLookup: suspend () -> Map<String, ByteArray>,
    ): Unit = notSupported()

    override suspend fun stop(): Unit = notSupported()

    override suspend fun peerIdForPeripheral(peripheralId: String): String = notSupported()

    override suspend fun request(timeout: Duration, peerId: String, payload: ByteArray): LibbleEvent.Response =
        notSupported()

    override suspend fun relayToUnknown(
        timeout: Duration,
        peripheralId: String,
        payload: ByteArray
    ): LibbleEvent.Response =
        notSupported()

    override suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray): Unit = notSupported()
    override suspend fun sendResponse(reqId: Long, payload: ByteArray): Unit = notSupported()
    override suspend fun sendResponseFailed(reqId: Long, error: String): Unit = notSupported()

    override suspend fun beginInitiatorExchange(
        peripheralId: String,
        pin: String,
        localContact: Contact,
        sigKeyPrivate: ByteArray,
    ): Result<BleExchangeResult> = notSupported()

    override suspend fun beginResponderExchange(
        pin: String,
        localContact: Contact,
        sigKeyPrivate: ByteArray,
    ): Result<BleExchangeResult> = notSupported()

    private fun notSupported(): Nothing =
        throw UnsupportedOperationException("libble is not supported in the test framework")
}