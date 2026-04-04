package io.github.smyrgeorge.freepath.libnet

enum class Transport(val mtu: Int) {
    LIBBLE(mtu = 65_536),       // matches BleFrame.MAX_PAYLOAD_SIZE (BLE L2CAP CoC hard limit)
    LIBP2P(mtu = 262_144),      // 256 KB — ~20 frames per 5 MB, smooth progress bar
}
