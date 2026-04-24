package io.github.smyrgeorge.freepath.libble.exchange

import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.STATUS_ERROR
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.STATUS_FAILURE
import kotlinx.coroutines.CancellationException

/**
 * Common run wrapper for both initiator and responder.
 *
 * Handles:
 * - Emitting [LibbleEvent.ContactExchange.Started] before the exchange begins
 * - Emitting [LibbleEvent.ContactExchange.Failed] and sending a status byte on error
 * - Propagating [CancellationException] without emitting a Failed event
 */
internal object BleExchangeRunner {

    const val PIN_CONFIRM_LEN = 32

    class ExchangeResult(val contact: Contact, val identitySecret: ByteArray)

    suspend fun run(
        sendExchange: suspend (ByteArray) -> Unit,
        onEvent: suspend (LibbleEvent.ContactExchange) -> Unit,
        exchange: suspend () -> ExchangeResult,
    ): Result<ExchangeResult> = runCatching {
        onEvent(LibbleEvent.ContactExchange.Started)
        try {
            exchange()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = if (e is IllegalStateException) STATUS_FAILURE else STATUS_ERROR
            runCatching { sendExchange(byteArrayOf(status)) }
            onEvent(LibbleEvent.ContactExchange.Failed(e.message ?: "exchange failed"))
            throw e
        }
    }.also { result ->
        // runCatching swallows CancellationException — rethrow it so coroutine cancellation
        // propagates correctly and doesn't surface as a spurious failure.
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    }
}
