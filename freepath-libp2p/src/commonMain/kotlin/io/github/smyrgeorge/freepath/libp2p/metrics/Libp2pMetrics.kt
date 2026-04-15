package io.github.smyrgeorge.freepath.libp2p.metrics

import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

class Libp2pMetrics {
    private val channel = Channel<Libp2pEvent>(Channel.UNLIMITED)
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(Libp2pMetricsSnapshot())
    val value: StateFlow<Libp2pMetricsSnapshot> = _state.asStateFlow()

    init {
        scope.launch {
            channel.consumeAsFlow().collect { event ->
                _state.value = reduce(_state.value, event)
            }
        }
    }

    fun onEvent(event: Libp2pEvent) {
        channel.trySend(event)
    }

    fun close() {
        channel.close()
    }

    private fun reduce(s: Libp2pMetricsSnapshot, event: Libp2pEvent): Libp2pMetricsSnapshot =
        when (event) {
            is Libp2pEvent.NewListenAddr -> s.copy(listenAddresses = (s.listenAddresses + event.addr).distinct())
            is Libp2pEvent.PeerConnected -> s.copy(connectedPeers = s.connectedPeers + event.peerId)
            is Libp2pEvent.PeerDisconnected -> s.copy(
                connectedPeers = s.connectedPeers - event.peerId,
                identifiedPeers = s.identifiedPeers - event.peerId,
                connectedRelays = s.connectedRelays - event.peerId,
                registeredRelays = s.registeredRelays - event.peerId,
            )

            is Libp2pEvent.PeerIdentified -> s.copy(identifiedPeers = s.identifiedPeers + event.peerId)
            is Libp2pEvent.MdnsPeerDiscovered -> s.copy(mdnsPeers = s.mdnsPeers + (event.peerId to event.addr))
            is Libp2pEvent.MdnsPeerExpired -> s.copy(mdnsPeers = s.mdnsPeers - event.peerId)
            is Libp2pEvent.RelayConnected -> s.copy(
                connectedRelays = s.connectedRelays + event.relayPeerId,
                failedRelays = s.failedRelays - event.relayPeerId,
            )

            is Libp2pEvent.RelayRegistered -> s.copy(
                registeredRelays = s.registeredRelays + (event.relayPeerId to Libp2pMetricsSnapshot.RelayRegistration(
                    event.namespace,
                    event.ttl
                )),
                failedRelays = s.failedRelays - event.relayPeerId,
            )

            is Libp2pEvent.RelayRegistrationFailed -> s.copy(
                failedRelays = s.failedRelays + (event.relayPeerId to event.error),
                registeredRelays = s.registeredRelays - event.relayPeerId,
            )

            else -> s
        }
}
