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

    // All relay logic lives in the node-wide RelayActor (the single owner of the relay queue and the
    // copy budget). On connect/identify we only nudge it to run a relay pass for this peer; the
    // forwarding decisions, copy accounting and sends happen there. Resolved per use — it is a
    // node-wide singleton.
    private suspend fun relayActor() = ActorSystem.get(RelayActor::class, RelayActor.key(ownerPeerId))

    // TODO: Make sync smarter — redundant work on every reconnect:
    //   1. sync() walks the whole feed on every reconnect (no per-peer high-water mark or digest).
    //      It doesn't re-send — the ContentSyncEntry version check prevents that — but it still walks.
    //   2. Connected + Identified both trigger a full pass; Identified follows Connected by seconds.
    // Target: advertise-then-pull — exchange a compact digest (list of (contentId, version) for
    // content; bloom/merkle for the relay queue), peer replies with what it wants, push only that.
    // Essentially gossipsub's IHAVE/IWANT applied point-to-point over request_response.
    // Cheap pre-protocol-change wins:
    //   - Drop sync on Connected; keep only Identified (or debounce).
    //   - Per-peer last_content_sync_at → only walk content modified after it.
    override suspend fun onReceive(m: PeerProtocol): Behavior<PeerProtocol.Response> {
        when (m) {
            is PeerProtocol.Connected -> {
                relay()
                sync()
            }

            is PeerProtocol.Identified -> {
                sync(contactContent)
                relay()
                sync()
            }
        }
        return Behavior.Reply(PeerProtocol.Ok)
    }

    /** Hand the relay pass for this peer to the RelayActor (fire-and-forget; it owns the queue). */
    private suspend fun relay() {
        relayActor().tell(RelayProtocol.RelayToPeer(peerId))
            .onFailure { log.warn("[${peerId.abbrev()}] Failed to trigger relay: ${it.message}") }
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
