package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.actor4k.actor.impl.BehaviorActor
import io.github.smyrgeorge.freepath.database.ContactCardEntry
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
                    AppState.contactExchangeIncomingDeferred = m.result
                    AppState.contactExchangeIncomingPin = m.pin
                    AppState.contactExchangeIncomingCodec = m.codec
                    AppUiState.showRecipientDrawer(m.peerCardBytes)
                    ctx.become(exchange)
                }
                // Exchange-result messages are irrelevant in normal mode — ignore
                is Protocol.ContactExchangePinSubmitted -> log.warn("[normal] Ignoring RecipientPinSubmitted — not in exchange.")
                is Protocol.ContactExchangeCancelled -> log.warn("[normal] Ignoring ExchangeCancelled — not in exchange.")
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
                    val expectedPin = AppState.contactExchangeIncomingPin
                    val codec = AppState.contactExchangeIncomingCodec
                    AppState.contactExchangeIncomingDeferred = null
                    AppState.contactExchangeIncomingPin = null
                    AppState.contactExchangeIncomingCodec = null
                    val responseBytes = if (m.enteredPin == expectedPin) {
                        if (codec != null) {
                            codec.encode(AppState.contactCard, AppState.identity.sigKeyPrivate)
                        } else {
                            log.warn("[exchange] RecipientPinSubmitted: incomingCodec is null — rejecting exchange.")
                            null
                        }
                    } else null
                    deferred?.complete(responseBytes)
                    ctx.become(normal)
                }

                is Protocol.ContactExchangeCancelled -> {
                    AppState.cancelContactExchange()
                    ctx.become(normal)
                }

                is Protocol.ContactExchangeSucceeded -> {
                    AppState.handleContactExchangeSuccess(m.peerCard)
                    ctx.become(normal)
                }

                is Protocol.ContactExchangeFailed -> {
                    AppState.handleContactExchangeFailure(m.reason)
                    ctx.become(normal)
                }

                is Protocol.IncomingContactExchange -> m.result.complete(null) // reject: already in exchange
                is Protocol.InitiateContactExchange -> log.warn("[exchange] Ignoring InitiateExchange — already in exchange.")
            }
            Behavior.Reply(Protocol.Ok)
        }
    }
}
