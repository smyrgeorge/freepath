package io.github.smyrgeorge.freepath.libp2p.metrics

/**
 * Point-in-time snapshot of all libp2p metrics.
 */
data class Libp2pMetricsSnapshot(
    /** Multiaddr strings the node is currently listening on. */
    val listenAddresses: List<String> = emptyList(),
    /** libp2p PeerIds that are currently connected. */
    val connectedPeers: Set<String> = emptySet(),
    /** libp2p PeerIds confirmed by Identify. */
    val identifiedPeers: Set<String> = emptySet(),
    /** Maps freepath nodeId → "host:port" for peers currently visible via mDNS. */
    val mdnsPeers: Map<String, String> = emptyMap(),
)
