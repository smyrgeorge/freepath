package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.actor4k.actor.impl.BehaviorActor
import io.github.smyrgeorge.actor4k.system.ActorSystem
import io.github.smyrgeorge.freepath.core.state.AbstractAppResources
import io.github.smyrgeorge.freepath.core.state.AbstractAppState
import io.github.smyrgeorge.freepath.core.state.AbstractViewState
import io.github.smyrgeorge.freepath.core.state.model.StartupRoute
import io.github.smyrgeorge.freepath.database.ContactEntry
import io.github.smyrgeorge.freepath.database.MessageStatus
import io.github.smyrgeorge.freepath.util.currentPlatform
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
) : BehaviorActor<AppProtocol, AppProtocol.Response>(key) {
    private val timer: Job = doEvery(5.seconds) {
        // Keep the actor alive.
        tell(AppProtocol.Ping).getOrElse { log.error("Failed to ping: ${it.message}") }
    }

    override suspend fun onBeforeActivate() {
        // Trigger activation.
        tell(AppProtocol.Ping).getOrElse { log.error("Failed to ping: ${it.message}") }
    }

    override suspend fun onActivate(m: AppProtocol) {
        log.info("[onActivate] Activating on ${currentPlatform}... ($m)")
        val time = measureTime {
            resources.initializeDatabase()
            state.initialize()
            resources.initialize(
                identity = state.identity,
                contactLookup = { state.contactLookup(it) },
            )
            resources.startNetworking()
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
        resources.stopNetworking()
        resources.closeDatabase()
        log.info("[onShutdown] Shutdown complete.")
    }

    companion object {
        const val DEFAULT_KEY = "system"

        private val normal: suspend (AppActor, AppProtocol) -> Behavior<AppProtocol.Response> = normal@{ ctx, m ->
            val log = ctx.log
            val state = ctx.state
            when (m) {
                is AppProtocol.Ping -> return@normal Behavior.Reply(AppProtocol.Pong)
                is AppProtocol.AcceptContact -> state.acceptContact(m.contact)
                is AppProtocol.SetTrustLevel -> state.setTrustLevel(m.entry, m.level)
                is AppProtocol.SendMessage -> state.send(m.peerId, m.text)
                is AppProtocol.MessageReceived -> state.saveMessage(m.msg, MessageStatus.RECEIVED)
                is AppProtocol.ContentReceived -> state.receiveContent(m.envelope)
                is AppProtocol.PublishContent -> state.publishContent(m.body)
                is AppProtocol.PeerConnected -> {
                    ActorSystem.get(SyncPeerActor::class, SyncPeerActor.key(state.identity.peerId, m.peerId))
                        .tell(SyncPeerProtocol.Connected)
                        .onFailure { log.warn("[PeerConnected] Failed to trigger for ${m.peerId}: ${it.message}") }
                }

                is AppProtocol.PeerIdentified -> {
                    ActorSystem.get(SyncPeerActor::class, SyncPeerActor.key(state.identity.peerId, m.peerId))
                        .tell(SyncPeerProtocol.Identified)
                        .onFailure { log.warn("[PeerIdentified] Failed to trigger for ${m.peerId}: ${it.message}") }
                }

                is AppProtocol.ResetData -> {
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
            Behavior.Reply(AppProtocol.Ok)
        }

        private val reset: suspend (AppActor, AppProtocol) -> Behavior<AppProtocol.Response> = reset@{ ctx, m ->
            val log = ctx.log
            when (m) {
                is AppProtocol.Ping -> return@reset Behavior.Reply(AppProtocol.Pong)
                else -> log.warn("[reset] Resetting app data... (ignored $m)")
            }
            Behavior.Reply(AppProtocol.Ok)
        }
    }
}