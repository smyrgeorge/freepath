package io.github.smyrgeorge.freepath.core.state

import io.github.smyrgeorge.actor4k.system.ActorSystem
import io.github.smyrgeorge.actor4k.system.registry.SimpleActorRegistry
import io.github.smyrgeorge.actor4k.util.SimpleLoggerFactory
import io.github.smyrgeorge.freepath.core.actor.AppActor
import io.github.smyrgeorge.freepath.core.actor.AppProtocol
import io.github.smyrgeorge.freepath.core.actor.SyncPeerActor
import io.github.smyrgeorge.freepath.core.util.InMemoryLoggingAppender
import io.github.smyrgeorge.log4k.RootLogger
import io.github.smyrgeorge.log4k.impl.extensions.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

abstract class AbstractAppHooks(
    private val actorKey: String,
    private val resources: AbstractAppResources,
    private val state: AbstractAppState,
    private val viewState: AbstractViewState,
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
                    state = state,
                    viewState = viewState,
                    resources = resources,
                )
            }
            .factoryFor(SyncPeerActor::class) { key ->
                SyncPeerActor(key = key, state = state, resources = resources)
            }

        ActorSystem
            .conf(conf)
            .register(loggerFactory)
            .register(registry)
            .start(registerShutdownHook = false)

        launch {
            val system = ActorSystem.get(AppActor::class, actorKey)
            resources.initialize(system)
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
