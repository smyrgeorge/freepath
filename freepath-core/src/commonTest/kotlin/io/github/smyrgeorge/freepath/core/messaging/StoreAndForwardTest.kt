package io.github.smyrgeorge.freepath.core.messaging

import io.github.smyrgeorge.freepath.core.testing.cluster.TestNode
import io.github.smyrgeorge.freepath.core.testing.util.awaitUntil
import io.github.smyrgeorge.freepath.core.testing.util.clusterTest
import io.github.smyrgeorge.freepath.database.MessageStatus
import io.github.smyrgeorge.freepath.database.RelayEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoreAndForwardTest {

    /** True if this relay entry is addressed to [recipient] (Pass-1 direct-delivery match). */
    private fun RelayEntry.isFor(recipient: TestNode): Boolean =
        envelope.receiverIdHash.contentEquals(recipient.identity.peerIdHash)

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

    @Test
    fun `a message queued while a relay peer is online is RELAYED`() = clusterTest(nodes = 3) { cluster ->
        val (alice, bob, carol) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)   // alice can seal a relay copy for bob
        cluster.connect(alice, carol)            // a relay is online; bob is not

        alice.sendMessage(to = bob, text = "relay me")

        // Direct delivery to bob fails (offline); the copy is handed to the mesh → RELAYED.
        awaitUntil {
            alice.chatWith(bob).any { it.message.body == "relay me" && it.status == MessageStatus.RELAYED }
        }
        // …and it is actually sprayed to the online relay.
        awaitUntil { carol.relayQueue().any { it.isFor(bob) } }
    }

    @Test
    fun `spraying to a relay halves the sender's copy budget`() =
        clusterTest(nodes = 3) { cluster ->
            val (alice, bob, carol) = cluster.nodes
            cluster.seedMutualContacts(alice, bob)
            // bob stays offline; carol is an intermediate mesh relay.

            alice.sendMessage(to = bob, text = "spray me")
            awaitUntil { alice.relayQueue().any { it.isFor(bob) } }
            val master = alice.relayQueue().single { it.isFor(bob) }
            assertEquals(8, master.copies, "a fresh relay copy starts with L copies")

            cluster.connect(alice, carol)

            // carol receives a sprayed copy with a halved copy budget.
            awaitUntil { carol.relayQueue().any { it.isFor(bob) } }
            val sprayed = carol.relayQueue().first { it.isFor(bob) }
            assertTrue(sprayed.copies in 1..4, "expected a halved copy budget, got ${sprayed.copies}")

            // alice handed off half its budget and keeps the rest (single relay → deterministic).
            awaitUntil { alice.relayQueue().singleOrNull { it.isFor(bob) }?.copies == 4 }
        }
}
