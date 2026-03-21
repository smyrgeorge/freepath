package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.actor4k.actor.impl.BehaviorActor
import io.github.smyrgeorge.freepath.client.model.ChatMessage
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
            resources.initializeDatabase()
            state.initialize()
            resources.initializeAppClient(state = state)
            resources.startLibp2p(
                nodeId = state.contactCard.nodeId,
                sigKeyPrivate = state.identity.sigKeyPrivate,
                identityEntry = state.identityEntry,
                contactLookup = { state.contactLookup(it) },
            )
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
        resources.stopLibp2p()
        resources.closeDatabase()
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
                is Protocol.InitiateContactExchange -> {
                    state.initiateContactExchange(m.peerId)
                    ctx.become(exchange)
                }

                is Protocol.IncomingContactExchange -> {
                    state.contactExchangeIncomingPin = m.pin
                    state.contactExchangeIncomingPeerId = m.peerId
                    state.contactExchangeIncomingPeerCard = m.peerCard
                    viewState.showRecipientDrawer(m.peerCard)
                    ctx.become(exchange)
                }

                is Protocol.SendChatMessage -> {
                    val message = ChatMessage(state.identityEntry.nodeId, m.peerId, m.text)
                    resources.client.send(message)
                        .onSuccess { state.appendMessage(message) }
                        .onFailure { log.error("Failed to send chat message: ${it.message}") }
                }

                is Protocol.ChatMessageReceived -> state.appendMessage(m.message)
                is Protocol.ContentReceived -> state.receiveContent(m.envelope)
                is Protocol.PeerIdentified -> {
                    resources.client.send(state.contactCardContentEnvelope, m.peerId)
                        .onFailure { log.warn("[PeerIdentified] Failed to push contact card to ${m.peerId}: ${it.message}") }
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

                is Protocol.ContactExchangeCancelled -> {
                    state.cancelContactExchange()
                    ctx.become(normal)
                }

                else -> log.warn("[normal] (ignored $m)")
            }
            Behavior.Reply(Protocol.Ok)
        }

        private val exchange: suspend (AppActor, Protocol) -> Behavior<Protocol.Response> = exchange@{ ctx, m ->
            val log = ctx.log
            val state = ctx.state
            val viewState = ctx.viewState
            when (m) {
                is Protocol.Ping -> return@exchange Behavior.Reply(Protocol.Pong)
                is Protocol.AcceptContact -> state.acceptContact(m.card)
                is Protocol.ContactExchangePinSubmitted -> {
                    val pin = state.contactExchangeIncomingPin
                    val peerId = state.contactExchangeIncomingPeerId
                    val peerCard = state.contactExchangeIncomingPeerCard
                    state.resetContactExchange()
                    if (m.enteredPin == pin && peerId != null && peerCard != null) {
                        state.acceptContact(peerCard)
                        viewState.hideExchangeDrawer()
                    } else {
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
                    state.acceptContact(m.peerCard) // requestor auto-accepts — they initiated
                    state.cancelContactExchange()   // hides the requestor PIN drawer
                    ctx.become(normal)
                }

                is Protocol.ContactExchangeFailed -> {
                    log.warn("[exchange] Exchange failed (${m.reason}) — resetting.")
                    state.cancelContactExchange()
                    ctx.become(normal)
                }

                is Protocol.ChatMessageReceived -> state.appendMessage(m.message)
                is Protocol.ContentReceived -> state.receiveContent(m.envelope)
                else -> log.warn("[exchange] Contact exchange in process.. (ignored $m)")
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
