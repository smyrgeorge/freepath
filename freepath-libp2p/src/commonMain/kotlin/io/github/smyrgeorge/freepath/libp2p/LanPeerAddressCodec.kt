package io.github.smyrgeorge.freepath.libp2p

internal object LanPeerAddressCodec {
    fun encode(host: String, port: Int): String = "$host:$port"
    fun decode(address: String): Pair<String, Int> {
        val host = address.substringBeforeLast(":")
        val port = address.substringAfterLast(":").toIntOrNull()
            ?: error("Invalid LAN peer address: \"$address\"")
        return host to port
    }
}
