package io.github.smyrgeorge.freepath.libble.exchange

import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeRunner.ExchangeResult
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeRunner.PIN_CONFIRM_LEN
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.ROLE_I
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.ROLE_R
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.STATUS_SUCCESS
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.cardAad
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.constantTimeEquals
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.decryptContact
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.deriveKeys
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.encryptContact
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.classic.debug
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the responder side of the secure BLE contact exchange (5-message protocol).
 *
 * Transport-agnostic: symmetric with [BleExchangeInitiator].
 */
internal class BleExchangeResponder(
    private val sendExchange: suspend (ByteArray) -> Unit,
    private val exchangeFrames: Channel<ByteArray>,
    private val pin: String,
) {
    private val log = Logger.of(this::class)

    init {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be exactly 4 digits" }
    }

    private suspend fun receiveExchange(): ByteArray =
        withTimeout(STEP_TIMEOUT) { exchangeFrames.receive() }

    suspend fun run(
        localContact: Contact,
        sigKeyPrivate: ByteArray,
        onEvent: suspend (LibbleEvent.ContactExchange) -> Unit,
    ): Result<ExchangeResult> = BleExchangeRunner.run(sendExchange, onEvent) {
        exchange(localContact, sigKeyPrivate, onEvent)
    }

    private suspend fun exchange(
        localContact: Contact,
        sigKeyPrivate: ByteArray,
        onEvent: suspend (LibbleEvent.ContactExchange) -> Unit,
    ): ExchangeResult {
        // Step 1: Receive initiator's ephemeral public key.
        log.debug("Responder: step 1 — waiting for initiator ephemeral key")
        val iEphPub = receiveExchange()

        // Generate responder's ephemeral keypair and derive all session keys.
        val rEphKp = CryptoProvider.generateX25519KeyPair()
        val keys = deriveKeys(rEphKp.privateKey, rEphKp.publicKey, iEphPub, isInitiator = false, pin)

        // Step 2: Send responder's ephemeral public key.
        log.debug("Responder: step 2 — sending ephemeral key")
        sendExchange(rEphKp.publicKey)

        // Step 3: Receive initiator's pinConfirm_I + encrypted contact card.
        log.debug("Responder: step 3 — waiting for initiator card")
        val cardPayload = receiveExchange()
        // A 1-byte payload is a status frame sent by the initiator on failure.
        if (cardPayload.size == 1) {
            val code = cardPayload[0]
            error("Initiator reported failure: 0x${code.toString(16)}")
        }
        require(cardPayload.size > PIN_CONFIRM_LEN) { "CARD payload too short" }
        val receivedPinConfirmI = cardPayload.copyOfRange(0, PIN_CONFIRM_LEN)
        require(constantTimeEquals(receivedPinConfirmI, keys.pinConfirmI)) { "PIN confirmation mismatch" }
        val peerContact = decryptContact(
            encryptedContact = cardPayload.copyOfRange(PIN_CONFIRM_LEN, cardPayload.size),
            sessionKey = keys.sessionKey,
            aad = cardAad(ROLE_I, keys.sessionId),
        )
        onEvent(LibbleEvent.ContactExchange.PinConfirmed)

        // Step 4: Send responder's pinConfirm_R + encrypted contact card.
        log.debug("Responder: step 4 — sending card")
        val encContact = encryptContact(localContact, sigKeyPrivate, keys.sessionKey, cardAad(ROLE_R, keys.sessionId))
        sendExchange(keys.pinConfirmR + encContact)

        // Step 5: Receive initiator's status byte.
        log.debug("Responder: step 5 — waiting for initiator status")
        val statusFrame = receiveExchange()
        require(statusFrame.isNotEmpty() && statusFrame[0] == STATUS_SUCCESS) {
            "Initiator reported failure: 0x${statusFrame.firstOrNull()?.toString(16) ?: "empty"}"
        }

        onEvent(LibbleEvent.ContactExchange.Completed(peerContact, keys.identitySecret))
        return ExchangeResult(peerContact, keys.identitySecret)
    }

    companion object {
        private val STEP_TIMEOUT = 30.seconds
    }
}
