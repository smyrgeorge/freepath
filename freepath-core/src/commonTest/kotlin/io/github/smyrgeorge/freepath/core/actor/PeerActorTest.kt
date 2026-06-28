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
        // No contact for the remote and an empty relay queue: the RelayActor pass finds nothing and
        // sync() hits its "no contact entry" guard — the actor must still reply Ok and queue nothing.
        val ref = ActorSystem.get(PeerActor::class, PeerActor.key(node.peerId, unknownPeer))
        assertEquals(PeerProtocol.Ok, ref.ask(PeerProtocol.Connected).getOrThrow())
        assertTrue(node.relayQueue().isEmpty())
    }

    @Test
    fun `SyncContact to an unreachable peer is a safe no-op`() = clusterTest { cluster ->
        val node = cluster.nodes.first()
        node.ensureOnboarded()
        // The remote is neither a contact nor connected, so the card send fails inside sync(); the
        // actor is best-effort/fire-and-forget, so it must still reply Ok rather than throw.
        val ref = ActorSystem.get(PeerActor::class, PeerActor.key(node.peerId, unknownPeer))
        val reply = ref.ask(PeerProtocol.SyncContact(node.state.contactContent)).getOrThrow()
        assertEquals(PeerProtocol.Ok, reply)
    }
}
