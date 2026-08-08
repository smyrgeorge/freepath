package io.github.smyrgeorge.freepath.core.state

import io.github.smyrgeorge.actor4k.system.ActorSystem
import io.github.smyrgeorge.actor4k.system.registry.SimpleActorRegistry
import io.github.smyrgeorge.actor4k.util.SimpleLoggerFactory
import io.github.smyrgeorge.actor4k.util.extentions.launch
import io.github.smyrgeorge.freepath.core.actor.AppActor
import io.github.smyrgeorge.freepath.core.actor.AppProtocol
import io.github.smyrgeorge.freepath.core.actor.ContactExchangeActor
import io.github.smyrgeorge.freepath.core.actor.PeerActor
import io.github.smyrgeorge.freepath.core.actor.RelayActor
import io.github.smyrgeorge.freepath.core.util.InMemoryLoggingAppender
import io.github.smyrgeorge.log4k.RootLogger
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
            .factoryFor(ContactExchangeActor::class) {
                ContactExchangeActor(
                    key = ContactExchangeActor.DEFAULT_KEY,
                    state = state,
                    viewState = viewState,
                    resources = resources,
                )
            }
            .factoryFor(PeerActor::class) { key ->
                PeerActor(key = key, state = state, resources = resources)
            }
            .factoryFor(RelayActor::class) { key ->
                RelayActor(key = key, resources = resources)
            }

        ActorSystem
            .conf(conf)
            .register(loggerFactory)
            .register(registry)
            .start(registerShutdownHook = false)

        launch {
            val app = ActorSystem.get(AppActor::class, actorKey)
            val contactExchange = ActorSystem.get(ContactExchangeActor::class, ContactExchangeActor.DEFAULT_KEY)
            resources.initialize(app, contactExchange)
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
