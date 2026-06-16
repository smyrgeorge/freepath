package io.github.smyrgeorge.freepath.core.state.service

import io.github.smyrgeorge.freepath.database.RelayEntry
import io.github.smyrgeorge.freepath.database.RelayEntryRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class RelayService(
    override val db: ISQLite,
    private val relayRepository: RelayEntryRepository,
) : Service {

    context(db: QueryExecutor)
    suspend fun findAll(limit: Int): List<RelayEntry> =
        relayRepository.findAllByLimit(limit).getOrThrow()

    context(db: QueryExecutor)
    suspend fun save(entry: RelayEntry): RelayEntry =
        relayRepository.save(entry).getOrThrow()

    context(db: Transaction)
    suspend fun reserveSprayCopy(entryId: Int): RelayEntry? {
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

    context(db: Transaction)
    suspend fun deleteAll() {
        relayRepository.deleteAll().getOrThrow()
    }

    companion object {
        val MAX_TTL_DURATION: Duration = 7.days
    }
}
