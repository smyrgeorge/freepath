package io.github.smyrgeorge.freepath.core.messaging

import io.github.smyrgeorge.freepath.core.testing.util.awaitUntil
import io.github.smyrgeorge.freepath.core.testing.util.clusterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncounterMemoryTest {

    @Test
    fun `identifying a peer records a local encounter on both sides`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        assertNull(alice.encounterWith(bob), "no encounter should exist before connecting")

        cluster.connect(alice, bob)

        awaitUntil { alice.encounterWith(bob) != null }
        val enc = assertNotNull(alice.encounterWith(bob))
        assertTrue(enc.count >= 1, "encounter count should be at least 1")

        // The connection is mutual, so bob records alice too.
        awaitUntil { bob.encounterWith(alice) != null }
    }

    @Test
    fun `re-identifying a peer bumps the encounter count`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes

        cluster.connect(alice, bob)
        awaitUntil { (alice.encounterWith(bob)?.count ?: 0) >= 1 }
        val first = assertNotNull(alice.encounterWith(bob)).count

        // Drop and reconnect → another PeerIdentified → the count increases.
        cluster.disconnect(alice, bob)
        cluster.connect(alice, bob)

        awaitUntil { (alice.encounterWith(bob)?.count ?: 0) > first }
    }
}
