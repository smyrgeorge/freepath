package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.actor4k.actor.impl.BehaviorActor
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import io.github.smyrgeorge.freepath.state.AbstractAppResources
import io.github.smyrgeorge.freepath.state.AbstractAppState
import io.github.smyrgeorge.freepath.state.AbstractViewState
import io.github.smyrgeorge.freepath.state.model.StartupRoute
import io.github.smyrgeorge.freepath.util.exitApplication
import io.github.smyrgeorge.log4k.impl.extensions.doEvery
import io.github.smyrgeorge.log4k.impl.extensions.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class AppActor(
    key: String,
    private val state: AbstractAppState,
    private val viewState: AbstractViewState,
    private val resources: AbstractAppResources,
) : BehaviorActor<Protocol, Protocol.Response>(key) {
    private val timer: Job = doEvery(5.seconds) {
        tell(Protocol.Ping).getOrThrow() // Keep the actor alive.
    }

    override suspend fun onBeforeActivate() {
        tell(Protocol.Ping).getOrThrow() // Trigger activation.
    }

    override suspend fun onActivate(m: Protocol) {
        log.info("[onActivate] Activating... ($m)")
        val time = measureTime {
            resources.openDatabase()
            state.initialize()
            launch {
                log.info("[onActivate] Starting up LAN protocol in the background...")
                resources.startupLan(state.identityEntry.nodeId, state.identity)
            }
        }

        log.info("[onActivate] Identity: ${state.identityEntry}")
        log.info("[onActivate] ContactCard: ${state.contactCardEntry}")
        log.info("[onActivate] Initialization took $time")

        val route = when {
            ContactCardEntry.TAG_ONBOARDING in state.contactCardEntry.tags -> StartupRoute.Onboarding
            viewState.pendingDeepLink.value != null -> {
                viewState.clearPendingDeepLink()
                StartupRoute.Network
            }

            else -> StartupRoute.Nearby
        }

        if (time < 1.seconds) delay(1.seconds - time)
        viewState.setStartupRoute(route)
        become(normal)
    }

    override suspend fun onShutdown() {
        log.info("[onShutdown] Shutting down...")
        timer.cancel()
        state.resetContactExchange() // Cancel any ongoing contact exchange flow.
        resources.closeDatabase()
        resources.shutdownLan()
        log.info("[onShutdown] Shutdown complete.")
    }

    companion object {
        const val DEFAULT_KEY = "system"

        private val normal: suspend (AppActor, Protocol) -> Behavior<Protocol.Response> = normal@{ ctx, m ->
            val log = ctx.log
            val state = ctx.state
            val viewState = ctx.viewState
            val resources = ctx.resources
            when (m) {
                is Protocol.Ping -> return@normal Behavior.Reply(Protocol.Pong)
                is Protocol.AcceptContact -> state.acceptContact(m.card)
                is Protocol.SetTrustLevel -> state.setTrustLevel(m.entry, m.level)
                is Protocol.PeerDiscovered -> state.peerDiscovered(m.nodeId)
                is Protocol.PeerConnected -> state.peerConnected(m.nodeId)
                is Protocol.PeerDisconnected -> state.peerDisconnected(m.nodeId)
                is Protocol.AppForegrounded -> {
                    log.info("[normal] App foregrounded — restarting mDNS discovery.")
                    resources.lanAdapter.restartDiscovery()
                }

                is Protocol.AppBackgrounded -> log.info("[normal] App backgrounded.")
                is Protocol.InitiateContactExchange -> {
                    state.initiateContactExchange(m.nodeId, m.exchanger) { result ->
                        val msg = if (result.isSuccess) Protocol.ContactExchangeSucceeded(result.getOrThrow())
                        else Protocol.ContactExchangeFailed(result.exceptionOrNull()?.message ?: "unknown error")
                        ctx.tell(msg).getOrThrow()
                    }
                    ctx.become(exchange)
                }

                is Protocol.IncomingContactExchange -> {
                    val peerCard = m.codec.decode(m.peerCardBytes).getOrNull()
                    if (peerCard == null) {
                        log.warn("[normal] IncomingContactExchange: failed to decode peer card — rejecting.")
                        m.result.complete(null)
                    } else {
                        state.contactExchangeIncomingDeferred = m.result
                        state.contactExchangeIncomingPin = m.pin
                        state.contactExchangeIncomingCodec = m.codec
                        state.contactExchangeIncomingPeerCard = peerCard
                        viewState.showRecipientDrawer(peerCard)
                        ctx.become(exchange)
                    }
                }

                is Protocol.ResetData -> {
                    log.info("[normal] Resetting app data...")
                    ctx.become(reset)
                    val success = state.resetData()
                    launch {
                        delay(2.seconds)
                        exitApplication(if (success) 0 else 1)
                    }
                }
                // Exchange-result messages are irrelevant in normal mode — ignore
                is Protocol.ContactExchangePinSubmitted -> log.warn("[normal] Ignoring RecipientPinSubmitted — not in exchange.")
                is Protocol.ContactExchangeCancelled -> {
                    state.cancelContactExchange()
                    ctx.become(normal)
                }

                is Protocol.ContactExchangeSucceeded -> log.warn("[normal] Ignoring ExchangeSucceeded — not in exchange.")
                is Protocol.ContactExchangeFailed -> log.warn("[normal] Ignoring ExchangeFailed — not in exchange.")
            }
            Behavior.Reply(Protocol.Ok)
        }

        private val exchange: suspend (AppActor, Protocol) -> Behavior<Protocol.Response> = exchange@{ ctx, m ->
            val log = ctx.log
            val state = ctx.state
            val resources = ctx.resources
            when (m) {
                is Protocol.Ping -> return@exchange Behavior.Reply(Protocol.Pong)
                // Normal peer/app lifecycle — still processed during exchange
                is Protocol.AcceptContact -> state.acceptContact(m.card)
                is Protocol.SetTrustLevel -> state.setTrustLevel(m.entry, m.level)
                is Protocol.PeerDiscovered -> state.peerDiscovered(m.nodeId)
                is Protocol.PeerConnected -> state.peerConnected(m.nodeId)
                is Protocol.PeerDisconnected -> state.peerDisconnected(m.nodeId)
                is Protocol.AppForegrounded -> {
                    log.info("[exchange] App foregrounded — restarting mDNS discovery.")
                    resources.lanAdapter.restartDiscovery()
                }

                is Protocol.AppBackgrounded -> log.info("[exchange] App backgrounded.")
                // Exchange-specific handlers
                is Protocol.ContactExchangePinSubmitted -> {
                    val deferred = state.contactExchangeIncomingDeferred
                    val pin = state.contactExchangeIncomingPin
                    val codec = state.contactExchangeIncomingCodec
                    val peerCard = state.contactExchangeIncomingPeerCard
                    state.resetContactExchange()
                    if (m.enteredPin == pin && codec != null && peerCard != null) {
                        val responseBytes = codec.encode(state.contactCard, state.identity.sigKeyPrivate)
                        deferred?.complete(responseBytes)
                        state.handleContactExchangeSuccess(peerCard)
                    } else {
                        deferred?.complete(null)
                        val reason = if (m.enteredPin != pin) "Invalid PIN" else "Exchange error"
                        state.handleContactExchangeFailure(reason)
                    }
                    ctx.become(normal)
                }

                is Protocol.ContactExchangeCancelled -> {
                    state.cancelContactExchange()
                    ctx.become(normal)
                }

                is Protocol.ContactExchangeSucceeded -> {
                    state.resetContactExchange()
                    state.handleContactExchangeSuccess(m.peerCard)
                    ctx.become(normal)
                }

                is Protocol.ContactExchangeFailed -> {
                    // Transport/network error on the requestor side — cancel silently so the
                    // user can tap "Add" again immediately without dismissing an error drawer.
                    log.warn("[exchange] Exchange failed (${m.reason}) — resetting.")
                    state.cancelContactExchange()
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
