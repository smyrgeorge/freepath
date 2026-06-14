package io.github.smyrgeorge.freepath.core.testing.cluster

import io.github.smyrgeorge.actor4k.system.ActorSystem
import io.github.smyrgeorge.actor4k.system.registry.SimpleActorRegistry
import io.github.smyrgeorge.actor4k.util.SimpleLoggerFactory
import io.github.smyrgeorge.freepath.core.actor.AppActor
import io.github.smyrgeorge.freepath.core.actor.ContactExchangeActor
import io.github.smyrgeorge.freepath.core.actor.SyncPeerActor
import io.github.smyrgeorge.freepath.core.state.model.StartupRoute
import io.github.smyrgeorge.freepath.core.testing.fake.FakeNetwork
import io.github.smyrgeorge.freepath.core.testing.state.TestAppResources
import io.github.smyrgeorge.freepath.core.testing.state.TestAppState
import io.github.smyrgeorge.freepath.core.testing.state.TestViewState
import io.github.smyrgeorge.freepath.core.testing.util.awaitUntil
import io.github.smyrgeorge.freepath.core.util.InMemoryLoggingAppender
import io.github.smyrgeorge.log4k.RootLogger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A cluster of in-process test nodes sharing one [FakeNetwork] and the one global actor system.
 *
 * actor4k's `ActorSystem` is a process-wide singleton, so only ONE cluster can run at a time —
 * always [shutdown] before starting another (use `try { ... } finally { cluster.shutdown() }`).
 *
 * ```
 * val cluster = TestCluster.start(nodes = 3)
 * try {
 *     val (a, b, r) = cluster.nodes
 *     cluster.seedMutualContacts(a, b)
 *     cluster.connect(a, b)
 *     a.sendMessage(to = b, "hello")
 *     awaitUntil { b.chatWith(a).any { it.body == "hello" } }
 * } finally {
 *     cluster.shutdown()
 * }
 * ```
 */
class TestCluster private constructor(
    val network: FakeNetwork,
    val nodes: List<TestNode>,
) {
    /** Connect two nodes and wait until each can route to the other. */
    suspend fun connect(a: TestNode, b: TestNode) {
        network.connect(a.peerId, b.peerId)
        awaitUntil { b.peerId in a.onlinePeers && a.peerId in b.onlinePeers }
    }

    /** Disconnect two nodes and wait until neither sees the other. */
    suspend fun disconnect(a: TestNode, b: TestNode) {
        network.disconnect(a.peerId, b.peerId)
        awaitUntil { b.peerId !in a.onlinePeers && a.peerId !in b.onlinePeers }
    }

    /**
     * Seed [a] and [b] as mutual contacts so they can encrypt to / verify each other. Required
     * before messaging: `LibnetClient.send` refuses to encrypt without the receiver's contact card,
     * and the receiver needs the sender's card to verify the signature.
     */
    suspend fun seedMutualContacts(a: TestNode, b: TestNode) {
        a.ensureOnboarded()
        b.ensureOnboarded()
        a.addContact(b)
        b.addContact(a)
    }

    /** Shut down all nodes and the actor system, resetting it so another cluster can start. */
    suspend fun shutdown() {
        ActorSystem.shutdown()
    }

    companion object {
        /** Boots [nodes] fully-initialised nodes (DB migrated, identity created, networking up). */
        suspend fun start(nodes: Int): TestCluster {
            require(nodes > 0) { "nodes must be > 0" }
            check(ActorSystem.status == ActorSystem.Status.NOT_READY) {
                "ActorSystem is ${ActorSystem.status}; a previous TestCluster was not shut down."
            }
            initLogging()

            val network = FakeNetwork()
            val registry = NodeRegistry()

            val nodeList = (0 until nodes).map { i ->
                val viewState = TestViewState()
                val resources = TestAppResources(network)
                val state = TestAppState(resources, viewState)
                TestNode(id = "node-$i", resources = resources, state = state, viewState = viewState)
                    .also { registry.put(it) }
            }

            startActorSystem(registry)

            // Booting the AppActor kicks off async activation (DB init + identity + networking). Wire
            // the actor refs immediately so the libp2p/client callbacks can tell() the AppActor before
            // any traffic — which only happens on connect(), well after this completes.
            nodeList.forEach { node ->
                val app = ActorSystem.get(AppActor::class, node.id)
                val contactExchange = ActorSystem.get(ContactExchangeActor::class, node.id)
                node.resources.initialize(app, contactExchange)
                node.attachRefs(app, contactExchange)
            }

            // Wait for activation to finish, then expose each peerId and register it so the
            // SyncPeerActor factory can resolve the owning node.
            nodeList.forEach { node ->
                awaitUntil(timeout = 30.seconds) {
                    node.viewState.startupRoute.value != StartupRoute.Loading
                }
                node.bindPeerId(node.state.identityEntry.peerId)
                registry.bindPeerId(node)
            }

            return TestCluster(network, nodeList)
        }

        private fun startActorSystem(registry: NodeRegistry) {
            val conf = ActorSystem.Conf(
                actorActivateTimeout = 60.seconds,   // onActivate runs DB init + a ~1s startup pad
                actorExpiresAfter = 1.hours,         // don't let an idle SyncPeerActor expire mid-test
                registryCleanupEvery = 1.hours,
                systemCollectStatsEvery = 1.hours,   // stats effectively disabled
                systemLogStatsEvery = 1.hours,
                shutdownPollingInterval = 50.milliseconds,
            )
            val loggerFactory = SimpleLoggerFactory()
            val actorRegistry = SimpleActorRegistry(loggerFactory)
                .factoryFor(AppActor::class) { key ->
                    val node = registry.byNodeId(key)
                    AppActor(key = key, state = node.state, viewState = node.viewState, resources = node.resources)
                }
                .factoryFor(ContactExchangeActor::class) { key ->
                    val node = registry.byNodeId(key)
                    ContactExchangeActor(
                        key = key,
                        state = node.state,
                        viewState = node.viewState,
                        resources = node.resources
                    )
                }
                .factoryFor(SyncPeerActor::class) { key ->
                    val node = registry.byPeerId(SyncPeerActor.ownerPeerIdOf(key))
                    SyncPeerActor(key = key, state = node.state, resources = node.resources)
                }

            ActorSystem
                .conf(conf)
                .register(loggerFactory)
                .register(actorRegistry)
                .start(registerShutdownHook = false)
        }

        private var loggingInitialised = false
        private fun initLogging() {
            if (loggingInitialised) return
            loggingInitialised = true
            RootLogger.Logging.appenders.register(InMemoryLoggingAppender)
        }
    }
}
