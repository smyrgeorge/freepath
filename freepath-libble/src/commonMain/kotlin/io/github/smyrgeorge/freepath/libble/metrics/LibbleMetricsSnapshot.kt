package io.github.smyrgeorge.freepath.libble.metrics

import io.github.smyrgeorge.freepath.libble.LibbleEvent

data class LibbleMetricsSnapshot(
    val discoveredPeripherals: Map<String, LibbleEvent.PeripheralDiscovered> = emptyMap(),
    val connectedPeripherals: Set<String> = emptySet(),
)
