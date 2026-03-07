package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.actor4k.system.ActorSystem
import io.github.smyrgeorge.actor4k.system.registry.SimpleActorRegistry
import io.github.smyrgeorge.actor4k.util.SimpleLoggerFactory
import io.github.smyrgeorge.freepath.util.configureLogging
import io.github.smyrgeorge.log4k.RootLogger
import io.github.smyrgeorge.log4k.impl.extensions.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

object AppHooks {
    @Suppress("unused")
    private val rootLogger = RootLogger // Do not delete this line.

    fun onCreate() {
        configureLogging()

        val conf = ActorSystem.Conf(
            systemCollectStatsEvery = 1.hours,
            systemLogStatsEvery = 1.hours,
            shutdownPollingInterval = 500.milliseconds,
        )
        val loggerFactory = SimpleLoggerFactory()
        val registry = SimpleActorRegistry(loggerFactory)
            .factoryFor(AppActor::class) { AppActor() }

        ActorSystem
            .conf(conf)
            .register(loggerFactory)
            .register(registry)
            .start(registerShutdownHook = false)

        launch {
            val system = ActorSystem.get(AppActor::class, AppActor.KEY)
            AppResources.initialize(system)
        }
    }

    fun onDestroy() {
        runBlocking { ActorSystem.shutdown() }
    }

    fun onStart() {
        launch { AppResources.system.tell(Protocol.AppForegrounded) }
    }

    fun onStop() {
        launch { AppResources.system.tell(Protocol.AppBackgrounded) }
    }
}