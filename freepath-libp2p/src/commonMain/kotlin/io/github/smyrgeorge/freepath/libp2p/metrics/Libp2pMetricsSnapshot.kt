package io.github.smyrgeorge.freepath.libp2p.metrics

data class Libp2pMetricsSnapshot(
    /** Multiaddr strings the node is currently listening on. */
    val listenAddresses: List<String> = emptyList(),
    /** libp2p PeerIds that are currently connected. */
    val connectedPeers: Set<String> = emptySet(),
    /** libp2p PeerIds confirmed by Identify. */
    val identifiedPeers: Set<String> = emptySet(),
    /** Maps freepath nodeId → "host:port" for peers currently visible via mDNS. */
    val mdnsPeers: Map<String, String> = emptyMap(),
    /** Relay PeerIds that are currently connected and identified. */
    val connectedRelays: Set<String> = emptySet(),
    /** Maps relay PeerId → RelayRegistration for relays we are registered with. */
    val registeredRelays: Map<String, RelayRegistration> = emptyMap(),
    /** Maps relay PeerId → error string for relays where registration failed. */
    val failedRelays: Map<String, String> = emptyMap(),
    /** Externally reachable multiaddrs confirmed by AutoNAT v2. */
    val externalAddresses: Set<String> = emptySet(),
    /** Inferred NAT reachability status from AutoNAT v2 probe results. */
    val natStatus: NatStatus = NatStatus.Unknown,
    /** UPnP port-mapping status on the local router. */
    val upnpStatus: UpnpStatus = UpnpStatus.Unknown,
    /** External addresses mapped via UPnP. */
    val upnpAddresses: Set<String> = emptySet(),
) {
    data class RelayRegistration(val namespace: String, val ttl: Long)

    enum class NatStatus { Unknown, Public, NAT }
    enum class UpnpStatus { Unknown, Unavailable, Active }
}
