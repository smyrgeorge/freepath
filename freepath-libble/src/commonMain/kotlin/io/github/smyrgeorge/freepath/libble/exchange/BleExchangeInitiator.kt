package io.github.smyrgeorge.freepath.libble.exchange

import io.github.smyrgeorge.freepath.contact.Contact
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the initiator side of the secure BLE contact exchange (5-message protocol).
 *
 * Transport-agnostic: receives bytes via [exchangeFrames] and sends via [sendExchange].
 */
internal class BleExchangeInitiator(
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
        // Step 1: Send initiator's ephemeral public key.
        log.debug("Initiator: step 1 — sending ephemeral key")
        val iEphKp = CryptoProvider.generateX25519KeyPair()
        sendExchange(iEphKp.publicKey)

        // Step 2: Receive responder's ephemeral public key and derive session keys.
        log.debug("Initiator: step 2 — waiting for responder ephemeral key")
        val rEphPub = receiveExchange()
        val keys = deriveKeys(iEphKp.privateKey, iEphKp.publicKey, rEphPub, isInitiator = true, pin)

        // Step 3: Send pinConfirm_I + encrypted contact card.
        log.debug("Initiator: step 3 — sending card")
        val encContact = encryptContact(localContact, sigKeyPrivate, keys.sessionKey, cardAad(ROLE_I, keys.sessionId))
        sendExchange(keys.pinConfirmI + encContact)

        // Step 4: Receive responder's pinConfirm_R + encrypted contact card.
        log.debug("Initiator: step 4 — waiting for responder card")
        val respPayload = receiveExchange()
        // A 1-byte payload is a status frame sent by the responder on failure.
        if (respPayload.size == 1) {
            val code = respPayload[0]
            error("Responder reported failure: 0x${code.toString(16)}")
        }
        require(respPayload.size > PIN_CONFIRM_LEN) { "CARD response too short" }
        val receivedPinConfirmR = respPayload.copyOfRange(0, PIN_CONFIRM_LEN)
        require(constantTimeEquals(receivedPinConfirmR, keys.pinConfirmR)) { "PIN confirmation mismatch" }
        val peerContact = decryptContact(
            encryptedContact = respPayload.copyOfRange(PIN_CONFIRM_LEN, respPayload.size),
            sessionKey = keys.sessionKey,
            aad = cardAad(ROLE_R, keys.sessionId),
        )
        onEvent(LibbleEvent.ContactExchange.PinConfirmed)

        // Step 5: Signal success to responder.
        log.debug("Initiator: step 5 — sending success status")
        sendExchange(byteArrayOf(STATUS_SUCCESS))
        onEvent(LibbleEvent.ContactExchange.Completed(peerContact, keys.identitySecret))
        return ExchangeResult(peerContact, keys.identitySecret)
    }

    companion object {
        private val STEP_TIMEOUT = 30.seconds
    }
}
