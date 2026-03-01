package io.github.smyrgeorge.freepath.transport.lan

import io.github.smyrgeorge.freepath.transport.PeerDiscovery
import io.github.smyrgeorge.freepath.transport.StatefulProtocol
import io.github.smyrgeorge.freepath.transport.codec.Base58
import io.github.smyrgeorge.freepath.transport.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.transport.model.LocalIdentity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class LanLinkAdapterTest {

    private fun createIdentity(): LocalIdentity {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val nodeIdRaw = CryptoProvider.sha256(sigKp.publicKey).copyOf(16)
        return LocalIdentity(nodeIdRaw, sigKp.publicKey, sigKp.privateKey, encKp.publicKey, encKp.privateKey)
    }

    private fun nodeIdString(identity: LocalIdentity): String =
        Base58.encode(identity.nodeIdRaw)

    private fun buildProtocol(
        identity: LocalIdentity,
        knownPeers: List<LocalIdentity>,
        received: Channel<Pair<String, ByteArray>>,
        peerDiscovery: PeerDiscovery,
    ): Pair<StatefulProtocol, LanLinkAdapter> {
        val knownNodeIds = knownPeers.map { nodeIdString(it) }.toSet()

        // protocol is a lateinit-style holder so the lambdas can reference it
        var protocol: StatefulProtocol? = null

        val linkAdapter = LanLinkAdapter(
            peerDiscovery = peerDiscovery,
            onPeerDisconnected = { peerId ->
                protocol?.closeSession(peerId)
            },
            isKnownPeer = { peerId -> peerId in knownNodeIds },
            onConnectionEstablished = { peerId ->
                protocol?.initiateHandshake(peerId)
            },
            onIdleTimeout = { peerId -> protocol?.closeSession(peerId) },
        )

        protocol = StatefulProtocol(
            identity = identity,
            contactLookup = { nodeIdBytes ->
                knownPeers.firstOrNull { it.nodeIdRaw.contentEquals(nodeIdBytes) }?.sigKeyPublic
            },
            linkAdapter = linkAdapter,
            onFrameReceived = { peerId, frame, _ ->
                val plaintext = kotlin.io.encoding.Base64.decode(frame.payload)
                received.send(Pair(peerId, plaintext))
            },
        )

        return Pair(protocol, linkAdapter)
    }

    // ---- tests ---------------------------------------------------------------

    @Test
    fun `message sent from A reaches both B and C`() = runBlocking {
        val identityA = createIdentity()
        val identityB = createIdentity()
        val identityC = createIdentity()
        val allIdentities = listOf(identityA, identityB, identityC)

        val receivedByA = Channel<Pair<String, ByteArray>>(capacity = 10)
        val receivedByB = Channel<Pair<String, ByteArray>>(capacity = 10)
        val receivedByC = Channel<Pair<String, ByteArray>>(capacity = 10)

        val discoveryA = InMemoryDiscovery(nodeIdString(identityA))
        val discoveryB = InMemoryDiscovery(nodeIdString(identityB))
        val discoveryC = InMemoryDiscovery(nodeIdString(identityC))

        val (protocolA, adapterA) = buildProtocol(identityA, allIdentities, receivedByA, discoveryA)
        val (protocolB, adapterB) = buildProtocol(identityB, allIdentities, receivedByB, discoveryB)
        val (protocolC, adapterC) = buildProtocol(identityC, allIdentities, receivedByC, discoveryC)

        try {
            protocolA.start()
            protocolB.start()
            protocolC.start()

            val nodeIdA = nodeIdString(identityA)
            val nodeIdB = nodeIdString(identityB)
            val nodeIdC = nodeIdString(identityC)

            // Simulate discovery: tell each adapter about every other adapter.
            discoveryA.register(nodeIdB, LanPeerAddress.encode("127.0.0.1", adapterB.localPort))
            discoveryA.register(nodeIdC, LanPeerAddress.encode("127.0.0.1", adapterC.localPort))
            discoveryB.register(nodeIdA, LanPeerAddress.encode("127.0.0.1", adapterA.localPort))
            discoveryB.register(nodeIdC, LanPeerAddress.encode("127.0.0.1", adapterC.localPort))
            discoveryC.register(nodeIdA, LanPeerAddress.encode("127.0.0.1", adapterA.localPort))
            discoveryC.register(nodeIdB, LanPeerAddress.encode("127.0.0.1", adapterB.localPort))

            // Wait until both sessions are established before sending.
            withTimeout(5_000) {
                while (!protocolA.hasSession(nodeIdB) || !protocolA.hasSession(nodeIdC)) delay(50)
            }

            // Send application messages
            val message = "hello freepath".encodeToByteArray()
            protocolA.send(nodeIdB, message)
            protocolA.send(nodeIdC, message)

            withTimeout(5_000) {
                val (peerB, payloadB) = receivedByB.receive()
                assertEquals(nodeIdA, peerB, "B should receive from A")
                assertEquals("hello freepath", payloadB.decodeToString(), "B's message should match")

                val (peerC, payloadC) = receivedByC.receive()
                assertEquals(nodeIdA, peerC, "C should receive from A")
                assertEquals("hello freepath", payloadC.decodeToString(), "C's message should match")
            }
        } finally {
            protocolA.stop()
            protocolB.stop()
            protocolC.stop()
        }
    }
}
