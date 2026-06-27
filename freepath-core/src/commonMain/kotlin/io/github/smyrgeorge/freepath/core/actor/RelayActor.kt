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
import io.github.smyrgeorge.log4k.impl.extensions.doEvery
import kotlinx.coroutines.Job
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The single owner of the store-and-forward relay queue and the home of ALL relay logic. Keyed per
 * owning node, so every copy-budget mutation (enqueue, distribute reservation, delivery-delete) and every
 * forwarding decision is serialized through one mailbox — no lock needed, and concurrent distributes of
 * the same replica from many connection events can never hand out more than the budget `L`.
 *
 * Triggers come from two places, both of which delegate here rather than touching the queue
 * themselves:
 *  - [PeerActor] sends [RelayProtocol.RelayToPeer] when a peer connects / is identified.
 *  - `AbstractAppState.relayMessage` sends [RelayProtocol.Distribute] when a message is freshly queued.
 */
class RelayActor(
    key: String,
    resources: AbstractAppResources,
) : Actor<RelayProtocol, RelayProtocol.Response>(key) {

    private val relayService: RelayService = resources.relayService
    private val contactService: ContactService = resources.contactService

    // lateinit-backed: resolved lazily so the actor can be constructed before networking is up.
    private val client: LibnetClient by lazy { resources.client }
    private val libnet: LibnetModule by lazy { resources.libnet }

    // Per-peer distribute-and-wait dedup: relay-entry ids already distributed to a given peerId, so we never
    // re-distribute — and thus re-halve — the same replica to the same peer. Lives here (not in PeerActor)
    // now that the relay pass runs here; pruned to the live queue on every pass. Access is
    // single-threaded (mailbox), so a plain map is safe.
    private val offered = mutableMapOf<String, MutableSet<Int>>()

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

            is RelayProtocol.Sweep -> {
                runCatching { relayService.db { deleteExpired(Clock.System.now()) } }
                    .onFailure { log.warn("Lifecycle sweep failed: ${it.message}") }
            }

            is RelayProtocol.RelayToPeer -> relayTo(m.peerId)

            is RelayProtocol.Distribute -> {
                // Shuffle so copies fan out to a random subset of carriers. Binary distribute-and-wait
                // halves the budget per peer, so only the first ~log2(copies) peers to reserve get a
                // copy at all; with a stable order that would always be the same few carriers.
                // (When the encounter heuristic §7 is wired in, this becomes a ranked order.)
                val online = libnet.onlinePeerIds().shuffled()
                online.forEach { relayTo(it) }
                return Behavior.Reply(RelayProtocol.Distributed(online.size))
            }
        }
        return Behavior.Reply(RelayProtocol.Ok)
    }

    /**
     * Run the relay pass for a single connected [peerId]: deliver replicas addressed to it (Pass 1),
     * then distribute the rest onward via binary distribute-and-wait (Pass 2).
     *
     * TODO: an unidentified peer (no contact card → `peerIdHash == null`) currently receives the
     *   full mailbox in Pass 2 — privacy issue; tighten when revisiting. Persisting [offered] across
     *   restarts is only an optimization (the copy budget already caps re-distribute).
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

        // Drop dedup ids for replicas that are gone (delivered / expired / swept), keeping the
        // in-memory set bounded by the live queue.
        val liveIds = entries.mapTo(HashSet()) { it.id }
        val offeredToPeer = offered.getOrPut(peerId) { mutableSetOf() }.apply { retainAll(liveIds) }

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
                offeredToPeer += entry.id   // copies already spent — never re-reserve for this peer

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
