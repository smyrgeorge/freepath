package io.github.smyrgeorge.freepath.libble.exchange

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.ROLE_I
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.ROLE_R
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.STATUS_SUCCESS
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.cardAad
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.constantTimeEquals
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.decryptCard
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.deriveKeys
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.encryptCard
import io.github.smyrgeorge.freepath.libble.gatt.BleGattServerPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the responder side of the secure BLE contact exchange (5-message protocol).
 *
 * Subscribes to [BleGattServerPort.events] and reacts to incoming writes from the initiator.
 * The GATT server must be running before [run] is called.
 */
internal class BleExchangeResponder(
    private val gattServer: BleGattServerPort,
    private val pin: String,
) {
    init {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be exactly 4 digits" }
    }

    suspend fun run(
        localCard: ContactCard,
        sigKeyPrivate: ByteArray,
        onEvent: suspend (LibbleEvent.ContactExchange) -> Unit,
    ): Result<Pair<ContactCard, String>> = runCatching {
        onEvent(LibbleEvent.ContactExchange.Started)
        try {
            exchange(localCard, sigKeyPrivate, onEvent)
        } catch (e: CancellationException) {
            throw e  // propagate cancellation without emitting a Failed event
        } catch (e: Exception) {
            onEvent(LibbleEvent.ContactExchange.Failed(e.message ?: "exchange failed"))
            throw e
        }
    }.also { result ->
        // runCatching swallows CancellationException — rethrow it so coroutine cancellation
        // propagates correctly and doesn't surface as a spurious failure.
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    }

    private suspend fun exchange(
        localCard: ContactCard,
        sigKeyPrivate: ByteArray,
        onEvent: suspend (LibbleEvent.ContactExchange) -> Unit,
    ): Pair<ContactCard, String> {
        // Step 1: Wait for initiator's ephemeral public key.
        val ephemeralEvent = withTimeout(STEP_TIMEOUT) {
            gattServer.events
                .filterIsInstance<BleGattServerPort.Event.EphemeralReceived>()
                .first()
        }
        val centralId = ephemeralEvent.centralId
        val iEphPub = ephemeralEvent.bytes

        // Generate responder's ephemeral keypair and derive all session keys.
        val rEphKp = CryptoProvider.generateX25519KeyPair()
        val keys = deriveKeys(rEphKp.privateKey, rEphKp.publicKey, iEphPub, isInitiator = false, pin)

        // Step 2: Serve responder's ephemeral public key for initiator to read.
        gattServer.setEphemeralValue(rEphKp.publicKey)

        // Step 3: Wait for initiator's pinConfirm_I + encrypted card.
        val cardPayload = withTimeout(STEP_TIMEOUT) {
            gattServer.events
                .filterIsInstance<BleGattServerPort.Event.CardReceived>()
                .first()
                .bytes
        }
        require(cardPayload.size > PIN_CONFIRM_LEN) { "CARD payload too short" }
        val receivedPinConfirmI = cardPayload.copyOfRange(0, PIN_CONFIRM_LEN)
        require(constantTimeEquals(receivedPinConfirmI, keys.pinConfirmI)) { "PIN confirmation mismatch" }

        val peerCard = decryptCard(
            encryptedCard = cardPayload.copyOfRange(PIN_CONFIRM_LEN, cardPayload.size),
            sessionKey = keys.sessionKey,
            aad = cardAad(ROLE_I, keys.sessionId),
        )
        onEvent(LibbleEvent.ContactExchange.PinConfirmed)

        // Step 4: Serve responder's pinConfirm_R + encrypted card for initiator to read.
        val encCard = encryptCard(localCard, sigKeyPrivate, keys.sessionKey, cardAad(ROLE_R, keys.sessionId))
        gattServer.setCardValue(keys.pinConfirmR + encCard)

        // Step 5: Wait for initiator's STATUS write.
        val status = withTimeout(STEP_TIMEOUT) {
            gattServer.events
                .filterIsInstance<BleGattServerPort.Event.StatusReceived>()
                .first()
                .status
        }
        require(status == STATUS_SUCCESS) { "Initiator reported failure: 0x${status.toString(16)}" }

        onEvent(LibbleEvent.ContactExchange.Completed(peerCard))
        return Pair(peerCard, centralId)
    }

    companion object {
        private const val PIN_CONFIRM_LEN = 32
        private val STEP_TIMEOUT = 30.seconds
    }
}
