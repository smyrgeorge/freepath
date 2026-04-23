package io.github.smyrgeorge.freepath.state

import io.github.smyrgeorge.actor4k.system.ActorSystem
import io.github.smyrgeorge.actor4k.system.registry.SimpleActorRegistry
import io.github.smyrgeorge.actor4k.util.SimpleLoggerFactory
import io.github.smyrgeorge.freepath.AppResources
import io.github.smyrgeorge.freepath.AppState
import io.github.smyrgeorge.freepath.AppViewState
import io.github.smyrgeorge.freepath.actor.AppActor
import io.github.smyrgeorge.freepath.actor.AppProtocol
import io.github.smyrgeorge.freepath.actor.SyncPeerActor
import io.github.smyrgeorge.freepath.util.InMemoryLoggingAppender
import io.github.smyrgeorge.log4k.RootLogger
import io.github.smyrgeorge.log4k.impl.extensions.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

abstract class AbstractAppHooks(
    private val actorKey: String
) {
    fun onCreate() {
        RootLogger.Logging.appenders.register(InMemoryLoggingAppender)

        val conf = ActorSystem.Conf(
            systemCollectStatsEvery = 1.hours, // Intentionally big (disabled)
            systemLogStatsEvery = 1.hours, // Intentionally big (disabled)
            shutdownPollingInterval = 500.milliseconds,
            actorExpiresAfter = 5.minutes,
        )
        val loggerFactory = SimpleLoggerFactory()
        val registry = SimpleActorRegistry(loggerFactory)
            .factoryFor(AppActor::class) {
                AppActor(
                    key = actorKey,
                    state = AppState,
                    viewState = AppViewState,
                    resources = AppResources,
                )
            }
            .factoryFor(SyncPeerActor::class) { key ->
                SyncPeerActor(key = key, state = AppState, resources = AppResources)
            }

        ActorSystem
            .conf(conf)
            .register(loggerFactory)
            .register(registry)
            .start(registerShutdownHook = false)

        launch {
            val system = ActorSystem.get(AppActor::class, actorKey)
            AppResources.initialize(system)
        }
    }

    fun onDestroy() {
        // The use of `runBlocking` is intentional here.
        runBlocking { ActorSystem.shutdown() }
    }

    fun onStart() {
        launch { ActorSystem.get(AppActor::class, actorKey).tell(AppProtocol.AppForegrounded) }
    }

    fun onStop() {
        launch { ActorSystem.get(AppActor::class, actorKey).tell(AppProtocol.AppBackgrounded) }
    }
}
