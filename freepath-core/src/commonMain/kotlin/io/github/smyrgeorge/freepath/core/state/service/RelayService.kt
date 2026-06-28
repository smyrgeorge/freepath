package io.github.smyrgeorge.freepath.core.state.service

import io.github.smyrgeorge.freepath.database.RelayEntry
import io.github.smyrgeorge.freepath.database.RelayEntryRepository
import io.github.smyrgeorge.freepath.database.RelayOfferedEntry
import io.github.smyrgeorge.freepath.database.RelayOfferedEntryRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class RelayService(
    override val db: ISQLite,
    private val relayRepository: RelayEntryRepository,
    private val relayOfferedRepository: RelayOfferedEntryRepository,
) : Service {

    context(db: QueryExecutor)
    suspend fun findAll(limit: Int): List<RelayEntry> =
        relayRepository.findAllByLimit(limit).getOrThrow()

    context(db: QueryExecutor)
    suspend fun save(entry: RelayEntry): RelayEntry =
        relayRepository.save(entry).getOrThrow()

    context(db: Transaction)
    suspend fun reserveCopy(entryId: Int): RelayEntry? {
        val current = relayRepository.findOneById(entryId).getOrThrow() ?: return null
        // Wait phase: a single copy left → hold it for direct delivery only.
        if (current.copies <= 1) return null

        val transferred = current.copies / 2
        val kept = current.copies - transferred
        relayRepository.save(current.copy(copies = kept)).getOrThrow()

        val relay = current.envelope.relay ?: error("Relay entry $entryId has no relay metadata")
        return current.copy(
            copies = transferred,
            envelope = current.envelope.copy(relay = relay.copy(copies = transferred)),
        )
    }

    context(db: QueryExecutor)
    suspend fun deleteById(id: Int) {
        relayRepository.deleteById(id).getOrThrow()
    }

    context(db: QueryExecutor)
    suspend fun deleteExpired(now: Instant): Long =
        relayRepository.executeDeleteExpired(
            now = now.toEpochMilliseconds(),
            minCreatedAt = (now - MAX_TTL_DURATION).toEpochMilliseconds(),
        ).getOrThrow()

    // ── distribute-and-wait dedup (durable, survives restarts) ──────────────────

    /** All relay-entry ids already offered to [peerId] — the persisted dedup set for a relay pass. */
    context(db: QueryExecutor)
    suspend fun offeredEntryIds(peerId: String): Set<Int> =
        relayOfferedRepository.findAllByPeerId(peerId).getOrThrow().mapTo(mutableSetOf()) { it.relayEntryId }

    /**
     * Record that [entryId] has been offered to [peerId], so it is never re-offered (which would
     * re-halve the copy budget). Idempotent: the `(relay_entry_id, peer_id)` unique index makes a
     * repeat a no-op, so the rejected duplicate insert is ignored.
     */
    context(db: QueryExecutor)
    suspend fun markOffered(entryId: Int, peerId: String) {
        relayOfferedRepository.insert(RelayOfferedEntry(relayEntryId = entryId, peerId = peerId))
    }

    /** Reap offered rows whose replica is gone (delivered / swept). Returns the rows removed. */
    context(db: QueryExecutor)
    suspend fun deleteOrphanedOffered(): Long =
        relayOfferedRepository.executeDeleteOrphaned().getOrThrow()

    context(db: Transaction)
    suspend fun deleteAll() {
        relayRepository.deleteAll().getOrThrow()
        relayOfferedRepository.deleteAll().getOrThrow()
    }

    companion object {
        val MAX_TTL_DURATION: Duration = 7.days
    }
}
