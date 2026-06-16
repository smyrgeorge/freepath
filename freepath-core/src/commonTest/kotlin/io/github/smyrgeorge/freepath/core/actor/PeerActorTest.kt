package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.system.ActorSystem
import io.github.smyrgeorge.freepath.core.testing.util.clusterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeerActorTest {

    private val unknownPeer = "12D3KooWUnknownRemotePeer"

    @Test
    fun `key round-trips owner and remote peerIds`() {
        val owner = "12D3KooWOwnerPeerId"
        val remote = "12D3KooWRemotePeerId"

        val key = PeerActor.key(owner, remote)

        assertEquals(owner, PeerActor.ownerPeerIdOf(key))
        assertEquals(remote, PeerActor.remotePeerIdOf(key))
    }

    @Test
    fun `connected to an unknown peer is a safe no-op`() = clusterTest { cluster ->
        val node = cluster.nodes.first()
        // No contact for the remote and an empty relay queue: Pass 1/2 find nothing and sync() hits
        // its "no contact entry" guard — the actor must still reply Ok and queue nothing.
        val ref = ActorSystem.get(PeerActor::class, PeerActor.key(node.peerId, unknownPeer))
        assertEquals(PeerProtocol.Ok, ref.ask(PeerProtocol.Connected).getOrThrow())
        assertTrue(node.relayQueue().isEmpty())
    }

    @Test
    fun `relay against an empty queue replies Ok`() = clusterTest { cluster ->
        val node = cluster.nodes.first()
        val ref = ActorSystem.get(PeerActor::class, PeerActor.key(node.peerId, unknownPeer))
        assertEquals(PeerProtocol.Ok, ref.ask(PeerProtocol.Relay).getOrThrow())
    }

    @Test
    fun `identified against an unknown peer still records an encounter`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        // PeerActor.Identified records the local encounter heuristic before sync/relay, even when
        // the peer is not (yet) a stored contact.
        val ref = ActorSystem.get(PeerActor::class, PeerActor.key(alice.peerId, bob.peerId))
        assertEquals(PeerProtocol.Ok, ref.ask(PeerProtocol.Identified).getOrThrow())
        assertEquals(bob.peerId, alice.encounterWith(bob)?.peerId)
    }
}
