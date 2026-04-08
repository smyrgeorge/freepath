package io.github.smyrgeorge.freepath.share

import io.github.smyrgeorge.actor4k.actor.Actor
import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.freepath.content.Content
import io.github.smyrgeorge.freepath.database.ContactEntryRepository
import io.github.smyrgeorge.freepath.database.ContentEntryRepository
import io.github.smyrgeorge.freepath.database.ContentSyncEntry
import io.github.smyrgeorge.freepath.database.ContentSyncEntryRepository
import io.github.smyrgeorge.freepath.database.RelayEntryRepository
import io.github.smyrgeorge.freepath.libnet.client.LibnetClient
import io.github.smyrgeorge.freepath.libnet.client.codec.LibnetClientCodec
import io.github.smyrgeorge.freepath.libnet.client.codec.StatelessEnvelopeCodec
import io.github.smyrgeorge.freepath.state.AbstractAppResources
import io.github.smyrgeorge.freepath.state.AbstractAppState
import io.github.smyrgeorge.freepath.state.abbrev
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlin.time.Clock

class ConnectedPeerActor(
    key: String,
    state: AbstractAppState,
    resources: AbstractAppResources,
) : Actor<ConnectedPeerProtocol, ConnectedPeerProtocol.Response>(key) {

    private val peerId: String get() = key
    private val contactContent = state.contactContent

    private val db: ISQLite = resources.db
    private val client: LibnetClient = resources.client
    private val relayRepository: RelayEntryRepository = resources.relayRepository
    private val contactRepository: ContactEntryRepository = resources.contactRepository
    private val contentRepository: ContentEntryRepository = resources.contentRepository
    private val syncRepository: ContentSyncEntryRepository = resources.contentSyncRepository

    override suspend fun onReceive(m: ConnectedPeerProtocol): Behavior<ConnectedPeerProtocol.Response> {
        when (m) {
            is ConnectedPeerProtocol.Connected -> {
                relay()
                sync()
            }
            is ConnectedPeerProtocol.Identified -> {
                sync(contactContent)
                relay()
                sync()
            }
        }
        return Behavior.Reply(ConnectedPeerProtocol.Ok)
    }

    private suspend fun relay() {
        val entries = relayRepository.findAllByLimit(db, RELAY_FETCH_LIMIT)
            .getOrElse {
                log.error("[${peerId.abbrev()}] Failed to load relay queue: ${it.message}")
                return
            }

        // Pass 1: deliver packets intended for this peer.
        entries
            .filter { it.receiverPeerId == peerId }
            .forEach { entry ->
                client.relay(entry.payload, peerId)
                    .onSuccess {
                        relayRepository.deleteById(db, entry.id).onFailure {
                            log.error("[${peerId.abbrev()}] Failed to delete relay entry ${entry.id}: ${it.message}")
                        }
                        log.info("[${peerId.abbrev()}] Delivered relay packet ${entry.id}")
                    }
                    .onFailure {
                        log.warn("[${peerId.abbrev()}] Failed to deliver relay packet ${entry.id}: ${it.message}")
                    }
            }

        // Pass 2: forward mesh hop packets (not intended for this peer).
        entries
            .filter { it.receiverPeerId != peerId }
            .forEach { entry ->
                val envelope = LibnetClientCodec.decode(entry.payload)
                if (envelope == null) {
                    // Malformed payload — discard.
                    relayRepository.deleteById(db, entry.id).onFailure {
                        log.error("[${peerId.abbrev()}] Failed to delete malformed relay entry ${entry.id}: ${it.message}")
                    }
                    return@forEach
                }

                val relay = envelope.relay
                val ttl = relay?.ttl ?: 0
                if (ttl <= 0 || relay == null) {
                    // Expired or non-relay envelope — discard.
                    relayRepository.deleteById(db, entry.id).onFailure {
                        log.error("[${peerId.abbrev()}] Failed to delete expired relay entry ${entry.id}: ${it.message}")
                    }
                    return@forEach
                }

                // Decrement TTL, update DB, then forward. relay is non-null here.
                val decremented = envelope.copy(relay = relay.copy(ttl = ttl - 1))
                val newPayload = byteArrayOf(LibnetClientCodec.VERSION, 0, 0, 0) +
                        StatelessEnvelopeCodec.encode(decremented)
                val saveResult = relayRepository.save(db, entry.copy(payload = newPayload))
                if (saveResult.isFailure) {
                    log.error("[${peerId.abbrev()}] Failed to update TTL for relay entry ${entry.id}: ${saveResult.exceptionOrNull()?.message}")
                    return@forEach
                }
                client.relay(newPayload, peerId)
                    .onSuccess { log.info("[${peerId.abbrev()}] Forwarded mesh hop ${entry.id} (ttl=${ttl - 1})") }
                    .onFailure { log.warn("[${peerId.abbrev()}] Failed to forward mesh hop ${entry.id}: ${it.message}") }
            }
    }

    private suspend fun sync() {
        // Contact should exist at this point (peer was identified), but guard defensively.
        contactRepository.findOneByPeerId(db, peerId).getOrNull() ?: run {
            log.warn("[${peerId.abbrev()}] Sync skipped — no contact entry found after Identified")
            return
        }

        // Sync all other stored content, page by page.
        var offset = 0
        val pageSize = 50
        while (true) {
            val page = contentRepository.findAllByLimitAndOffset(db, pageSize, offset).getOrElse {
                log.error("[${peerId.abbrev()}] Failed to load content page at offset $offset: ${it.message}")
                break
            }
            if (page.isEmpty()) break
            page
                .filter { it.authorId != peerId }
                .forEach { entry -> sync(entry.content) }
            if (page.size < pageSize) break
            offset += pageSize
        }
    }

    private suspend fun sync(content: Content) {
        val existing = syncRepository.findOneByPeerIdAndContentId(db, peerId, content.id).getOrNull()
        if (existing != null && existing.version >= content.version) return

        client.send(content, peerId)
            .onSuccess {
                val entry = existing
                    ?.copy(version = content.version, syncedAt = Clock.System.now())
                    ?: ContentSyncEntry(peerId = peerId, contentId = content.id, version = content.version)
                syncRepository.save(db, entry)
                    .onSuccess { log.info("[${peerId.abbrev()}] Synced ${content.id} v${content.version}") }
                    .onFailure { log.error("[${peerId.abbrev()}] Failed to save sync entry: ${it.message}") }
            }
            .onFailure { log.warn("[${peerId.abbrev()}] Failed to send ${content.id}: ${it.message}") }
    }

    companion object {
        const val RELAY_FETCH_LIMIT = 256
    }
}
