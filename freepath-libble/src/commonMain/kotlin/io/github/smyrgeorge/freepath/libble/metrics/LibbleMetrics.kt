package io.github.smyrgeorge.freepath.libble.metrics

import io.github.smyrgeorge.freepath.libble.LibbleEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    fun close() {
        channel.close()
    }

    private fun reduce(s: LibbleMetricsSnapshot, event: LibbleEvent): LibbleMetricsSnapshot =
        when (event) {
            is LibbleEvent.PeripheralDiscovered -> s.copy(discoveredPeripherals = s.discoveredPeripherals + (event.peripheralId to event))
            is LibbleEvent.PeripheralExpired -> s.copy(
                discoveredPeripherals = s.discoveredPeripherals - event.peripheralId,
                connectedPeripherals = s.connectedPeripherals - event.peripheralId,
            )

            is LibbleEvent.PeripheralConnected -> s.copy(connectedPeripherals = s.connectedPeripherals + event.peripheralId)
            is LibbleEvent.PeripheralDisconnected -> s.copy(connectedPeripherals = s.connectedPeripherals - event.peripheralId)
            is LibbleEvent.ContactExchange -> s
        }
}
