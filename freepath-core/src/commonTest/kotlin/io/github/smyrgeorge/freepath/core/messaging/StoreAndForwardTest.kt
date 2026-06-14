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
 * Both direct delivery and store-and-forward surface as [MessageStatus.SENT] on the sender: here
 * "sent" means "handed off" — acked by the recipient directly, or queued in the relay mailbox for
 * the mesh to carry. The mesh is fire-and-forget (no end-to-end delivery receipt).
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
            alice.chatWith(bob).any { it.message.body == "into the void" && it.status == MessageStatus.SENT }
        }
        assertTrue(alice.relayQueue().isNotEmpty(), "the message should be queued for store-and-forward")
        assertTrue(bob.chatWith(alice).isEmpty(), "bob should not have received anything")
    }

    @Test
    fun `queued message is delivered when the peer reconnects`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)
        // bob is offline when the message is sent, so it is queued for relay (and marked SENT)…
        alice.sendMessage(to = bob, text = "deferred hello")
        awaitUntil {
            alice.chatWith(bob).any { it.message.body == "deferred hello" && it.status == MessageStatus.SENT }
        }
        assertTrue(alice.relayQueue().isNotEmpty())

        // …then bob comes online and the queue is flushed to him on connect.
        cluster.connect(alice, bob)

        awaitUntil { bob.chatWith(alice).any { it.message.body == "deferred hello" } }
        awaitUntil { alice.relayQueue().isEmpty() } // delivered → removed from the queue

        val received = bob.chatWith(alice).single { it.message.body == "deferred hello" }
        assertEquals(MessageStatus.RECEIVED, received.status)
        assertEquals(alice.peerId, received.senderId)

        // "sent" already meant "queued for relay"; it stays SENT after the mesh delivers it.
        val sent = alice.chatWith(bob).single { it.message.body == "deferred hello" }
        assertEquals(MessageStatus.SENT, sent.status)
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
