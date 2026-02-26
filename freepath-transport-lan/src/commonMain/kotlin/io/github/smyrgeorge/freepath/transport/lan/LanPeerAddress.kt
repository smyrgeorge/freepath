package io.github.smyrgeorge.freepath.transport.lan

/**
 * Encodes and decodes the opaque `address` string exchanged between [LanLinkAdapter] and
 * its [io.github.smyrgeorge.freepath.transport.PeerDiscovery] implementation.
 *
 * Format: `"host:port"` where `host` is an IPv4 address or a bracket-enclosed IPv6 address
 * (e.g. `[::1]`), and `port` is the decimal TCP port number.
 * Using the last `:` as the separator makes parsing unambiguous for both address families.
 */
internal object LanPeerAddress {
    fun encode(host: String, port: Int): String = "$host:$port"

    fun decode(address: String): Pair<String, Int> {
        val host = address.substringBeforeLast(":")
        val port = address.substringAfterLast(":").toIntOrNull()
            ?: error("Invalid LAN peer address: \"$address\"")
        return host to port
    }
}
