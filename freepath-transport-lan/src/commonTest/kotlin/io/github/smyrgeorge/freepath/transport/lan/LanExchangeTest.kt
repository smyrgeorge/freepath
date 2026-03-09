package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.ContactCardCodec
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.contact.exchange.LanContactExchange
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.codec.Base58
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class LanExchangeTest {

    private fun createIdentity(): Identity {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val nodeIdRaw = CryptoProvider.sha256(sigKp.publicKey).copyOf(16)
        return Identity(nodeIdRaw, sigKp.publicKey, sigKp.privateKey, encKp.publicKey, encKp.privateKey)
    }

    private fun nodeIdString(identity: Identity): String = Base58.encode(identity.nodeIdRaw)

    private fun contactCardFor(identity: Identity): ContactCard = ContactCard(
        schema = ContactCard.SCHEMA,
        nodeId = ContactCardCodec.deriveNodeId(identity.sigKeyPublic),
        sigKey = Base64.encode(identity.sigKeyPublic),
        encKey = Base64.encode(identity.encKeyPublic),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    /**
     * Builds a minimal LanLinkAdapter with an onExchangeRequested callback.
     * isKnownPeer always returns false so that connectToDiscoveredPeer only stores
     * the address in discoveredAddresses without opening a full TCP/handshake connection.
     */
    private fun buildExchangeAdapter(
        identity: Identity,
        onExchangeRequested: (suspend (pin: String, peerCardBytes: ByteArray) -> ByteArray?)?,
    ): LanLinkAdapter {
        val adapter = LanLinkAdapter(
            peerDiscovery = InMemoryDiscovery(nodeIdString(identity)),
            onPeerDisconnected = {},
            isKnownPeer = { false },
            onConnectionEstablished = {},
            onExchangeRequested = onExchangeRequested,
        )
        adapter.setInboundFrameHandler { _, _ -> }
        return adapter
    }

    @Test
    fun `exchangeFrame succeeds when peer returns card bytes for correct PIN`() = runBlocking {
        withTimeout(5_000) {
            val identityA = createIdentity()
            val identityB = createIdentity()

            val nodeIdA = nodeIdString(identityA)

            val cardA = contactCardFor(identityA)
            val cardB = contactCardFor(identityB)
            val correctPin = "123456"

            var receivedByA: ByteArray? = null

            // Node A is the responder: returns its signed card only when the PIN matches.
            val adapterA = buildExchangeAdapter(identityA) { pin, peerCardBytes ->
                receivedByA = peerCardBytes
                if (pin == correctPin) LanContactExchange.encode(cardA, identityA.sigKeyPrivate) else null
            }
            // Node B is the initiator: no exchange callback needed.
            val adapterB = buildExchangeAdapter(identityB, onExchangeRequested = null)

            try {
                adapterA.start()
                adapterB.start()

                // Call directly (not via mDNS) to inject B's address into A's discoveredAddresses
                // isKnownPeer=false causes the adapter to store the address and skip the handshake
                launch { adapterB.connectToDiscoveredPeer(nodeIdA, "127.0.0.1", adapterA.localPort) }.join()

                val result = adapterB.contactExchangeFrame(nodeIdA, correctPin, cardB, identityB.sigKeyPrivate)

                assertTrue(result.isSuccess, "Exchange should succeed with correct PIN")
                val receivedCard = result.getOrThrow()
                assertEquals(cardA.nodeId, receivedCard.nodeId, "B should receive A's ContactCard")

                // A should have received B's signed card bytes — decode and verify nodeId
                val decodedByA = receivedByA?.let { LanContactExchange.decode(it).getOrNull() }
                assertEquals(cardB.nodeId, decodedByA?.nodeId, "A should have received B's card")
            } finally {
                adapterA.stop()
                adapterB.stop()
            }
        }
    }

    @Test
    fun `exchangeFrame fails when peer rejects due to wrong PIN`() = runBlocking {
        withTimeout(5_000) {
            val identityA = createIdentity()
            val identityB = createIdentity()

            val nodeIdA = nodeIdString(identityA)

            val cardA = contactCardFor(identityA)
            val cardB = contactCardFor(identityB)
            val correctPin = "999999"
            val wrongPin = "000000"

            // Node A is the responder: rejects any PIN that doesn't match.
            val adapterA = buildExchangeAdapter(identityA) { pin, _ ->
                if (pin == correctPin) LanContactExchange.encode(cardA, identityA.sigKeyPrivate) else null
            }
            // Node B is the initiator.
            val adapterB = buildExchangeAdapter(identityB, onExchangeRequested = null)

            try {
                adapterA.start()
                adapterB.start()

                // Call directly (not via mDNS) to inject B's address into A's discoveredAddresses
                // isKnownPeer=false causes the adapter to store the address and skip the handshake
                launch { adapterB.connectToDiscoveredPeer(nodeIdA, "127.0.0.1", adapterA.localPort) }.join()

                val result = adapterB.contactExchangeFrame(nodeIdA, wrongPin, cardB, identityB.sigKeyPrivate)

                assertFalse(result.isSuccess, "Exchange should fail with wrong PIN")
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("invalid PIN", ignoreCase = true) == true,
                    "Failure should be due to PIN rejection, got: ${result.exceptionOrNull()}"
                )
            } finally {
                adapterA.stop()
                adapterB.stop()
            }
        }
    }
}
