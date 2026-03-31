package io.github.smyrgeorge.freepath.libble.metrics

import io.github.smyrgeorge.freepath.libble.LibbleEvent

data class LibbleMetricsSnapshot(
    val identifiedPeers: Set<String> = emptySet(),
    // Peripheral IDs (OS addresses/UUIDs) that have been matched to a known contact.
    val identifiedPeripherals: Set<String> = emptySet(),
    val discoveredPeripherals: Map<String, LibbleEvent.PeripheralDiscovered> = emptyMap(),
    /** Hex-encoded identity token currently being advertised (truncated to 10 chars for display), or null. */
    val advertisedTokenHex: String? = null,
)
