package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.actor4k.actor.impl.BehaviorActor
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import io.github.smyrgeorge.freepath.util.exitApplication
import io.github.smyrgeorge.log4k.impl.extensions.doEvery
import io.github.smyrgeorge.log4k.impl.extensions.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class AppActor : BehaviorActor<Protocol, Protocol.Response>(KEY) {
    internal val exchangeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
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
        become(normal)
    }

    override suspend fun onShutdown() {
        log.info("[onShutdown] Shutting down...")
        timer.cancel()
        exchangeScope.cancel()
        AppResources.closeDatabase()
        AppResources.shutdownLan()
        log.info("[onShutdown] Shutdown complete.")
    }

    companion object {
        const val KEY = "system"

        private val normal: suspend (AppActor, Protocol) -> Behavior<Protocol.Response> = normal@{ ctx, m ->
            val log = ctx.log
            when (m) {
                is Protocol.Ping -> return@normal Behavior.Reply(Protocol.Pong)
                is Protocol.AcceptContact -> AppState.acceptContact(m.card)
                is Protocol.SetTrustLevel -> AppState.setTrustLevel(m.entry, m.level)
                is Protocol.PeerDiscovered -> AppState.peerDiscovered(m.nodeId)
                is Protocol.PeerLost -> AppState.peerLost(m.nodeId)
                is Protocol.PeerConnected -> AppState.peerConnected(m.nodeId)
                is Protocol.PeerDisconnected -> AppState.peerDisconnected(m.nodeId)
                is Protocol.AppForegrounded -> {
                    log.info("[normal] App foregrounded — restarting mDNS discovery.")
                    AppResources.lanAdapter.restartDiscovery()
                }

                is Protocol.AppBackgrounded -> log.info("[normal] App backgrounded.")
                is Protocol.InitiateContactExchange -> {
                    AppState.initiateContactExchange(m.nodeId, ctx.exchangeScope, m.exchanger) { result ->
                        if (result.isSuccess) ctx.tell(Protocol.ContactExchangeSucceeded(result.getOrThrow()))
                        else ctx.tell(
                            Protocol.ContactExchangeFailed(
                                result.exceptionOrNull()?.message ?: "unknown error"
                            )
                        )
                    }
                    ctx.become(exchange)
                }

                is Protocol.IncomingContactExchange -> {
                    val peerCard = m.codec.decode(m.peerCardBytes).getOrNull()
                    if (peerCard == null) {
                        log.warn("[normal] IncomingContactExchange: failed to decode peer card — rejecting.")
                        m.result.complete(null)
                    } else {
                        AppState.contactExchangeIncomingDeferred = m.result
                        AppState.contactExchangeIncomingPin = m.pin
                        AppState.contactExchangeIncomingCodec = m.codec
                        AppState.contactExchangeIncomingPeerCard = peerCard
                        AppUiState.showRecipientDrawer(peerCard)
                        ctx.become(exchange)
                    }
                }

                is Protocol.ResetData -> {
                    log.info("[normal] Resetting app data...")
                    ctx.become(reset)
                    val success = AppState.resetData()
                    launch {
                        delay(2.seconds)
                        exitApplication(if (success) 0 else 1)
                    }
                }
                // Exchange-result messages are irrelevant in normal mode — ignore
                is Protocol.ContactExchangePinSubmitted -> log.warn("[normal] Ignoring RecipientPinSubmitted — not in exchange.")
                is Protocol.ContactExchangeCancelled -> {
                    AppState.cancelContactExchange()
                    ctx.become(normal)
                }

                is Protocol.ContactExchangeSucceeded -> log.warn("[normal] Ignoring ExchangeSucceeded — not in exchange.")
                is Protocol.ContactExchangeFailed -> log.warn("[normal] Ignoring ExchangeFailed — not in exchange.")
            }
            Behavior.Reply(Protocol.Ok)
        }

        private val exchange: suspend (AppActor, Protocol) -> Behavior<Protocol.Response> = exchange@{ ctx, m ->
            val log = ctx.log
            when (m) {
                is Protocol.Ping -> return@exchange Behavior.Reply(Protocol.Pong)
                // Normal peer/app lifecycle — still processed during exchange
                is Protocol.AcceptContact -> AppState.acceptContact(m.card)
                is Protocol.SetTrustLevel -> AppState.setTrustLevel(m.entry, m.level)
                is Protocol.PeerDiscovered -> AppState.peerDiscovered(m.nodeId)
                is Protocol.PeerLost -> AppState.peerLost(m.nodeId)
                is Protocol.PeerConnected -> AppState.peerConnected(m.nodeId)
                is Protocol.PeerDisconnected -> AppState.peerDisconnected(m.nodeId)
                is Protocol.AppForegrounded -> {
                    log.info("[exchange] App foregrounded — restarting mDNS discovery.")
                    AppResources.lanAdapter.restartDiscovery()
                }

                is Protocol.AppBackgrounded -> log.info("[exchange] App backgrounded.")
                // Exchange-specific handlers
                is Protocol.ContactExchangePinSubmitted -> {
                    val deferred = AppState.contactExchangeIncomingDeferred
                    val pin = AppState.contactExchangeIncomingPin
                    val codec = AppState.contactExchangeIncomingCodec
                    val peerCard = AppState.contactExchangeIncomingPeerCard
                    AppState.resetContactExchange()
                    if (m.enteredPin == pin && codec != null && peerCard != null) {
                        val responseBytes = codec.encode(AppState.contactCard, AppState.identity.sigKeyPrivate)
                        deferred?.complete(responseBytes)
                        AppState.handleContactExchangeSuccess(peerCard)
                    } else {
                        deferred?.complete(null)
                        val reason = if (m.enteredPin != pin) "Invalid PIN" else "Exchange error"
                        AppState.handleContactExchangeFailure(reason)
                    }
                    ctx.become(normal)
                }

                is Protocol.ContactExchangeCancelled -> {
                    AppState.cancelContactExchange()
                    ctx.become(normal)
                }

                is Protocol.ContactExchangeSucceeded -> {
                    AppState.resetContactExchange()
                    AppState.handleContactExchangeSuccess(m.peerCard)
                    ctx.become(normal)
                }

                is Protocol.ContactExchangeFailed -> {
                    // Transport/network error on the requestor side — cancel silently so the
                    // user can tap "Add" again immediately without dismissing an error drawer.
                    log.warn("[exchange] Exchange failed (${m.reason}) — resetting.")
                    AppState.cancelContactExchange()
                    ctx.become(normal)
                }

                is Protocol.IncomingContactExchange -> m.result.complete(null) // reject: already in exchange
                is Protocol.InitiateContactExchange -> log.warn("[exchange] Ignoring InitiateExchange — already in exchange.")
                is Protocol.ResetData -> log.warn("[exchange] Ignoring ResetData — exchange in progress.")
            }
            Behavior.Reply(Protocol.Ok)
        }

        private val reset: suspend (AppActor, Protocol) -> Behavior<Protocol.Response> = reset@{ ctx, m ->
            val log = ctx.log
            when (m) {
                is Protocol.Ping -> return@reset Behavior.Reply(Protocol.Pong)
                else -> log.warn("[reset] Resetting app data... (ignored $m)")
            }
            Behavior.Reply(Protocol.Ok)
        }
    }
}
