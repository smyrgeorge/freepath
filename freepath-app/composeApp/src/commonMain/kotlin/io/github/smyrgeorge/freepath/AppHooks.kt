package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.actor4k.system.ActorSystem
import io.github.smyrgeorge.actor4k.system.registry.SimpleActorRegistry
import io.github.smyrgeorge.actor4k.util.SimpleLoggerFactory
import io.github.smyrgeorge.freepath.sync.SyncPeerActor
import io.github.smyrgeorge.log4k.impl.extensions.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

object AppHooks {
    fun onCreate() {
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
                    key = AppActor.DEFAULT_KEY,
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
            val system = ActorSystem.get(AppActor::class, AppActor.DEFAULT_KEY)
            AppResources.initialize(system)
        }
    }

    fun onDestroy() {
        // The use of `runBlocking` is intentional here.
        runBlocking { ActorSystem.shutdown() }
    }

    fun onStart() {
        launch { ActorSystem.get(AppActor::class, AppActor.DEFAULT_KEY).tell(Protocol.AppForegrounded) }
    }

    fun onStop() {
        launch { ActorSystem.get(AppActor::class, AppActor.DEFAULT_KEY).tell(Protocol.AppBackgrounded) }
    }
}