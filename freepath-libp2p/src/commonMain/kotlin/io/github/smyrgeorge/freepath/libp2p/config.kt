package io.github.smyrgeorge.freepath.libp2p


val defaultListenAddrs: String = listOf(
    "/ip4/0.0.0.0/tcp/0",
    "/ip4/0.0.0.0/udp/0/quic-v1",
    "/ip6/::/tcp/0",
).joinToString("\n")

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
