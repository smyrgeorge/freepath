package io.github.smyrgeorge.freepath.libble

import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.libble.LibbleEvent.Response
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeResult
import io.github.smyrgeorge.freepath.libble.metrics.LibbleMetrics
import kotlinx.coroutines.channels.Channel
import kotlin.time.Duration

interface LibbleModule {
    val metrics: LibbleMetrics
    val requests: Channel<LibbleEvent.RequestReceived>

    fun setEventHandler(handler: suspend (LibbleEvent) -> Unit): LibbleModule

    suspend fun start(
        localPeerId: String,
        contactSecretsLookup: suspend () -> Map<String, ByteArray>,
    )

    suspend fun stop()

    suspend fun peerIdForPeripheral(peripheralId: String): String?

    suspend fun request(timeout: Duration, peerId: String, payload: ByteArray): Response

    suspend fun relayToUnknown(timeout: Duration, peripheralId: String, payload: ByteArray): Response

    suspend fun sendRequest(peerId: String, reqId: Long, payload: ByteArray)
    suspend fun sendResponse(reqId: Long, payload: ByteArray)
    suspend fun sendResponseFailed(reqId: Long, error: String)

    suspend fun beginInitiatorExchange(
        peripheralId: String,
        pin: String,
        localContact: Contact,
        sigKeyPrivate: ByteArray,
    ): Result<BleExchangeResult>

    suspend fun beginResponderExchange(
        pin: String,
        localContact: Contact,
        sigKeyPrivate: ByteArray,
    ): Result<BleExchangeResult>
}
