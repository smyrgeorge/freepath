package io.github.smyrgeorge.freepath.core.state.service

import io.github.smyrgeorge.freepath.database.RelayEntry
import io.github.smyrgeorge.freepath.database.RelayEntryRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

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

    /**
     * Applies mesh-forwarding TTL policy to [entry] and returns the entry to forward, or `null`
     * if it should not be forwarded (TTL exhausted — the entry is discarded from the queue).
     *
     * Per-attempt semantics: TTL counts forward *attempts*, not successful deliveries, so it is
     * decremented and persisted here — before the send — to prevent retry storms. A repeatedly
     * failing entry still ages out and is eventually discarded rather than forwarded forever.
     *
     * The caller forwards the returned entry's [RelayEntry.envelope]; the decremented TTL is
     * authoritative in `envelope.relay.ttl` (the [RelayEntry.ttl] column is generated/read-only).
     */
    context(db: QueryExecutor)
    suspend fun decrementTtlOrDiscard(entry: RelayEntry): RelayEntry? {
        if (entry.ttl <= 0) {
            deleteById(entry.id)
            return null
        }
        val relay = entry.envelope.relay
            ?: error("Relay entry ${entry.id} has no relay metadata")
        val updated = entry.copy(
            envelope = entry.envelope.copy(relay = relay.copy(ttl = entry.ttl - 1))
        )
        return save(updated)
    }

    context(db: QueryExecutor)
    suspend fun deleteById(id: Int) {
        relayRepository.deleteById(id).getOrThrow()
    }

    context(db: Transaction)
    suspend fun deleteAll() {
        relayRepository.deleteAll().getOrThrow()
    }
}
