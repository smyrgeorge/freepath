package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.Actor
import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.freepath.core.state.AbstractAppResources
import io.github.smyrgeorge.freepath.core.state.abbrev
import io.github.smyrgeorge.freepath.core.state.service.ContactService
import io.github.smyrgeorge.freepath.core.state.service.RelayService
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.db
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.tx
import io.github.smyrgeorge.freepath.database.RelayEntry
import io.github.smyrgeorge.freepath.libnet.LibnetModule
import io.github.smyrgeorge.freepath.libnet.client.LibnetClient
import io.github.smyrgeorge.freepath.util.doEvery
import kotlinx.coroutines.Job
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class RelayActor(
    key: String,
    resources: AbstractAppResources,
) : Actor<RelayProtocol, RelayProtocol.Response>(key) {

    private val relayService: RelayService = resources.relayService
    private val contactService: ContactService = resources.contactService

    private val client: LibnetClient by lazy { resources.client }
    private val libnet: LibnetModule by lazy { resources.libnet }

    private val timer: Job = doEvery(SWEEP_INTERVAL) {
        tell(RelayProtocol.Sweep).getOrElse { log.error("Failed to trigger relay sweep: ${it.message}") }
    }

    override suspend fun onReceive(m: RelayProtocol): Behavior<RelayProtocol.Response> {
        when (m) {
            is RelayProtocol.Sweep -> {
                runCatching {
                    relayService.db { deleteExpired(Clock.System.now()) }
                    // Reap dedup rows whose replica is gone (delivered / swept) — FKs are not enforced.
                    relayService.db { deleteOrphanedOffered() }
                }.onFailure { log.warn("Lifecycle sweep failed: ${it.message}") }
            }

            is RelayProtocol.RelayToPeer -> relayTo(m.peerId)

            is RelayProtocol.Distribute -> {
                val stored = runCatching { relayService.db { save(m.entry) } }
                    .getOrElse {
                        log.error("Failed to enqueue relay entry: ${it.message}")
                        throw it // surface to the asker so it can mark the send FAILED
                    }
                // Never hand the copy back to the peer we received it from: mark it already-offered to
                // that source. Without this the budget would halve an extra time per hop (bounce-back)
                // and multi-hop delivery would starve before reaching the destination.
                m.fromPeerId?.let { relayService.db { markOffered(stored.id, it) } }
                val reached = distributeToOnline()
                if (reached == 0) log.info("No peers online; queued relay copy (entry ${stored.id})")
                else log.info("Queued relay copy (entry ${stored.id}); distributed to $reached online peer(s)")
                return Behavior.Reply(RelayProtocol.Distributed(reached))
            }
        }
        return Behavior.Reply(RelayProtocol.Ok)
    }

    /**
     * Distribute the whole relay queue to every peer reachable right now; returns the peer count.
     * Shuffle so copies fan out to a random subset of carriers — binary distribute-and-wait halves the
     * budget per peer, so only the first ~log2(copies) peers to reserve get a copy; with a stable order
     * that would always be the same few. (When the encounter heuristic §7 is wired in, this becomes a
     * ranked order instead of random.)
     */
    private suspend fun distributeToOnline(): Int {
        val online = libnet.onlinePeerIds().shuffled()
        online.forEach { relayTo(it) }
        return online.size
    }

    /**
     * Run the relay pass for a single connected [peerId]: deliver replicas addressed to it (Pass 1),
     * then distribute the rest onward via binary distribute-and-wait (Pass 2).
     *
     * TODO: an unidentified peer (no contact card → `peerIdHash == null`) currently receives the
     *   full mailbox in Pass 2 — privacy issue; tighten when revisiting.
     */
    private suspend fun relayTo(peerId: String) {
        // Highest priority first: under limited airtime, more important messages move sooner.
        // sortedByDescending is stable, so equal-priority entries keep their id-ascending order.
        val entries = runCatching { relayService.db { findAll(RELAY_FETCH_LIMIT) } }
            .getOrElse {
                log.error("[${peerId.abbrev()}] Failed to load relay queue: ${it.message}")
                return
            }
            .sortedByDescending { it.envelope.relay?.priority ?: 0 }
        if (entries.isEmpty()) return

        // Durable distribute-and-wait dedup (RelayService / DB): replicas already offered to this
        // peer, so we never re-offer — and re-halve — the same replica. Survives app restarts; stale
        // rows for deleted replicas are reaped by the sweep, so no pruning is needed here.
        val offeredToPeer = relayService.db { offeredEntryIds(peerId) }.toMutableSet()

        // Look up this peer's receiverIdHash to distinguish Pass 1 (for them) vs Pass 2 (forward on).
        val peerIdHash: ByteArray? = runCatching { contactService.db { getByPeerId(peerId) } }
            .getOrNull()?.contact?.peerIdHash

        // Pass 1: deliver packets intended for this peer (final delivery — no copy accounting).
        // TODO: when authenticated delivery ACKs land, delete on ACK rather than on successful
        //   hand-off, so a re-encounter can't resurrect-then-redeliver. For now we delete on delivery
        //   to avoid infinite redelivery.
        if (peerIdHash != null) {
            entries
                .filter { it.envelope.receiverIdHash.contentEquals(peerIdHash) }
                .forEach { entry -> deliver(entry, peerId) }
        }

        // Pass 2: distribute packets not intended for this peer onward — binary distribute-and-wait.
        // If peerIdHash is unknown (unidentified peer), treat every entry as forwardable.
        // reserveCopy atomically decides distribute vs wait and reserves the copies before we send; we
        // never distribute the same replica to this peer twice (offeredToPeer).
        entries
            .filter { peerIdHash == null || !it.envelope.receiverIdHash.contentEquals(peerIdHash) }
            .forEach { entry ->
                if (entry.id in offeredToPeer) return@forEach

                // null → wait phase / entry gone → hold the replica for direct delivery only.
                val distributed = reserveCopy(entry.id) ?: return@forEach
                offeredToPeer += entry.id                            // within-pass dedup
                relayService.db { markOffered(entry.id, peerId) }    // durable dedup (survives restarts)

                // Reserve-before-send: a failed send forfeits the reserved copies (acceptable
                // per-attempt anti-retry-storm semantics).
                client.relay(distributed.envelope, peerId)
                    .onSuccess { log.info("[${peerId.abbrev()}] Distributed ${distributed.copies} copy/copies of ${entry.id}") }
                    .onFailure { log.warn("[${peerId.abbrev()}] Failed to distribute ${entry.id}: ${it.message}") }
            }
    }

    /** Deliver a replica addressed to [peerId] and drop it on a successful hand-off. */
    private suspend fun deliver(entry: RelayEntry, peerId: String) {
        client.relay(entry.envelope, peerId)
            .onSuccess {
                delete(entry.id)
                log.info("[${peerId.abbrev()}] Delivered relay packet ${entry.id}")
            }
            .onFailure { log.warn("[${peerId.abbrev()}] Failed to deliver relay packet ${entry.id}: ${it.message}") }
    }

    /** Atomically reserve the distributed half of an entry's copy budget; null in the wait phase / gone. */
    private suspend fun reserveCopy(entryId: Int): RelayEntry? =
        runCatching { relayService.tx { reserveCopy(entryId) } }
            .getOrElse {
                log.error("Failed to reserve distribute for $entryId: ${it.message}")
                null
            }

    /** Drop a replica from the queue (idempotent). */
    private suspend fun delete(entryId: Int) {
        runCatching { relayService.db { deleteById(entryId) } }
            .onFailure { log.error("Failed to delete relay entry $entryId: ${it.message}") }
    }

    override suspend fun onShutdown() {
        timer.cancel()
    }

    companion object {
        // Page size for a single relay pass — bounds work per (re)connection event.
        const val RELAY_FETCH_LIMIT: Int = 256

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
