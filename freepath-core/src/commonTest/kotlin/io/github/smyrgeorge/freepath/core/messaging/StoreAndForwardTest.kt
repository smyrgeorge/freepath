package io.github.smyrgeorge.freepath.core.messaging

import io.github.smyrgeorge.freepath.core.testing.util.awaitUntil
import io.github.smyrgeorge.freepath.core.testing.util.clusterTest
import io.github.smyrgeorge.freepath.database.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises mesh store-and-forward: when a chat `send` can't reach the recipient directly, the
 * sender seals a relay copy into its own relay queue and the existing `SyncPeerActor.relay` path
 * carries it — directly once the peer reconnects, or across an intermediate relay node.
 *
 * Sender-side status distinguishes how far a message got: [MessageStatus.SENT] only on a direct
 * ack from the recipient; [MessageStatus.RELAYED] when the relay copy is handed to an online peer;
 * [MessageStatus.QUEUED] when it is stored but no peer is reachable yet. The mesh is fire-and-forget
 * (no end-to-end receipt), so a QUEUED message that the mesh later delivers stays QUEUED on the
 * sender — there is no signal to promote it (a live QUEUED→RELAYED transition is future work).
 *
 * Like [MessageExchangeTest], the cluster framework is JVM-only, so each test no-ops on the other
 * targets that also compile this common source set.
 */
class StoreAndForwardTest {

    @Test
    fun `message to an unreachable peer is queued for relay`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)
        // intentionally NOT connected — bob is unreachable

        alice.sendMessage(to = bob, text = "into the void")

        awaitUntil {
            alice.chatWith(bob).any { it.message.body == "into the void" && it.status == MessageStatus.QUEUED }
        }
        assertTrue(alice.relayQueue().isNotEmpty(), "the message should be queued for store-and-forward")
        assertTrue(bob.chatWith(alice).isEmpty(), "bob should not have received anything")
    }

    @Test
    fun `queued message is delivered when the peer reconnects`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)
        // bob is offline when the message is sent, so it is stored for relay and marked QUEUED…
        alice.sendMessage(to = bob, text = "deferred hello")
        awaitUntil {
            alice.chatWith(bob).any { it.message.body == "deferred hello" && it.status == MessageStatus.QUEUED }
        }
        assertTrue(alice.relayQueue().isNotEmpty())

        // …then bob comes online and the queue is flushed to him on connect.
        cluster.connect(alice, bob)

        awaitUntil { bob.chatWith(alice).any { it.message.body == "deferred hello" } }
        awaitUntil { alice.relayQueue().isEmpty() } // delivered → removed from the queue

        val received = bob.chatWith(alice).single { it.message.body == "deferred hello" }
        assertEquals(MessageStatus.RECEIVED, received.status)
        assertEquals(alice.peerId, received.senderId)

        // The mesh is fire-and-forget: there is no receipt back to alice, so her copy stays QUEUED
        // even though bob received it. Promoting QUEUED→RELAYED/SENT on delivery is future work.
        val sent = alice.chatWith(bob).single { it.message.body == "deferred hello" }
        assertEquals(MessageStatus.QUEUED, sent.status)
    }

    @Test
    fun `queued message is delivered across a relay hop`() = clusterTest(nodes = 3) { cluster ->
        val (alice, bob, carol) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)  // alice can seal to bob; bob can verify alice's signature
        cluster.seedMutualContacts(carol, bob)  // carol (the relay) knows bob and can deliver to him
        // alice and carol are NOT mutual contacts, and alice never connects to bob directly.

        alice.sendMessage(to = bob, text = "via the mesh")
        awaitUntil { alice.relayQueue().isNotEmpty() } // queued on alice

        // Hop 1: alice forwards the (undeliverable-to-her) packet to carol as a mesh hop.
        cluster.connect(alice, carol)
        awaitUntil {
            carol.relayQueue().any { it.envelope.receiverIdHash.contentEquals(bob.identity.peerIdHash) }
        }

        // Hop 2: carol, who knows bob, delivers it on connect.
        cluster.connect(carol, bob)
        awaitUntil { bob.chatWith(alice).any { it.message.body == "via the mesh" } }

        val received = bob.chatWith(alice).single { it.message.body == "via the mesh" }
        assertEquals(MessageStatus.RECEIVED, received.status)
        assertEquals(alice.peerId, received.senderId, "the inner sender is alice, not the relay")
    }

    @Test
    fun `message with no contact card is marked FAILED`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        // No seedMutualContacts: alice has no contact card for bob, so the message can be neither
        // encrypted for a direct send nor sealed into a relay copy — the surviving FAILED path.
        alice.sendMessage(to = bob, text = "no card")

        awaitUntil {
            alice.chatWith(bob).any { it.message.body == "no card" && it.status == MessageStatus.FAILED }
        }
        assertTrue(alice.relayQueue().isEmpty(), "nothing should be queued without a contact card")
        assertTrue(bob.chatWith(alice).isEmpty(), "bob should not have received anything")
    }
}
