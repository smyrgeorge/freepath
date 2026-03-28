package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.actor4k.actor.impl.BehaviorActor
import io.github.smyrgeorge.freepath.client.model.ChatMessage
import io.github.smyrgeorge.freepath.database.ContactEntry
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
    /** Tracks the currently running exchange coroutine so it can be cancelled on dismiss. */
    private var exchangeJob: Job? = null

    private val timer: Job = doEvery(5.seconds) {
        // Keep the actor alive.
        tell(Protocol.Ping).getOrElse { log.error("Failed to ping: ${it.message}") }
    }

    override suspend fun onBeforeActivate() {
        // Trigger activation.
        tell(Protocol.Ping).getOrElse { log.error("Failed to ping: ${it.message}") }
    }

    override suspend fun onActivate(m: Protocol) {
        log.info("[onActivate] Activating... ($m)")
        val time = measureTime {
            resources.initializeDatabase()
            state.initialize()
            resources.initializeAppClient(state = state)
            resources.startLibp2p(
                peerId = state.contact.peerId,
                sigKeyPrivate = state.identity.sigKeyPrivate,
                identityEntry = state.identityEntry,
                contactLookup = { state.contactLookup(it) },
            )
            resources.startupLibble()
        }

        log.info("[onActivate] Identity: ${state.identityEntry}")
        log.info("[onActivate] Contact: ${state.contactEntry}")
        log.info("[onActivate] Initialization took $time")


        val route = when {
            ContactEntry.TAG_ONBOARDING in state.contactEntry.tags -> StartupRoute.Onboarding
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
        resources.stopLibp2p()
        resources.stopLibble()
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
                is Protocol.AcceptContact -> state.acceptContact(m.contact)
                is Protocol.SetTrustLevel -> state.setTrustLevel(m.entry, m.level)
                is Protocol.BleInitiateContactExchange -> {
                    viewState.showRequestorEnterPin(m.peripheralId)
                    ctx.become(exchange)
                }

                is Protocol.BleInitiateResponderContactExchange -> {
                    val pin = (0 until 4).map { ('0'..'9').random() }.joinToString("")
                    viewState.showResponderWaiting(pin)
                    ctx.become(exchange)
                    ctx.exchangeJob = launch {
                        val result = resources.libble.beginResponderExchange(
                            pin = pin,
                            localContact = state.contact,
                            sigKeyPrivate = state.identity.sigKeyPrivate,
                        )
                        result.onSuccess { (peerCard, centralId) ->
                            ctx.tell(Protocol.BleContactExchangeSucceeded(peerCard, centralId)).getOrThrow()
                        }.onFailure { e ->
                            ctx.tell(Protocol.BleContactExchangeFailed(e.message ?: "BLE exchange failed")).getOrThrow()
                        }
                    }
                }

                is Protocol.SendChatMessage -> {
                    val message = ChatMessage(state.identityEntry.peerId, m.peerId, m.text)
                    resources.client.send(message)
                        .onSuccess { state.appendMessage(message) }
                        .onFailure { log.error("Failed to send chat message: ${it.message}") }
                }

                is Protocol.ChatMessageReceived -> state.appendMessage(m.msg)
                is Protocol.ContentReceived -> state.receiveContent(m.envelope)
                is Protocol.PeerIdentified -> {
                    resources.client.send(state.contactContentEnvelope, m.peerId)
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

                else -> log.warn("[normal] (ignored $m)")
            }
            Behavior.Reply(Protocol.Ok)
        }

        //
        // Exchange state machine:
        //
        //   normal   ──► BleInitiateContactExchange          ──► exchange  (shows RequestorEnterPin drawer)
        //   normal   ──► BleInitiateResponderContactExchange ──► exchange  (shows ResponderWaiting drawer, launches exchange)
        //   exchange ──► BleBeginInitiatorContactExchange    ──► exchange  (launches initiator exchange with entered PIN)
        //   exchange ──► BleContactExchangeSucceeded         ──► normal    (accepts card, hides drawer)
        //   exchange ──► BleContactExchangeFailed            ──► exchange  (shows Failed drawer)
        //   exchange ──► BleContactExchangeCancelled         ──► normal    (hides drawer — also handles Dismiss)
        //
        private val exchange: suspend (AppActor, Protocol) -> Behavior<Protocol.Response> = exchange@{ ctx, m ->
            val log = ctx.log
            val state = ctx.state
            val viewState = ctx.viewState
            val resources = ctx.resources
            when (m) {
                is Protocol.Ping -> return@exchange Behavior.Reply(Protocol.Pong)
                is Protocol.AcceptContact -> state.acceptContact(m.contact)

                is Protocol.BleBeginInitiatorContactExchange -> {
                    viewState.hideExchangeDrawer()
                    ctx.exchangeJob = launch {
                        val result = resources.libble.beginInitiatorExchange(
                            peripheralId = m.peripheralId,
                            pin = m.pin,
                            localContact = state.contact,
                            sigKeyPrivate = state.identity.sigKeyPrivate,
                        )
                        result.onSuccess { peerCard ->
                            ctx.tell(Protocol.BleContactExchangeSucceeded(peerCard, m.peripheralId)).getOrThrow()
                        }.onFailure { e ->
                            ctx.tell(Protocol.BleContactExchangeFailed(e.message ?: "BLE exchange failed")).getOrThrow()
                        }
                    }
                }

                is Protocol.BleContactExchangeCancelled -> {
                    ctx.exchangeJob?.cancel()
                    ctx.exchangeJob = null
                    state.cancelContactExchange()
                    ctx.become(normal)
                }

                is Protocol.BleContactExchangeSucceeded -> {
                    ctx.exchangeJob = null
                    state.acceptContact(m)
                    state.cancelContactExchange()
                    ctx.become(normal)
                }

                is Protocol.BleContactExchangeFailed -> {
                    log.warn("[exchange] Exchange failed: ${m.reason}")
                    val userReason = friendlyBleError(m.reason)
                    viewState.exchangeFailed(userReason)
                    // Stay in exchange until user dismisses the Failed drawer via BleContactExchangeCancelled
                }

                is Protocol.ChatMessageReceived -> state.appendMessage(m.msg)
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

        private fun friendlyBleError(reason: String): String = when {
            reason.contains("disconnect", ignoreCase = true) ->
                "Connection lost. Make sure both devices are nearby and try again."

            reason.contains("timeout", ignoreCase = true) ->
                "Exchange timed out. Make sure both devices are ready and try again."

            reason.contains("PIN", ignoreCase = true) ->
                "PIN confirmation failed. Check that both devices entered the same PIN."

            reason.contains("mismatch", ignoreCase = true) ->
                "PIN confirmation failed. Check that both devices entered the same PIN."

            else -> "Exchange failed. Please try again."
        }
    }
}
