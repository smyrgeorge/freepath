package io.github.smyrgeorge.freepath.share

import io.github.smyrgeorge.actor4k.actor.Actor
import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.freepath.content.Content
import io.github.smyrgeorge.freepath.database.ContentEntryRepository
import io.github.smyrgeorge.freepath.database.ContentSyncEntry
import io.github.smyrgeorge.freepath.database.ContentSyncEntryRepository
import io.github.smyrgeorge.freepath.libnet.client.LibnetClient
import io.github.smyrgeorge.freepath.state.AbstractAppResources
import io.github.smyrgeorge.freepath.state.AbstractAppState
import io.github.smyrgeorge.freepath.state.abbrev
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlin.time.Clock

class PeerActor(
    key: String,
    state: AbstractAppState,
    resources: AbstractAppResources,
) : Actor<PeerProtocol, PeerProtocol.Response>(key) {

    private val peerId: String get() = key

    private val db: ISQLite = resources.db
    private val client: LibnetClient = resources.client
    private val contactContent: Content = state.contactContent
    private val contentRepository: ContentEntryRepository = resources.contentEntryRepository
    private val syncRepository: ContentSyncEntryRepository = resources.contentSyncRepository

    override suspend fun onReceive(m: PeerProtocol): Behavior<PeerProtocol.Response> {
        when (m) {
            is PeerProtocol.Sync -> sync()
        }
        return Behavior.Reply(PeerProtocol.Ok)
    }

    private suspend fun sync() {
        sync(contactContent)

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
            }.onFailure { log.warn("[${peerId.abbrev()}] Failed to send ${content.id}: ${it.message}") }
    }
}
