package io.github.smyrgeorge.freepath.libp2p


val defaultListenAddrs: String = listOf(
    "/ip4/0.0.0.0/tcp/0",
    "/ip4/0.0.0.0/udp/0/quic-v1",
    "/ip6/::/tcp/0",
).joinToString("\n")

val defaultRelayAddrs: String = listOf(
    "/ip6/2a01:4f8:1c1a:1798::1/tcp/4001/p2p/12D3KooWKMwprgBKPMGA7mTgkhKp3UweX5qDBqVdnAjbpoAxnBX9",
    "/ip6/2a01:4f8:1c1a:1798::1/udp/4001/quic-v1/p2p/12D3KooWKMwprgBKPMGA7mTgkhKp3UweX5qDBqVdnAjbpoAxnBX9",
    "/ip4/46.224.91.96/tcp/4001/p2p/12D3KooWKMwprgBKPMGA7mTgkhKp3UweX5qDBqVdnAjbpoAxnBX9",
    "/ip4/46.224.91.96/udp/4001/quic-v1/p2p/12D3KooWKMwprgBKPMGA7mTgkhKp3UweX5qDBqVdnAjbpoAxnBX9",
).joinToString("\n")

/**
 * Parses [defaultRelayAddrs] into a map of PeerId → host extracted from the multiaddr.
 * e.g. "12D3Koo..." → "2a01:4f8:1c1a:1798::1"
 */
val relayPeerIdToHost: Map<String, String> by lazy {
    defaultRelayAddrs.lines()
        .mapNotNull { addr ->
            // Multiaddr format: /ip4/<host>/... or /ip6/<host>/... .../p2p/<peerId>
            val parts = addr.split("/").filter { it.isNotEmpty() }
            val hostIndex = parts.indexOfFirst { it == "ip4" || it == "ip6" }
            val p2pIndex = parts.indexOfFirst { it == "p2p" }
            if (hostIndex < 0 || p2pIndex < 0) return@mapNotNull null
            val host = parts.getOrNull(hostIndex + 1) ?: return@mapNotNull null
            val peerId = parts.getOrNull(p2pIndex + 1) ?: return@mapNotNull null
            peerId to host
        }
        .toMap()
}

/**
 * Converts a "host:port" LAN address to a libp2p multiaddr string.
 * Returns null for link-local IPv6 addresses (fe80::) — they require a scope ID
 * and cannot be dialled reliably across devices.
 */
internal fun lanAddressToMultiaddr(address: String): String? {
    val (host, port) = runCatching { LanPeerAddressCodec.decode(address) }.getOrNull()
        ?: return null
    val cleanHost = host.removeSurrounding("[", "]")
    return if (cleanHost.contains(":")) {
        // IPv6 — skip link-local addresses (fe80::)
        if (cleanHost.startsWith("fe80", ignoreCase = true)) return null
        "/ip6/$cleanHost/tcp/$port"
    } else {
        "/ip4/$cleanHost/tcp/$port"
    }
}
