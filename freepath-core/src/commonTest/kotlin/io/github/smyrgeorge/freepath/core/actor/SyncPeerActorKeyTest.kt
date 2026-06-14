package io.github.smyrgeorge.freepath.core.actor

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sample test exercising the [SyncPeerActor] key namespacing seam: a key encodes both the owning
 * node's peerId and the remote peerId, and the two extractors round-trip it back.
 */
class SyncPeerActorKeyTest {

    @Test
    fun `key round-trips owner and remote peerIds`() {
        val owner = "12D3KooWOwnerPeerId"
        val remote = "12D3KooWRemotePeerId"

        val key = SyncPeerActor.key(owner, remote)

        assertEquals(owner, SyncPeerActor.ownerPeerIdOf(key))
        assertEquals(remote, SyncPeerActor.remotePeerIdOf(key))
    }
}
