package io.github.smyrgeorge.freepath.state.model

sealed class ConnectionSource {
    data object LAN : ConnectionSource()
    data object BLE : ConnectionSource()
    data object Internet : ConnectionSource()
}
