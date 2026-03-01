package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.StatefulProtocol
import io.github.smyrgeorge.freepath.transport.codec.Base58
import io.github.smyrgeorge.freepath.transport.codec.StatelessEnvelopeCodec
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.transport.model.ContactInfo
import io.github.smyrgeorge.freepath.transport.model.LocalIdentity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LanStatelessEnvelopeTest {

    private fun createIdentity(): LocalIdentity {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val nodeIdRaw = CryptoProvider.sha256(sigKp.publicKey).copyOf(16)
        return LocalIdentity(nodeIdRaw, sigKp.publicKey, sigKp.privateKey, encKp.publicKey, encKp.privateKey)
    }

    private fun buildProtocol(
        identity: LocalIdentity,
        knownPeers: List<LocalIdentity>,
        received: Channel<Pair<String, ByteArray>>,
    ): Pair<StatefulProtocol, LanLinkAdapter> {
        val nodeId = Base58.encode(identity.nodeIdRaw)
        val knownNodeIds = knownPeers.map { Base58.encode(it.nodeIdRaw) }.toSet()
        var protocol: StatefulProtocol? = null

        val adapter = LanLinkAdapter(
            peerDiscovery = InMemoryDiscovery(nodeId),
            onPeerDisconnected = { peerId -> protocol?.closeSession(peerId) },
            isKnownPeer = { peerId -> peerId in knownNodeIds },
            onConnectionEstablished = { peerId -> protocol?.initiateHandshake(peerId) },
            onIdleTimeout = { peerId -> protocol?.closeSession(peerId) },
        )

        protocol = StatefulProtocol(
            identity = identity,
            contactLookup = { nodeIdBytes ->
                knownPeers.firstOrNull { it.nodeIdRaw.contentEquals(nodeIdBytes) }?.sigKeyPublic
            },
            linkAdapter = adapter,
            onFrameReceived = { peerId, frame, _ ->
                received.send(Pair(peerId, Base64.decode(frame.payload)))
            },
        )

        return Pair(protocol, adapter)
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `StatelessEnvelope delivery A to B`() = runBlocking {
        val identityA = createIdentity()
        val identityB = createIdentity()

        val nodeIdA = Base58.encode(identityA.nodeIdRaw)
        val nodeIdB = Base58.encode(identityB.nodeIdRaw)

        val receivedByB = Channel<Pair<String, ByteArray>>(capacity = 10)

        val (protocolA, adapterA) = buildProtocol(identityA, listOf(identityA, identityB), Channel(10))
        val (protocolB, adapterB) = buildProtocol(identityB, listOf(identityA, identityB), receivedByB)

        try {
            protocolA.start()
            protocolB.start()

            launch { adapterA.connectToDiscoveredPeer(nodeIdB, "127.0.0.1", adapterB.localPort) }
            withTimeout(5_000L) { while (!protocolA.hasSession(nodeIdB)) delay(50) }

            val plaintext = "hello bob via StatelessEnvelope".encodeToByteArray()
            val envelope = StatelessEnvelopeCodec.seal(
                sender = identityA,
                receiverIdRaw = identityB.nodeIdRaw,
                receiverEncKeyPublic = identityB.encKeyPublic,
                plaintext = plaintext,
                timestamp = 1_000_000L,
            )
            protocolA.send(nodeIdB, StatelessEnvelopeCodec.encode(envelope))

            val (sender, envelopeBytes) = withTimeout(5_000L) { receivedByB.receive() }
            assertEquals(nodeIdA, sender)

            val recovered = StatelessEnvelopeCodec.open(
                envelope = StatelessEnvelopeCodec.decode(envelopeBytes),
                receiver = identityB,
                contactLookup = { nodeIdRaw ->
                    if (identityA.nodeIdRaw.contentEquals(nodeIdRaw))
                        ContactInfo(identityA.sigKeyPublic, identityA.encKeyPublic)
                    else null
                },
            )
            assertContentEquals(plaintext, recovered)
        } finally {
            protocolA.stop()
            protocolB.stop()
        }
    }

    @Test
    fun `relay StatelessEnvelope A to C through B without direct session`() = runBlocking {
        val identityA = createIdentity()
        val identityB = createIdentity()
        val identityC = createIdentity()

        val nodeIdA = Base58.encode(identityA.nodeIdRaw)
        val nodeIdB = Base58.encode(identityB.nodeIdRaw)
        val nodeIdC = Base58.encode(identityC.nodeIdRaw)

        // A knows B; B knows A and C; C knows B. A and C have no direct session.
        val receivedByB = Channel<Pair<String, ByteArray>>(capacity = 10)
        val receivedByC = Channel<Pair<String, ByteArray>>(capacity = 10)

        val (protocolA, adapterA) = buildProtocol(identityA, listOf(identityA, identityB), Channel(10))
        val (protocolB, adapterB) = buildProtocol(identityB, listOf(identityA, identityB, identityC), receivedByB)
        val (protocolC, adapterC) = buildProtocol(identityC, listOf(identityB, identityC), receivedByC)

        try {
            protocolA.start()
            protocolB.start()
            protocolC.start()

            launch { adapterA.connectToDiscoveredPeer(nodeIdB, "127.0.0.1", adapterB.localPort) }
            launch { adapterB.connectToDiscoveredPeer(nodeIdC, "127.0.0.1", adapterC.localPort) }
            withTimeout(5_000L) {
                while (!protocolA.hasSession(nodeIdB) || !protocolB.hasSession(nodeIdC)) delay(50)
            }

            // Alice seals an envelope for Charlie and sends it to Bob.
            val plaintext = "hello charlie via relay".encodeToByteArray()
            val envelope = StatelessEnvelopeCodec.seal(
                sender = identityA,
                receiverIdRaw = identityC.nodeIdRaw,
                receiverEncKeyPublic = identityC.encKeyPublic,
                plaintext = plaintext,
                timestamp = 1_000_000L,
            )
            protocolA.send(nodeIdB, StatelessEnvelopeCodec.encode(envelope))

            // Bob receives the frame, reads receiverId for routing, and forwards to Charlie.
            val (fromA, receivedAtB) = withTimeout(5_000L) { receivedByB.receive() }
            assertEquals(nodeIdA, fromA, "Bob receives from Alice")

            val envelopeAtB = StatelessEnvelopeCodec.decode(receivedAtB)
            assertEquals(nodeIdC, envelopeAtB.receiverId, "Bob sees Charlie as receiver")
            assertEquals(nodeIdA, envelopeAtB.senderId, "Bob sees Alice as sender")

            protocolB.send(nodeIdC, StatelessEnvelopeCodec.encode(envelopeAtB))

            // Charlie receives the relayed envelope and decrypts it.
            val (fromB, receivedAtC) = withTimeout(5_000L) { receivedByC.receive() }
            assertEquals(nodeIdB, fromB, "Charlie receives from Bob (relay leg)")

            val recovered = StatelessEnvelopeCodec.open(
                envelope = StatelessEnvelopeCodec.decode(receivedAtC),
                receiver = identityC,
                contactLookup = { nodeIdRaw ->
                    if (identityA.nodeIdRaw.contentEquals(nodeIdRaw))
                        ContactInfo(identityA.sigKeyPublic, identityA.encKeyPublic)
                    else null
                },
            )
            assertContentEquals(plaintext, recovered, "Charlie decrypts Alice's relayed StatelessEnvelope")
        } finally {
            protocolA.stop()
            protocolB.stop()
            protocolC.stop()
        }
    }
}
