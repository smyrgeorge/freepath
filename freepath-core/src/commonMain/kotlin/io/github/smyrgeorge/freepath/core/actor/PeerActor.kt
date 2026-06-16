package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.Actor
import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.actor4k.system.ActorSystem
import io.github.smyrgeorge.freepath.core.state.AbstractAppResources
import io.github.smyrgeorge.freepath.core.state.AbstractAppState
import io.github.smyrgeorge.freepath.core.state.abbrev
import io.github.smyrgeorge.freepath.core.state.service.ContactEncounterService
import io.github.smyrgeorge.freepath.core.state.service.ContactService
import io.github.smyrgeorge.freepath.core.state.service.ContentService
import io.github.smyrgeorge.freepath.core.state.service.RelayService
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.db
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.tx
import io.github.smyrgeorge.freepath.database.ContentSyncEntry
import io.github.smyrgeorge.freepath.libnet.client.LibnetClient
import io.github.smyrgeorge.freepath.model.content.Content
import kotlin.time.Clock

class PeerActor(
    key: String,
    state: AbstractAppState,
    resources: AbstractAppResources,
) : Actor<PeerProtocol, PeerProtocol.Response>(key) {

    private val peerId: String get() = remotePeerIdOf(key)
    private val ownerPeerId: String get() = ownerPeerIdOf(key)
    private val contactContent = state.contactContent

    private val client: LibnetClient = resources.client
    private val contactService: ContactService = resources.contactService
    private val contactEncounterService: ContactEncounterService = resources.contactEncounterService
    private val contentService: ContentService = resources.contentService
    private val relayService: RelayService = resources.relayService

    // The relay queue's single owner: all copy-budget mutations (reserve, delivery-delete) go
    // through it so the Spray-and-Wait accounting is serialized without a lock. We only
    // read the queue (findAll) directly. Resolved per use — it is a node-wide singleton.
    private suspend fun relayActor() = ActorSystem.get(RelayActor::class, RelayActor.key(ownerPeerId))

    // Per-(this peer) Spray-and-Wait dedup: relay entry ids already sprayed to this peer during the
    // lifetime of this actor. Scoped to one peer (the actor is keyed per remote peer), so the entry
    // id alone is enough. Prevents re-spraying — and thus re-halving — the same replica to the same
    // peer within a session.
    private val offered = mutableSetOf<Int>()

    // TODO: Make sync/relay smarter — redundant work on every reconnect:
    //   1. sync() walks the whole feed on every reconnect (no per-peer high-water mark or digest).
    //      It doesn't re-send — the ContentSyncEntry version check prevents that — but it still walks.
    //   2. Connected + Identified both trigger a full pass; Identified follows Connected by seconds.
    // Target: advertise-then-pull — exchange a compact digest (list of (contentId, version) for
    // content; bloom/merkle for the relay queue), peer replies with what it wants, push only that.
    // Essentially gossipsub's IHAVE/IWANT applied point-to-point over request_response.
    // Cheap pre-protocol-change wins:
    //   - Drop sync on Connected; keep only Identified (or debounce).
    //   - Per-peer last_content_sync_at → only walk content modified after it.
    //   - Persist the in-memory `offered` set so Pass 2 dedup survives an actor restart (today it
    //     resets on respawn; the copy budget still caps re-spray, so this is only an optimization).
    // Also tighten: unidentified peers currently receive the full mailbox (peerIdHash == null
    // branch in relay()) — privacy issue, fix when revisiting.
    override suspend fun onReceive(m: PeerProtocol): Behavior<PeerProtocol.Response> {
        when (m) {
            is PeerProtocol.Connected -> {
                relay()
                sync()
            }

            is PeerProtocol.Identified -> {
                recordEncounter()
                sync(contactContent)
                relay()
                sync()
            }

            is PeerProtocol.Relay -> relay()
        }
        return Behavior.Reply(PeerProtocol.Ok)
    }

    private suspend fun recordEncounter() {
        runCatching { contactEncounterService.db { recordEncounter(peerId) } }
            .onFailure { log.warn("[${peerId.abbrev()}] Failed to record encounter: ${it.message}") }
    }

    private suspend fun relay() {
        // Highest priority first: under limited airtime, more important messages move sooner.
        // sortedByDescending is stable, so equal-priority entries keep their id-ascending order.
        val entries = runCatching { relayService.db { findAll(RELAY_FETCH_LIMIT) } }
            .getOrElse {
                log.error("[${peerId.abbrev()}] Failed to load relay queue: ${it.message}")
                return
            }
            .sortedByDescending { it.envelope.relay?.priority ?: 0 }

        // Look up this peer's receiverIdHash to distinguish Pass 1 (for them) vs Pass 2 (forward onward).
        val peerIdHash: ByteArray? = runCatching { contactService.db { getByPeerId(peerId) } }
            .getOrNull()?.contact?.peerIdHash

        // Pass 1: deliver packets intended for this peer (final delivery — no copy accounting).
        // TODO: when authenticated delivery ACKs land, delete on ACK rather than on successful
        //   hand-off, so a re-encounter can't resurrect-then-redeliver. For now we delete on delivery
        //   to avoid infinite redelivery.
        if (peerIdHash != null) {
            entries
                .filter { it.envelope.receiverIdHash.contentEquals(peerIdHash) }
                .forEach { entry ->
                    client.relay(entry.envelope, peerId)
                        .onSuccess {
                            relayActor().tell(RelayProtocol.Delivered(entry.id))
                                .onFailure {
                                    log.error("[${peerId.abbrev()}] Failed to delete relay entry ${entry.id}: ${it.message}")
                                }
                            log.info("[${peerId.abbrev()}] Delivered relay packet ${entry.id}")
                        }
                        .onFailure {
                            log.warn("[${peerId.abbrev()}] Failed to deliver relay packet ${entry.id}: ${it.message}")
                        }
                }
        }

        // Pass 2: spray packets not intended for this peer onward — binary Spray-and-Wait.
        // If peerIdHash is unknown (unidentified peer), treat every entry as forwardable.
        // RelayActor atomically decides spray vs wait and reserves the copies before we send
        // (its mailbox serializes the budget across this node's actors); we never spray the same
        // replica to this peer twice (offered).
        entries
            .filter { peerIdHash == null || !it.envelope.receiverIdHash.contentEquals(peerIdHash) }
            .forEach { entry ->
                if (entry.id in offered) return@forEach

                // Atomically reserve the sprayed copies (RelayActor re-reads the current budget and
                // serializes the read-modify-write via its mailbox).
                // null → wait phase / entry gone → hold the replica for direct delivery only.
                val sprayed = relayActor().ask(RelayProtocol.ReserveSpray(entry.id))
                    .getOrElse {
                        log.error("[${peerId.abbrev()}] Failed to reserve spray for ${entry.id}: ${it.message}")
                        return@forEach
                    }.entry ?: return@forEach
                offered += entry.id   // copies already spent — never re-reserve for this peer

                client.relay(sprayed.envelope, peerId)
                    .onSuccess { log.info("[${peerId.abbrev()}] Sprayed ${sprayed.copies} copy/copies of ${entry.id}") }
                    .onFailure { log.warn("[${peerId.abbrev()}] Failed to spray ${entry.id}: ${it.message}") }
            }
    }

    private suspend fun sync() {
        // Contact should exist at this point (peer was identified), but guard defensively.
        runCatching { contactService.db { getByPeerId(peerId) } }.getOrNull() ?: run {
            log.warn("[${peerId.abbrev()}] Sync skipped — no contact entry found after Identified")
            return
        }

        // Sync all other stored content, page by page.
        var offset = 0
        val pageSize = 50
        while (true) {
            val page = runCatching { contentService.db { getFeed(pageSize, offset) } }.getOrElse {
                log.error("[${peerId.abbrev()}] Failed to load content page at offset $offset: ${it.message}")
                break
            }
            if (page.isEmpty()) break
            page.filter { it.authorId != peerId }.forEach { entry -> sync(entry.content) }
            if (page.size < pageSize) break
            offset += pageSize
        }
    }

    private suspend fun sync(content: Content) {
        val existing = runCatching { contentService.db { getSyncEntry(peerId, content.id) } }.getOrNull()
        if (existing != null && existing.version >= content.version) return

        client.send(content, peerId)
            .onSuccess {
                val entry = existing
                    ?.copy(version = content.version, syncedAt = Clock.System.now())
                    ?: ContentSyncEntry(peerId = peerId, contentId = content.id, version = content.version)
                runCatching { contentService.tx { saveSyncEntry(entry) } }
                    .onSuccess { log.info("[${peerId.abbrev()}] Synced ${content.id} v${content.version}") }
                    .onFailure { log.error("[${peerId.abbrev()}] Failed to save sync entry: ${it.message}") }
            }
            .onFailure { log.warn("[${peerId.abbrev()}] Failed to send ${content.id}: ${it.message}") }
    }

    companion object {
        const val RELAY_FETCH_LIMIT: Int = 256

        // The actor registry is a single global namespace. A PeerActor is owned by the local
        // node and scoped to one remote peer, so its key must encode BOTH — otherwise two nodes
        // talking to the same remote peer (any relay/hub topology) would collide on the same key.
        // In production there is one node, so the owner is always "self" and behaviour is unchanged.
        // peerIds are Base58 (libp2p), so ':' never appears in them and is a safe separator.
        private const val KEY_SEPARATOR: String = ":"

        fun key(ownerPeerId: String, remotePeerId: String): String {
            require(!ownerPeerId.contains(KEY_SEPARATOR)) { "ownerPeerId must not contain $KEY_SEPARATOR" }
            require(!remotePeerId.contains(KEY_SEPARATOR)) { "remotePeerId must not contain $KEY_SEPARATOR" }
            return "$ownerPeerId$KEY_SEPARATOR$remotePeerId"
        }

        fun ownerPeerIdOf(key: String): String {
            require(key.contains(KEY_SEPARATOR)) { "key must contain $KEY_SEPARATOR" }
            return key.substringBeforeLast(KEY_SEPARATOR)
        }

        fun remotePeerIdOf(key: String): String {
            require(key.contains(KEY_SEPARATOR)) { "key must contain $KEY_SEPARATOR" }
            return key.substringAfterLast(KEY_SEPARATOR)
        }
    }
}
