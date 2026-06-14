package io.github.smyrgeorge.freepath.core.testing.cluster

/**
 * Resolves nodes for the shared actor factories.
 *
 * - `AppActor` / `ContactExchangeActor` are keyed by node id → [byNodeId].
 * - `SyncPeerActor` is keyed by `"ownerPeerId:remotePeerId"` → resolved by owner peerId via [byPeerId].
 *
 * The registry is fully populated before the actors that read it are created: node ids up front
 * (before any `ActorSystem.get`), and peerIds once a node has booted (before any `connect()`, which
 * is what triggers `SyncPeerActor` creation). Plain maps are therefore safe for this JVM harness.
 */
internal class NodeRegistry {
    private val nodesById = HashMap<String, TestNode>()
    private val nodesByPeerId = HashMap<String, TestNode>()

    fun put(node: TestNode) {
        nodesById[node.id] = node
    }

    fun bindPeerId(node: TestNode) {
        nodesByPeerId[node.peerId] = node
    }

    fun byNodeId(id: String): TestNode =
        nodesById[id] ?: error("No test node registered for actor key '$id'")

    fun byPeerId(peerId: String): TestNode =
        nodesByPeerId[peerId] ?: error("No test node registered for peerId '$peerId'")
}
