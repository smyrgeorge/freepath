package io.github.smyrgeorge.freepath.core.messaging

import io.github.smyrgeorge.freepath.core.testing.util.awaitUntil
import io.github.smyrgeorge.freepath.core.testing.util.clusterTest
import io.github.smyrgeorge.freepath.database.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises freepath-core's chat messaging exchange end-to-end: real actors, services,
 * `LibnetClient` framing and per-node in-memory databases, with only the physical transport faked
 * by the in-process network.
 *
 * Chat `send` is direct-only (no relay metadata), so this suite covers direct delivery. Mesh
 * store-and-forward (the relay queue + `SyncPeerActor.relay`) is a separate subsystem and deserves
 * its own suite.
 *
 * The cluster framework is JVM-only (in-memory SQLite + `LIBP2P` supported / `LIBBLE` not), so each
 * test no-ops on non-JVM targets that also compile this common source set.
 */
class MessageExchangeTest {

    @Test
    fun `direct message is delivered to a connected contact`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)
        cluster.connect(alice, bob)

        alice.sendMessage(to = bob, text = "hello bob")

        awaitUntil { bob.chatWith(alice).any { it.message.body == "hello bob" } }
        val received = bob.chatWith(alice).single { it.message.body == "hello bob" }
        assertEquals(MessageStatus.RECEIVED, received.status)
        assertEquals(alice.peerId, received.senderId)
        assertEquals(bob.peerId, received.recipientId)
    }

    @Test
    fun `sent message transitions to SENT once delivered`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)
        cluster.connect(alice, bob)

        alice.sendMessage(to = bob, text = "ack me")

        awaitUntil {
            alice.chatWith(bob).any { it.message.body == "ack me" && it.status == MessageStatus.SENT }
        }
    }

    @Test
    fun `messaging is bidirectional`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)
        cluster.connect(alice, bob)

        alice.sendMessage(to = bob, text = "ping")
        bob.sendMessage(to = alice, text = "pong")

        awaitUntil { bob.chatWith(alice).any { it.message.body == "ping" } }
        awaitUntil { alice.chatWith(bob).any { it.message.body == "pong" } }
    }

    @Test
    fun `message to an unreachable peer is marked FAILED`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)
        // intentionally NOT connected — bob is unreachable

        alice.sendMessage(to = bob, text = "into the void")

        awaitUntil {
            alice.chatWith(bob).any { it.message.body == "into the void" && it.status == MessageStatus.FAILED }
        }
        assertTrue(bob.chatWith(alice).isEmpty(), "bob should not have received anything")
    }

    @Test
    fun `all of several messages are delivered`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)
        cluster.connect(alice, bob)

        val texts = (1..5).map { "msg-$it" }
        texts.forEach { alice.sendMessage(to = bob, text = it) }

        awaitUntil { bob.chatWith(alice).mapNotNull { it.message.body }.toSet() == texts.toSet() }
    }

    @Test
    fun `message is delivered only to the addressed peer`() = clusterTest(nodes = 3) { cluster ->
        val (alice, bob, carol) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)
        cluster.seedMutualContacts(alice, carol)
        cluster.connect(alice, bob)
        cluster.connect(alice, carol)

        alice.sendMessage(to = bob, text = "for bob only")

        awaitUntil { bob.chatWith(alice).any { it.message.body == "for bob only" } }
        assertTrue(carol.chatWith(alice).none { it.message.body == "for bob only" })
    }
}
