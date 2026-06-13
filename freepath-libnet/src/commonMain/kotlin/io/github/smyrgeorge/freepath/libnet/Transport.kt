package io.github.smyrgeorge.freepath.libnet

import io.github.smyrgeorge.freepath.util.Platform
import io.github.smyrgeorge.freepath.util.currentPlatform

enum class Transport(
    val enabled: Boolean,
    val mtu: Int,
    val platforms: Set<Platform>,
    val supported: Boolean = enabled && currentPlatform in platforms,
) {
    LIBBLE(
        enabled = false,
        mtu = 65_536, // matches BleFrame.MAX_PAYLOAD_SIZE (BLE L2CAP CoC hard limit)
        platforms = setOf(
            Platform.ANDROID,
            Platform.IOS
        ),
    ),
    LIBP2P(
        enabled = true,
        mtu = 262_144, // 256 KB — ~20 frames per 5 MB
        platforms = setOf(
            Platform.ANDROID,
            Platform.IOS,
            Platform.IOS_SIMULATOR,
            Platform.JVM
        ),
    ),
}
