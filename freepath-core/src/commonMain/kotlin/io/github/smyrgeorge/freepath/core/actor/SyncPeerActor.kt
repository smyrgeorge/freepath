package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.Actor
import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.freepath.core.state.AbstractAppResources
import io.github.smyrgeorge.freepath.core.state.AbstractAppState
import io.github.smyrgeorge.freepath.core.state.abbrev
import io.github.smyrgeorge.freepath.core.state.service.ContactService
import io.github.smyrgeorge.freepath.core.state.service.ContentService
import io.github.smyrgeorge.freepath.core.state.service.RelayService
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.db
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.tx
import io.github.smyrgeorge.freepath.database.ContentSyncEntry
import io.github.smyrgeorge.freepath.libnet.client.LibnetClient
import io.github.smyrgeorge.freepath.model.content.Content
import kotlin.time.Clock

class SyncPeerActor(
    key: String,
    state: AbstractAppState,
    resources: AbstractAppResources,
) : Actor<SyncPeerProtocol, SyncPeerProtocol.Response>(key) {

    private val peerId: String get() = remotePeerIdOf(key)
    private val contactContent = state.contactContent

    private val client: LibnetClient = resources.client
    private val contactService: ContactService = resources.contactService
    private val contentService: ContentService = resources.contentService
    private val relayService: RelayService = resources.relayService

    // TODO: Make sync/relay smarter — three flood sources today:
    //   1. Pass 2 re-forwards the same mesh packets to every reconnecting peer (no per-peer dedup).
    //   2. sync() walks all content on every reconnect (no per-peer high-water mark or digest).
    //   3. Connected + Identified both trigger a full pass; Identified follows Connected by seconds.
    // Target: advertise-then-pull — exchange a compact digest (list of (contentId, version) for
    // content; bloom/merkle for the relay queue), peer replies with what it wants, push only that.
    // Essentially gossipsub's IHAVE/IWANT applied point-to-point over request_response.
    // Cheap pre-protocol-change wins:
    //   - (relay_entry_id, peer_id) already-offered table → Pass 2 dedup.
    //   - Drop sync on Connected; keep only Identified (or debounce).
    //   - Per-peer last_content_sync_at → only walk content modified after it.
    // Also tighten: unidentified peers currently receive the full mailbox (peerIdHash == null
    // branch in relay()) — privacy issue, fix when revisiting.
    override suspend fun onReceive(m: SyncPeerProtocol): Behavior<SyncPeerProtocol.Response> {
        when (m) {
            is SyncPeerProtocol.Connected -> {
                relay()
                sync()
            }

            is SyncPeerProtocol.Identified -> {
                sync(contactContent)
                relay()
                sync()
            }
        }
        return Behavior.Reply(SyncPeerProtocol.Ok)
    }

    private suspend fun relay() {
        val entries = runCatching { relayService.db { findAll(RELAY_FETCH_LIMIT) } }
            .getOrElse {
                log.error("[${peerId.abbrev()}] Failed to load relay queue: ${it.message}")
                return
            }

        // Look up this peer's receiverIdHash to distinguish Pass 1 (for them) vs Pass 2 (mesh hops).
        val peerIdHash: ByteArray? = runCatching { contactService.db { getByPeerId(peerId) } }
            .getOrNull()?.contact?.peerIdHash

        // Pass 1: deliver packets intended for this peer.
        // No TTL enforcement here — this is final delivery, not mesh forwarding.
        if (peerIdHash != null) {
            entries
                .filter { it.envelope.receiverIdHash.contentEquals(peerIdHash) }
                .forEach { entry ->
                    client.relay(entry.envelope, peerId)
                        .onSuccess {
                            runCatching {
                                relayService.db { deleteById(entry.id) }
                            }.onFailure {
                                log.error("[${peerId.abbrev()}] Failed to delete relay entry ${entry.id}: ${it.message}")
                            }
                            log.info("[${peerId.abbrev()}] Delivered relay packet ${entry.id}")
                        }
                        .onFailure {
                            log.warn("[${peerId.abbrev()}] Failed to deliver relay packet ${entry.id}: ${it.message}")
                        }
                }
        }

        // Pass 2: forward mesh hop packets (not intended for this peer).
        // If peerIdHash is unknown (unidentified peer), forward all entries as mesh hops.
        // RelayService owns the TTL policy: it decrements + persists the TTL before the send
        // (per-attempt semantics, to prevent retry storms) or discards an entry whose TTL is
        // exhausted. A null result means "exhausted and discarded — nothing to forward". On a
        // DB failure we skip the send rather than forward with a stale TTL.
        entries
            .filter { peerIdHash == null || !it.envelope.receiverIdHash.contentEquals(peerIdHash) }
            .forEach { entry ->
                val forward = runCatching { relayService.db { decrementTtlOrDiscard(entry) } }
                    .getOrElse {
                        log.error("[${peerId.abbrev()}] Failed to advance TTL for relay entry ${entry.id}: ${it.message}")
                        return@forEach
                    } ?: return@forEach

                client.relay(forward.envelope, peerId)
                    .onSuccess { log.info("[${peerId.abbrev()}] Forwarded mesh hop ${entry.id} (ttl=${forward.envelope.relay?.ttl})") }
                    .onFailure { log.warn("[${peerId.abbrev()}] Failed to forward mesh hop ${entry.id}: ${it.message}") }
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
        const val RELAY_FETCH_LIMIT = 256

        // The actor registry is a single global namespace. A SyncPeerActor is owned by the local
        // node and scoped to one remote peer, so its key must encode BOTH — otherwise two nodes
        // talking to the same remote peer (any relay/hub topology) would collide on the same key.
        // In production there is one node, so the owner is always "self" and behaviour is unchanged.
        // peerIds are Base58 (libp2p), so ':' never appears in them and is a safe separator.
        private const val KEY_SEPARATOR = ":"

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
