package io.github.smyrgeorge.freepath.core.messaging

import io.github.smyrgeorge.actor4k.system.ActorSystem
import io.github.smyrgeorge.freepath.core.actor.RelayActor
import io.github.smyrgeorge.freepath.core.actor.RelayProtocol
import io.github.smyrgeorge.freepath.core.testing.cluster.TestCluster
import io.github.smyrgeorge.freepath.core.testing.cluster.TestNode
import io.github.smyrgeorge.freepath.core.testing.util.awaitUntil
import io.github.smyrgeorge.freepath.core.testing.util.clusterTest
import io.github.smyrgeorge.freepath.database.MessageStatus
import io.github.smyrgeorge.freepath.database.RelayEntry
import io.github.smyrgeorge.freepath.libnet.client.model.RelayOptions
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StoreAndForwardCommunityTest {

    private companion object {
        const val COMMUNITY = 100
        val BOOT_TIMEOUT = 240.seconds
        val STEP_TIMEOUT = 60.seconds
    }

    /** True if this node currently holds a relay copy addressed to [recipient]. */
    private suspend fun TestNode.carries(recipient: TestNode): Boolean =
        relayQueue().any { it.isFor(recipient) }

    /** True if this relay entry is addressed to [recipient] (Pass-1 direct-delivery match). */
    private fun RelayEntry.isFor(recipient: TestNode): Boolean =
        envelope.receiverIdHash.contentEquals(recipient.identity.peerIdHash)

    /** Number of binary-distribute steps a budget of [copies] yields from a single source (floor(log2)). */
    private fun spreadDepth(copies: Int): Int {
        var c = copies
        var n = 0
        while (c > 1) {
            c /= 2; n++
        }
        return n
    }

    @Test
    fun `a message is delivered across a multi-relay path`() =
        clusterTest(nodes = COMMUNITY, timeout = BOOT_TIMEOUT) { cluster ->
            val alice = cluster.nodes[0]
            val (r1, r2, r3) = Triple(cluster.nodes[1], cluster.nodes[2], cluster.nodes[3])
            val bob = cluster.nodes.last()

            cluster.seedMutualContacts(alice, bob)   // alice can seal a copy; bob can verify it
            cluster.seedMutualContacts(r3, bob)      // the last carrier knows bob → Pass-1 delivery

            alice.sendMessage(to = bob, text = "across the mesh")
            awaitUntil { alice.relayQueue().any { it.isFor(bob) } }

            // Distribute the copy down a 3-relay chain (alice → r1 → r2 → r3), halving the budget each step.
            relayStep(cluster, alice, r1, bob)
            relayStep(cluster, r1, r2, bob)
            relayStep(cluster, r2, r3, bob)

            // r3 holds its last copy and finally meets bob, delivering it (the final step).
            cluster.connect(r3, bob)
            awaitUntil(timeout = STEP_TIMEOUT) {
                bob.chatWith(alice).any { it.message.body == "across the mesh" }
            }
            val received = bob.chatWith(alice).single { it.message.body == "across the mesh" }
            assertEquals(MessageStatus.RECEIVED, received.status)
            assertEquals(alice.peerId, received.senderId, "the inner sender is alice, not a relay")
        }

    @Test
    fun `a message spreads through a connected neighbourhood but not into the wider community`() =
        clusterTest(nodes = COMMUNITY, timeout = BOOT_TIMEOUT) { cluster ->
            val alice = cluster.nodes[0]
            val bob = cluster.nodes.last()                       // offline; only used so alice can seal
            val neighbourhood = cluster.nodes.subList(0, 8)      // alice + 7 connected peers
            val outsiders = cluster.nodes.subList(8, COMMUNITY)  // the other 92, incl. bob — never linked
            cluster.seedMutualContacts(alice, bob)

            alice.sendMessage(to = bob, text = "rumour")
            awaitUntil { alice.relayQueue().any { it.isFor(bob) } }

            // Wire a small connected neighbourhood rooted at alice (a few relay steps, some branching).
            cluster.connect(alice, cluster.nodes[1])
            cluster.connect(alice, cluster.nodes[2])
            cluster.connect(alice, cluster.nodes[3])
            cluster.connect(cluster.nodes[1], cluster.nodes[4])
            cluster.connect(cluster.nodes[1], cluster.nodes[5])
            cluster.connect(cluster.nodes[2], cluster.nodes[6])
            cluster.connect(cluster.nodes[3], cluster.nodes[7])

            // The rumour spreads to several peers in the neighbourhood …
            awaitUntil(timeout = STEP_TIMEOUT) {
                neighbourhood.count { n -> n.carries(bob) } >= 4
            }
            // … and is bounded: it never escapes into the 92 unconnected community members.
            val leaked = outsiders.count { n -> n.carries(bob) }
            assertEquals(0, leaked, "the rumour must stay within the connected neighbourhood")
        }

    @Test
    fun `a mobile carrier ferries the message to a peer it meets later`() =
        clusterTest(nodes = COMMUNITY, timeout = BOOT_TIMEOUT) { cluster ->
            val alice = cluster.nodes[0]
            val ferry = cluster.nodes[42]      // a community member that knows bob
            val bob = cluster.nodes.last()
            cluster.seedMutualContacts(alice, bob)
            cluster.seedMutualContacts(ferry, bob)   // the ferry can deliver to bob on contact

            // alice never meets bob. She hands a copy to the ferry while they are briefly together…
            alice.sendMessage(to = bob, text = "carry this to bob")
            awaitUntil { alice.relayQueue().any { it.isFor(bob) } }

            cluster.connect(alice, ferry)
            awaitUntil(timeout = STEP_TIMEOUT) { ferry.carries(bob) }
            cluster.disconnect(alice, ferry)         // alice and the ferry part ways

            // …then the ferry moves on and later runs into bob, delivering it.
            cluster.connect(ferry, bob)
            awaitUntil(timeout = STEP_TIMEOUT) {
                bob.chatWith(alice).any { it.message.body == "carry this to bob" }
            }
            assertEquals(bob.chatWith(alice).single { it.message.body == "carry this to bob" }.senderId, alice.peerId)
        }

    @Test
    fun `concurrent fan-out never hands out more than the copy budget`() =
        clusterTest(nodes = COMMUNITY, timeout = BOOT_TIMEOUT) { cluster ->
            val alice = cluster.nodes[0]
            val bob = cluster.nodes.last()
            val candidates = cluster.nodes.subList(1, 21)   // 20 peers rush alice at the same time
            cluster.seedMutualContacts(alice, bob)

            alice.sendMessage(to = bob, text = "stampede")
            awaitUntil { alice.relayQueue().any { it.isFor(bob) } }

            // Worst case for the shared copy counter: everyone connects to alice concurrently, so all
            // of alice's per-peer PeerActors try to distribute the same replica at once.
            coroutineScope { candidates.forEach { peer -> launch { cluster.connect(alice, peer) } } }

            // Binary distribute-and-wait reserves copies atomically, so regardless of concurrency it hands
            // out exactly the budget: with L = 8 the copy reaches exactly log2(L) = 3 peers (4 + 2 + 1)
            // and the sender keeps the last one. Before the race fix this leaked to many more peers.
            val expectedCarriers = spreadDepth(RelayOptions.DEFAULT_COPIES)   // 3 for L = 8
            awaitUntil(timeout = STEP_TIMEOUT) { candidates.count { it.carries(bob) } >= expectedCarriers }
            assertEquals(
                expectedCarriers,
                candidates.count { it.carries(bob) },
                "concurrent fan-out must not leak copies beyond the budget L",
            )
            // …and the sender retains exactly one copy (wait phase) — the budget is conserved.
            assertEquals(1, alice.relayQueue().single { it.isFor(bob) }.copies)
        }

    @Test
    fun `re-running the relay pass to a carrier does not re-distribute the same replica`() =
        clusterTest(nodes = COMMUNITY, timeout = BOOT_TIMEOUT) { cluster ->
            val alice = cluster.nodes[0]
            val carol = cluster.nodes[50]
            val bob = cluster.nodes.last()
            cluster.seedMutualContacts(alice, bob)

            alice.sendMessage(to = bob, text = "once is enough")
            awaitUntil { alice.relayQueue().any { it.isFor(bob) } }

            // First encounter: alice hands carol a halved copy (8 → keep 4, send 4).
            cluster.connect(alice, carol)
            awaitUntil(timeout = STEP_TIMEOUT) { carol.carries(bob) }
            val aliceCopies = alice.relayQueue().single { it.isFor(bob) }.copies
            val carolCopies = carol.relayQueue().single { it.isFor(bob) }.copies

            // Re-run the relay pass to the same peer many times. The durable (DB-backed) offered set
            // must make every pass a no-op — no further reservation — so neither budget shrinks.
            // Without dedup, alice would keep re-halving 4 → 2 → 1 on each re-encounter.
            val aliceRelay = ActorSystem.get(RelayActor::class, RelayActor.key(alice.peerId))
            repeat(5) { aliceRelay.ask(RelayProtocol.RelayToPeer(carol.peerId)).getOrThrow() }

            assertEquals(
                aliceCopies,
                alice.relayQueue().single { it.isFor(bob) }.copies,
                "the sender must not re-distribute a replica it already offered this peer",
            )
            assertEquals(
                carolCopies,
                carol.relayQueue().single { it.isFor(bob) }.copies,
                "the carrier must not be re-offered (and must hold a single replica)",
            )
            assertEquals(1, carol.relayQueue().count { it.isFor(bob) }, "no duplicate replica on the carrier")
        }

    @Test
    fun `the message is stored once even when several carriers deliver it to the recipient`() =
        clusterTest(nodes = COMMUNITY, timeout = BOOT_TIMEOUT) { cluster ->
            val alice = cluster.nodes[0]
            val c1 = cluster.nodes[10]
            val c2 = cluster.nodes[20]
            val bob = cluster.nodes.last()
            cluster.seedMutualContacts(alice, bob)
            cluster.seedMutualContacts(c1, bob)   // both carriers know bob → Pass-1 delivery
            cluster.seedMutualContacts(c2, bob)

            alice.sendMessage(to = bob, text = "deliver me once")
            awaitUntil { alice.relayQueue().any { it.isFor(bob) } }

            // Two carriers each pick up a copy from alice.
            cluster.connect(alice, c1)
            awaitUntil(timeout = STEP_TIMEOUT) { c1.carries(bob) }
            cluster.connect(alice, c2)
            awaitUntil(timeout = STEP_TIMEOUT) { c2.carries(bob) }

            // Both meet bob and deliver; each drops its copy on a successful hand-off.
            cluster.connect(c1, bob)
            cluster.connect(c2, bob)
            awaitUntil(timeout = STEP_TIMEOUT) { !c1.carries(bob) && !c2.carries(bob) }

            // The message_id unique index makes the second delivery a no-op: bob stores it exactly once.
            assertEquals(
                1,
                bob.chatWith(alice).count { it.message.body == "deliver me once" },
                "the recipient must store the message exactly once, regardless of carrier count",
            )
        }

    @Test
    fun `independent messages each carry their own copy budget`() =
        clusterTest(nodes = COMMUNITY, timeout = BOOT_TIMEOUT) { cluster ->
            val alice = cluster.nodes[0]
            val carol = cluster.nodes[30]
            val bob = cluster.nodes.last()
            cluster.seedMutualContacts(alice, bob)

            val texts = listOf("m1", "m2", "m3")
            texts.forEach { alice.sendMessage(to = bob, text = it) }
            awaitUntil { alice.relayQueue().count { it.isFor(bob) } == texts.size }
            // Each freshly-queued message starts at the full budget L.
            assertTrue(
                alice.relayQueue().filter { it.isFor(bob) }.all { it.copies == RelayOptions.DEFAULT_COPIES },
                "every queued message starts at L copies",
            )

            // A single encounter distributes a halved copy of every queued message, independently.
            cluster.connect(alice, carol)
            awaitUntil(timeout = STEP_TIMEOUT) { carol.relayQueue().count { it.isFor(bob) } == texts.size }

            val half = RelayOptions.DEFAULT_COPIES / 2
            assertTrue(
                alice.relayQueue().filter { it.isFor(bob) }.all { it.copies == half },
                "the sender keeps half of each message's budget",
            )
            assertTrue(
                carol.relayQueue().filter { it.isFor(bob) }.all { it.copies == half },
                "the carrier receives half of each message's budget",
            )
        }

    /** Distribute [from]'s copy of the message-for-[recipient] to [to], then wait until [to] has it. */
    private suspend fun relayStep(cluster: TestCluster, from: TestNode, to: TestNode, recipient: TestNode) {
        cluster.connect(from, to)
        awaitUntil(timeout = STEP_TIMEOUT) { to.carries(recipient) }
    }
}
