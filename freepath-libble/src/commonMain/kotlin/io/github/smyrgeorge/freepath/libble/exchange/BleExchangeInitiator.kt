package io.github.smyrgeorge.freepath.libble.exchange

import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.ROLE_I
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.ROLE_R
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.STATUS_ERROR
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.STATUS_FAILURE
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.STATUS_SUCCESS
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.cardAad
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.constantTimeEquals
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.decryptContact
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.deriveKeys
import io.github.smyrgeorge.freepath.libble.exchange.BleExchangeCrypto.encryptContact
import io.github.smyrgeorge.freepath.libble.pool.BleConnectionPort
import kotlinx.coroutines.CancellationException

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
        localContact: Contact,
        sigKeyPrivate: ByteArray,
        onEvent: suspend (LibbleEvent.ContactExchange) -> Unit,
    ): Result<Contact> = runCatching {
        onEvent(LibbleEvent.ContactExchange.Started)
        try {
            exchange(localContact, sigKeyPrivate, onEvent)
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
        localContact: Contact,
        sigKeyPrivate: ByteArray,
        onEvent: suspend (LibbleEvent.ContactExchange) -> Unit,
    ): Contact {
        // Step 1: Generate ephemeral keypair and send public key.
        val iEphKp = CryptoProvider.generateX25519KeyPair()
        connection.writeEphemeral(iEphKp.publicKey)

        // Step 2: Read responder's ephemeral public key and derive all session keys.
        val rEphPub = connection.readEphemeral()
        val keys = deriveKeys(iEphKp.privateKey, iEphKp.publicKey, rEphPub, isInitiator = true, pin)

        // Step 3: Send pinConfirm_I + encrypted card.
        val encContact = encryptContact(localContact, sigKeyPrivate, keys.sessionKey, cardAad(ROLE_I, keys.sessionId))
        connection.writeContact(keys.pinConfirmI + encContact)

        // Step 4: Read and validate responder's response.
        val respPayload = connection.readContact()
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
        connection.writeStatus(STATUS_SUCCESS)
        onEvent(LibbleEvent.ContactExchange.Completed(peerContact))
        return peerContact
    }

    companion object {
        private const val PIN_CONFIRM_LEN = 32
    }
}
