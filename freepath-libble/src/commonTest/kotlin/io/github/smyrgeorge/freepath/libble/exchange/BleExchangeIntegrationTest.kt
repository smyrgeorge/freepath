package io.github.smyrgeorge.freepath.libble.exchange

import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.libble.LibbleEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class BleExchangeIntegrationTest {

    @Test
    fun `initiator and responder exchange contacts successfully with matching PIN`() = runTest {
        val i2r = Channel<ByteArray>(Channel.BUFFERED)
        val r2i = Channel<ByteArray>(Channel.BUFFERED)

        val aliceKeys = CryptoProvider.generateEd25519KeyPair()
        val aliceEncKeys = CryptoProvider.generateX25519KeyPair()
        val aliceContact = Contact(
            schema = Contact.SCHEMA,
            sigKey = Base64.encode(aliceKeys.publicKey),
            encKey = Base64.encode(aliceEncKeys.publicKey),
        )

        val bobKeys = CryptoProvider.generateEd25519KeyPair()
        val bobEncKeys = CryptoProvider.generateX25519KeyPair()
        val bobContact = Contact(
            schema = Contact.SCHEMA,
            sigKey = Base64.encode(bobKeys.publicKey),
            encKey = Base64.encode(bobEncKeys.publicKey),
        )

        val pin = "4321"
        val events = mutableListOf<LibbleEvent.ContactExchange>()

        val initiator = BleExchangeInitiator(
            sendExchange = { bytes -> i2r.send(bytes) },
            exchangeFrames = r2i,
            pin = pin,
        )
        val responder = BleExchangeResponder(
            sendExchange = { bytes -> r2i.send(bytes) },
            exchangeFrames = i2r,
            pin = pin,
        )

        val initiatorDeferred = async {
            initiator.run(aliceContact, aliceKeys.privateKey) { events += it }
        }
        val responderDeferred = async {
            responder.run(bobContact, bobKeys.privateKey) { }
        }

        val initiatorResult = initiatorDeferred.await()
        val responderResult = responderDeferred.await()

        assertTrue(initiatorResult.isSuccess, "Initiator failed: ${initiatorResult.exceptionOrNull()}")
        assertTrue(responderResult.isSuccess, "Responder failed: ${responderResult.exceptionOrNull()}")

        val receivedByInitiator = initiatorResult.getOrThrow()
        val receivedByResponder = responderResult.getOrThrow()

        assertEquals(bobContact.sigKey, receivedByInitiator.contact.sigKey)
        assertEquals(aliceContact.sigKey, receivedByResponder.contact.sigKey)

        // Both sides must derive the same identity secret.
        assertTrue(
            receivedByInitiator.identitySecret.contentEquals(receivedByResponder.identitySecret),
            "Identity secrets must match between initiator and responder"
        )
        assertEquals(32, receivedByInitiator.identitySecret.size)

        assertTrue(events.any { it is LibbleEvent.ContactExchange.PinConfirmed })
        assertTrue(events.last() is LibbleEvent.ContactExchange.Completed)
    }

    @Test
    fun `exchange fails when PINs do not match`() = runTest {
        val i2r = Channel<ByteArray>(Channel.BUFFERED)
        val r2i = Channel<ByteArray>(Channel.BUFFERED)

        val aliceKeys = CryptoProvider.generateEd25519KeyPair()
        val aliceEncKeys = CryptoProvider.generateX25519KeyPair()
        val aliceContact = Contact(
            schema = Contact.SCHEMA,
            sigKey = Base64.encode(aliceKeys.publicKey),
            encKey = Base64.encode(aliceEncKeys.publicKey),
        )
        val bobKeys = CryptoProvider.generateEd25519KeyPair()
        val bobEncKeys = CryptoProvider.generateX25519KeyPair()
        val bobContact = Contact(
            schema = Contact.SCHEMA,
            sigKey = Base64.encode(bobKeys.publicKey),
            encKey = Base64.encode(bobEncKeys.publicKey),
        )

        val initiator = BleExchangeInitiator(
            sendExchange = { bytes -> i2r.send(bytes) },
            exchangeFrames = r2i,
            pin = "1234",
        )
        val responder = BleExchangeResponder(
            sendExchange = { bytes -> r2i.send(bytes) },
            exchangeFrames = i2r,
            pin = "9999",
        )

        // Close channels when a side finishes so the waiting side is unblocked.
        val initiatorDeferred = async {
            try {
                initiator.run(aliceContact, aliceKeys.privateKey) { }
            } finally {
                i2r.close()
            }
        }
        val responderDeferred = async {
            try {
                responder.run(bobContact, bobKeys.privateKey) { }
            } finally {
                r2i.close()
            }
        }

        val initiatorResult = initiatorDeferred.await()
        val responderResult = responderDeferred.await()

        assertTrue(
            initiatorResult.isFailure && responderResult.isFailure,
            "Expected both sides to fail with PIN mismatch"
        )
    }
}
