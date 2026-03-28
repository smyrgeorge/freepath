package io.github.smyrgeorge.freepath.libble.exchange

import io.github.smyrgeorge.freepath.contact.ContactCard
import kotlinx.coroutines.CancellationException
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.ROLE_I
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.ROLE_R
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.STATUS_ERROR
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.STATUS_FAILURE
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.STATUS_SUCCESS
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.cardAad
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.constantTimeEquals
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.decryptCard
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.deriveKeys
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.encryptCard
import io.github.smyrgeorge.freepath.libble.pool.BleConnectionPort

/**
 * Drives the initiator side of the secure BLE contact exchange (5-message protocol).
 *
 * The caller is responsible for connecting to the correct peripheral before calling [run].
 */
internal class BleExchangeInitiator(
    private val connection: BleConnectionPort,
    private val pin: String,
) {
    init {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be exactly 4 digits" }
    }

    suspend fun run(
        localCard: ContactCard,
        sigKeyPrivate: ByteArray,
        onEvent: suspend (LibbleEvent.ContactExchange) -> Unit,
    ): Result<ContactCard> = runCatching {
        onEvent(LibbleEvent.ContactExchange.Started)
        try {
            exchange(localCard, sigKeyPrivate, onEvent)
        } catch (e: CancellationException) {
            throw e  // propagate cancellation without emitting a Failed event
        } catch (e: Exception) {
            val status = if (e is IllegalStateException) STATUS_FAILURE else STATUS_ERROR
            runCatching { connection.writeStatus(status) }
            onEvent(LibbleEvent.ContactExchange.Failed(e.message ?: "exchange failed"))
            throw e
        }
    }.also { result ->
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    }

    private suspend fun exchange(
        localCard: ContactCard,
        sigKeyPrivate: ByteArray,
        onEvent: suspend (LibbleEvent.ContactExchange) -> Unit,
    ): ContactCard {
        // Step 1: Generate ephemeral keypair and send public key.
        val iEphKp = CryptoProvider.generateX25519KeyPair()
        connection.writeEphemeral(iEphKp.publicKey)

        // Step 2: Read responder's ephemeral public key and derive all session keys.
        val rEphPub = connection.readEphemeral()
        val keys = deriveKeys(iEphKp.privateKey, iEphKp.publicKey, rEphPub, isInitiator = true, pin)

        // Step 3: Send pinConfirm_I + encrypted card.
        val encCard = encryptCard(localCard, sigKeyPrivate, keys.sessionKey, cardAad(ROLE_I, keys.sessionId))
        connection.writeContactCard(keys.pinConfirmI + encCard)

        // Step 4: Read and validate responder's response.
        val respPayload = connection.readContactCard()
        require(respPayload.size > PIN_CONFIRM_LEN) { "CARD response too short" }
        val receivedPinConfirmR = respPayload.copyOfRange(0, PIN_CONFIRM_LEN)
        require(constantTimeEquals(receivedPinConfirmR, keys.pinConfirmR)) { "PIN confirmation mismatch" }

        val peerCard = decryptCard(
            encryptedCard = respPayload.copyOfRange(PIN_CONFIRM_LEN, respPayload.size),
            sessionKey = keys.sessionKey,
            aad = cardAad(ROLE_R, keys.sessionId),
        )
        onEvent(LibbleEvent.ContactExchange.PinConfirmed)

        // Step 5: Signal success to responder.
        connection.writeStatus(STATUS_SUCCESS)
        onEvent(LibbleEvent.ContactExchange.Completed(peerCard))
        return peerCard
    }

    companion object {
        private const val PIN_CONFIRM_LEN = 32
    }
}
