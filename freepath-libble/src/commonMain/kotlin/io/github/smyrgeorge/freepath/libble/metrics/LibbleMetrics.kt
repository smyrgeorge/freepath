package io.github.smyrgeorge.freepath.libble.metrics

import io.github.smyrgeorge.freepath.libble.LibbleEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

class LibbleMetrics {
    private val channel = Channel<LibbleEvent>(Channel.UNLIMITED)
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(LibbleMetricsSnapshot())
    val value: StateFlow<LibbleMetricsSnapshot> = _state.asStateFlow()

    init {
        scope.launch {
            channel.consumeAsFlow().collect { event ->
                _state.value = reduce(_state.value, event)
            }
        }
    }

    fun onEvent(event: LibbleEvent) {
        channel.trySend(event)
    }

    fun updateAdvertisedToken(tokenHex: String?) {
        _state.value = _state.value.copy(advertisedTokenHex = tokenHex)
    }

    fun close() {
        channel.close()
        scope.cancel()
    }

    private fun reduce(s: LibbleMetricsSnapshot, event: LibbleEvent): LibbleMetricsSnapshot =
        when (event) {
            is LibbleEvent.PeripheralDiscovered -> s.copy(discoveredPeripherals = s.discoveredPeripherals + (event.peripheralId to event))
            is LibbleEvent.PeripheralExpired -> s.copy(
                discoveredPeripherals = s.discoveredPeripherals - event.peripheralId,
                identifiedPeripherals = s.identifiedPeripherals - event.peripheralId,
            )
            is LibbleEvent.PeerIdentified -> s.copy(
                identifiedPeers = s.identifiedPeers + event.peerId,
                identifiedPeripherals = s.identifiedPeripherals + event.peripheralId,
            )
            is LibbleEvent.PeerLost -> s.copy(identifiedPeers = s.identifiedPeers - event.peerId)
            else -> s
        }
}
