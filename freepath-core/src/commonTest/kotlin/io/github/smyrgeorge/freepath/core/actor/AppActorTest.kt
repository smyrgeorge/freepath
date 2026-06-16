package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.freepath.core.state.model.StartupRoute
import io.github.smyrgeorge.freepath.core.testing.util.clusterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppActorTest {

    @Test
    fun `responds to Ping with Pong`() = clusterTest { cluster ->
        val node = cluster.nodes.first()
        assertEquals(AppProtocol.Pong, node.appRef.ask(AppProtocol.Ping).getOrThrow())
    }

    @Test
    fun `activation leaves the loading route`() = clusterTest { cluster ->
        // TestCluster.start already waits for activation to finish; assert the actor moved the UI
        // off the initial loading route as part of onActivate.
        val node = cluster.nodes.first()
        assertTrue(node.viewState.startupRoute.value != StartupRoute.Loading)
    }

    @Test
    fun `accepts a peer-connected event with no live connection`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        // No real connection: AppActor just nudges the per-peer PeerActor, whose relay/sync pass is
        // a harmless no-op against an empty queue. The event must still be accepted.
        assertEquals(AppProtocol.Ok, alice.appRef.ask(AppProtocol.PeerConnected(bob.peerId)).getOrThrow())
    }

    @Test
    fun `accepts a peer-identified event with no live connection`() = clusterTest { cluster ->
        val (alice, bob) = cluster.nodes
        assertEquals(AppProtocol.Ok, alice.appRef.ask(AppProtocol.PeerIdentified(bob.peerId)).getOrThrow())
    }
}
