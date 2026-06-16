package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.system.ActorSystem
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

        val relay = relayActorOf(alice)

        // 8 → spray 4 (keep 4) → spray 2 (keep 2) → spray 1 (keep 1) → wait phase (null).
        assertEquals(4, relay.ask(RelayProtocol.ReserveSpray(entry.id)).getOrThrow().entry?.copies)
        assertEquals(4, alice.relayQueue().single().copies)

        assertEquals(2, relay.ask(RelayProtocol.ReserveSpray(entry.id)).getOrThrow().entry?.copies)
        assertEquals(2, alice.relayQueue().single().copies)

        assertEquals(1, relay.ask(RelayProtocol.ReserveSpray(entry.id)).getOrThrow().entry?.copies)
        assertEquals(1, alice.relayQueue().single().copies)

        assertNull(
            relay.ask(RelayProtocol.ReserveSpray(entry.id)).getOrThrow().entry,
            "the last copy is held for direct delivery (wait phase)",
        )
        assertEquals(1, alice.relayQueue().single().copies, "the held copy is untouched")
    }

    @Test
    fun `reserve on a missing entry returns null`() = clusterTest { cluster ->
        val node = cluster.nodes.first()
        val relay = relayActorOf(node)
        assertNull(relay.ask(RelayProtocol.ReserveSpray(999_999)).getOrThrow().entry)
    }

    @Test
    fun `delivered removes the entry from the queue`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        seedQueuedReplica(cluster, alice, bob)

        val entry = alice.relayQueue().single()
        relayActorOf(alice).ask(RelayProtocol.Delivered(entry.id)).getOrThrow()

        awaitUntil { alice.relayQueue().isEmpty() }
    }

    @Test
    fun `enqueue persists a master replica`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        seedQueuedReplica(cluster, alice, bob)
        val relay = relayActorOf(alice)

        // Remove the original, then re-enqueue a fresh row (id = 0 → INSERT; the message_id unique
        // index is free again now the original is gone).
        val entry = alice.relayQueue().single()
        relay.ask(RelayProtocol.Delivered(entry.id)).getOrThrow()
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
        relay.ask(RelayProtocol.Delivered(entry.id)).getOrThrow()
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
