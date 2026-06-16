package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.Actor
import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.freepath.core.state.AbstractAppResources
import io.github.smyrgeorge.freepath.core.state.service.RelayService
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.db
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.tx
import io.github.smyrgeorge.log4k.impl.extensions.doEvery
import kotlinx.coroutines.Job
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class RelayActor(
    key: String,
    resources: AbstractAppResources,
) : Actor<RelayProtocol, RelayProtocol.Response>(key) {

    private val relayService: RelayService = resources.relayService

    private val timer: Job = doEvery(SWEEP_INTERVAL) {
        tell(RelayProtocol.Sweep).getOrElse { log.error("Failed to trigger relay sweep: ${it.message}") }
    }

    override suspend fun onReceive(m: RelayProtocol): Behavior<RelayProtocol.Response> {
        when (m) {
            is RelayProtocol.Enqueue -> {
                val stored = runCatching { relayService.db { save(m.entry) } }
                    .getOrElse {
                        log.error("Failed to enqueue relay entry: ${it.message}")
                        throw it // surface to the asker so it can mark the send FAILED
                    }
                return Behavior.Reply(RelayProtocol.Stored(stored))
            }

            is RelayProtocol.ReserveSpray -> {
                val reserved = runCatching { relayService.tx { reserveSprayCopy(m.entryId) } }
                    .getOrElse {
                        log.error("Failed to reserve spray for ${m.entryId}: ${it.message}")
                        null
                    }
                return Behavior.Reply(RelayProtocol.Reserved(reserved))
            }

            is RelayProtocol.Delivered -> {
                runCatching { relayService.db { deleteById(m.entryId) } }
                    .onFailure { log.error("Failed to delete delivered relay entry ${m.entryId}: ${it.message}") }
            }

            is RelayProtocol.Sweep -> {
                runCatching { relayService.db { deleteExpired(Clock.System.now()) } }
                    .onFailure { log.warn("Lifecycle sweep failed: ${it.message}") }
            }
        }
        return Behavior.Reply(RelayProtocol.Ok)
    }

    override suspend fun onShutdown() {
        timer.cancel()
    }

    companion object {
        // Keyed by the owning node's peerId. In production there is one node, so one RelayActor; the
        // global actor registry is a single namespace shared across nodes in multi-node tests, so the
        // owner must be encoded in the key to keep each node bound to its own DB (mirrors AppActor /
        // PeerActor).
        fun key(ownerPeerId: String): String = ownerPeerId

        // The sweep is a single cheap DELETE, so the exact cadence barely matters (replica TTLs are
        // days). Kept below ActorSystem.actorExpiresAfter (5 min) so the tick doubles as a keep-alive:
        // an actively-used queue's owner stays warm and the periodic sweep always runs.
        private val SWEEP_INTERVAL: Duration = 1.minutes
    }
}
