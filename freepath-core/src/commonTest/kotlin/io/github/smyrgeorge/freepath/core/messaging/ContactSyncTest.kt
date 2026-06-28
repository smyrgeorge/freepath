package io.github.smyrgeorge.freepath.core.messaging

import io.github.smyrgeorge.freepath.core.testing.util.awaitUntil
import io.github.smyrgeorge.freepath.core.testing.util.clusterTest
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * End-to-end coverage for live contact-card propagation: when a node edits its own card (today only
 * the avatar), [io.github.smyrgeorge.freepath.core.state.AbstractAppState.updateOwnAvatar] fans a
 * [io.github.smyrgeorge.freepath.core.actor.PeerProtocol.SyncContact] out to every online contact's
 * PeerActor, which pushes the fresh card over the network.
 */
class ContactSyncTest {

    @Test
    fun `avatar update propagates to an online contact`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)
        cluster.connect(alice, bob)

        // Precondition: bob knows alice but she onboarded without an avatar.
        assertNull(bob.avatarOf(alice))

        alice.updateAvatar("avatar-xyz")

        // The edit bumps the card version and pushes it to bob (online) without a reconnect.
        awaitUntil { bob.avatarOf(alice) == "avatar-xyz" }
    }

    @Test
    fun `avatar update is not pushed to an offline contact`() = clusterTest(nodes = 3) { cluster ->
        val (alice, bob, carol) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)
        cluster.seedMutualContacts(alice, carol)
        cluster.connect(alice, bob) // carol is a contact but stays offline

        alice.updateAvatar("avatar-online-only")

        // bob (online) converges; assert it first so the push has demonstrably run...
        awaitUntil { bob.avatarOf(alice) == "avatar-online-only" }
        // ...carol (offline) was never sent the push and still has no avatar for alice.
        assertNull(carol.avatarOf(alice))
    }

    @Test
    fun `a later avatar update overwrites an earlier one for an online contact`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        cluster.seedMutualContacts(alice, bob)
        cluster.connect(alice, bob)

        alice.updateAvatar("avatar-v1")
        awaitUntil { bob.avatarOf(alice) == "avatar-v1" }

        alice.updateAvatar("avatar-v2")
        awaitUntil { bob.avatarOf(alice) == "avatar-v2" }
    }
}
