package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.actor4k.actor.Actor
import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import io.github.smyrgeorge.log4k.impl.extensions.doEvery
import io.github.smyrgeorge.log4k.impl.extensions.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class AppActor : Actor<Protocol, Protocol.Response>(KEY) {
    private val timer: Job = doEvery(5.seconds) {
        tell(Protocol.Ping) // Keep the actor alive.
    }

    override suspend fun onBeforeActivate() {
        tell(Protocol.Ping) // trigger activation.
    }

    override suspend fun onActivate(m: Protocol) {
        log.info("[onActivate] Activating... ($m)")
        val time = measureTime {
            AppResources.openDatabase()
            AppState.initialize()
            launch {
                log.info("[onActivate] Starting up LAN protocol in the background...")
                AppResources.startupLan()
            }
        }

        log.info("[onActivate] Identity: ${AppState.identityEntry}")
        log.info("[onActivate] ContactCard: ${AppState.contactCardEntry}")
        log.info("[onActivate] Initialization took $time")

        val route = when {
            ContactCardEntry.TAG_ONBOARDING in AppState.contactCardEntry.tags ->
                AppUiState.StartupRoute.Onboarding

            AppUiState.pendingDeepLink.value != null -> {
                AppUiState.clearPendingDeepLink()
                AppUiState.StartupRoute.Network
            }

            else -> AppUiState.StartupRoute.Nearby
        }

        if (time < 1.seconds) delay(1.seconds - time)
        AppUiState.setStartupRoute(route)
    }

    override suspend fun onReceive(m: Protocol): Behavior<Protocol.Response> {
        when (m) {
            is Protocol.Ping -> Behavior.Reply(Protocol.Pong)
            is Protocol.AcceptContact -> AppState.acceptContact(m.card)
            is Protocol.SetTrustLevel -> AppState.setTrustLevel(m.entry, m.level)
            is Protocol.PeerDiscovered -> AppState.peerDiscovered(m.nodeId)
            is Protocol.PeerLost -> AppState.peerLost(m.nodeId)
            is Protocol.PeerConnected -> AppState.peerConnected(m.nodeId)
            is Protocol.PeerDisconnected -> AppState.peerDisconnected(m.nodeId)
            is Protocol.AppForegrounded -> {
                log.info("[initialized] App foregrounded — restarting mDNS discovery.")
                AppResources.lanAdapter.restartDiscovery()
            }

            is Protocol.AppBackgrounded -> {
                log.info("[initialized] App backgrounded — stopping mDNS discovery.")
            }
        }

        return Behavior.Reply(Protocol.Ok)
    }

    override suspend fun onShutdown() {
        log.info("[onShutdown] Shutting down...")
        timer.cancel()
        AppResources.closeDatabase()
        AppResources.shutdownLan()
        log.info("[onShutdown] Shutdown complete.")
    }

    companion object {
        const val KEY = "system"
    }
}
