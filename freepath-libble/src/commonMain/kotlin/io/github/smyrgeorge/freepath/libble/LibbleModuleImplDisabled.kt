package io.github.smyrgeorge.freepath.libble

import io.github.smyrgeorge.freepath.libble.LibbleEvent.Response
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeResult
import io.github.smyrgeorge.freepath.libble.metrics.LibbleMetrics
import io.github.smyrgeorge.freepath.model.contact.Contact
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration

class LibbleModuleImplDisabled : LibbleModule {
    override val metrics: LibbleMetrics = LibbleMetrics()
    override val requests: Channel<LibbleEvent.RequestReceived> = Channel(Channel.UNLIMITED)

    override fun setEventHandler(handler: suspend (LibbleEvent) -> Unit): LibbleModule = this

    override suspend fun start(
        localPeerId: String,
        contactSecretsLookup: suspend () -> Map<String, ByteArray>,
    ) {
        // BLE disabled: nothing to start.
    }

    override suspend fun stop() {
        // BLE disabled: nothing to stop.
    }

    override suspend fun peerIdForPeripheral(peripheralId: String): String? = null
    override suspend fun request(timeout: Duration, peerId: String, payload: ByteArray): Response =
        LibbleEvent.RequestFailed(reqId = 0L, senderId = peerId, error = BLE_DISABLED)

    override suspend fun relayToUnknown(timeout: Duration, peripheralId: String, payload: ByteArray): Response =
        LibbleEvent.RequestFailed(reqId = 0L, senderId = peripheralId, error = BLE_DISABLED)

    override suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray) {
        // BLE disabled: nothing to send.
    }

    override suspend fun sendResponse(reqId: Long, payload: ByteArray) {
        // BLE disabled: nothing to send.
    }

    override suspend fun sendResponseFailed(reqId: Long, error: String) {
        // BLE disabled: nothing to send.
    }

    override suspend fun beginInitiatorExchange(
        peripheralId: String,
        pin: String,
        localContact: Contact,
        sigKeyPrivate: ByteArray,
    ): Result<BleExchangeResult> = Result.failure(IllegalStateException(BLE_DISABLED))

    override suspend fun beginResponderExchange(
        pin: String,
        localContact: Contact,
        sigKeyPrivate: ByteArray,
    ): Result<BleExchangeResult> = Result.failure(IllegalStateException(BLE_DISABLED))

    companion object {
        private const val BLE_DISABLED = "BLE is currently disabled"
    }
}
