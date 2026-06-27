package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.system.ActorSystem
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.db
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.tx
import io.github.smyrgeorge.freepath.core.testing.cluster.TestCluster
import io.github.smyrgeorge.freepath.core.testing.cluster.TestNode
import io.github.smyrgeorge.freepath.core.testing.util.awaitUntil
import io.github.smyrgeorge.freepath.core.testing.util.clusterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class RelayActorTest {

    private suspend fun relayActorOf(node: TestNode) =
        ActorSystem.get(RelayActor::class, RelayActor.key(node.peerId))

    /** Seed a single queued master replica on [from] addressed to the (offline) [to]. */
    private suspend fun seedQueuedReplica(cluster: TestCluster, from: TestNode, to: TestNode) {
        cluster.seedMutualContacts(from, to)
        from.sendMessage(to = to, text = "queued")
        awaitUntil { from.relayQueue().isNotEmpty() }
    }

    // The copy-budget primitives (reserve / delete) are internal to RelayActor now — they are no
    // longer protocol messages. The math itself lives in RelayService, so the unit tests below
    // exercise it there directly. The actor's serialization of these ops under concurrency is
    // covered end-to-end by the StoreAndForward* tests (e.g. concurrent fan-out conserving L).
    private suspend fun TestNode.reserveCopy(entryId: Int) =
        resources.relayService.tx { reserveCopy(entryId) }

    private suspend fun TestNode.deleteRelay(entryId: Int) =
        resources.relayService.db { deleteById(entryId) }

    @Test
    fun `key returns the owner peerId`() {
        val owner = "12D3KooWOwnerPeerId"
        assertEquals(owner, RelayActor.key(owner))
    }

    @Test
    fun `reserve halves the copy budget and then enters the wait phase`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        seedQueuedReplica(cluster, alice, bob)

        val entry = alice.relayQueue().single()
        assertEquals(8, entry.copies, "a fresh master replica starts with L=8 copies")

        // 8 → distribute 4 (keep 4) → distribute 2 (keep 2) → distribute 1 (keep 1) → wait phase (null).
        assertEquals(4, alice.reserveCopy(entry.id)?.copies)
        assertEquals(4, alice.relayQueue().single().copies)

        assertEquals(2, alice.reserveCopy(entry.id)?.copies)
        assertEquals(2, alice.relayQueue().single().copies)

        assertEquals(1, alice.reserveCopy(entry.id)?.copies)
        assertEquals(1, alice.relayQueue().single().copies)

        assertNull(alice.reserveCopy(entry.id), "the last copy is held for direct delivery (wait phase)")
        assertEquals(1, alice.relayQueue().single().copies, "the held copy is untouched")
    }

    @Test
    fun `reserve on a missing entry returns null`() = clusterTest { cluster ->
        val node = cluster.nodes.first()
        assertNull(node.reserveCopy(999_999))
    }

    @Test
    fun `relay to an unknown peer against an empty queue replies Ok`() = clusterTest { cluster ->
        val node = cluster.nodes.first()
        assertEquals(
            RelayProtocol.Ok,
            relayActorOf(node).ask(RelayProtocol.RelayToPeer("12D3KooWUnknownPeer")).getOrThrow(),
        )
    }

    @Test
    fun `distribute with no online peers reports zero and keeps the replica queued`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        seedQueuedReplica(cluster, alice, bob) // bob is offline → nothing reachable to distribute to

        val distributed = relayActorOf(alice).ask(RelayProtocol.Distribute).getOrThrow()
        assertEquals(0, distributed.peerCount, "no peers online → nothing distributed")
        assertEquals(1, alice.relayQueue().size, "the replica stays queued")
    }

    @Test
    fun `enqueue persists a master replica`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        seedQueuedReplica(cluster, alice, bob)
        val relay = relayActorOf(alice)

        // Remove the original, then re-enqueue a fresh row (id = 0 → INSERT; the message_id unique
        // index is free again now the original is gone).
        val entry = alice.relayQueue().single()
        alice.deleteRelay(entry.id)
        awaitUntil { alice.relayQueue().isEmpty() }

        val stored = relay.ask(RelayProtocol.Enqueue(entry.copy(id = 0))).getOrThrow().entry
        assertEquals(8, stored.copies)
        awaitUntil { alice.relayQueue().size == 1 }
    }

    @Test
    fun `sweep drops an expired replica but keeps fresh ones`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        seedQueuedReplica(cluster, alice, bob)
        val relay = relayActorOf(alice)

        // Re-enqueue the replica with an expiry in the past (the generated expires_at column is
        // derived from the envelope JSON, so the sweep query sees it as expired).
        val entry = alice.relayQueue().single()
        alice.deleteRelay(entry.id)
        awaitUntil { alice.relayQueue().isEmpty() }

        val expiredEnvelope = entry.envelope.copy(
            relay = entry.envelope.relay!!.copy(expiresAt = Instant.fromEpochMilliseconds(0)),
        )
        relay.ask(RelayProtocol.Enqueue(entry.copy(id = 0, envelope = expiredEnvelope))).getOrThrow()
        awaitUntil { alice.relayQueue().size == 1 }

        relay.ask(RelayProtocol.Sweep).getOrThrow()
        awaitUntil { alice.relayQueue().isEmpty() }
    }

    @Test
    fun `sweep keeps a fresh replica`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        seedQueuedReplica(cluster, alice, bob)

        relayActorOf(alice).ask(RelayProtocol.Sweep).getOrThrow()

        // The replica's default expiry is 7 days out, so the sweep must not touch it.
        assertEquals(alice.relayQueue().size, 1, "a fresh, unexpired replica should survive the sweep")
    }
}
