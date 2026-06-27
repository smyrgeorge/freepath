package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.Auditable
import io.github.smyrgeorge.freepath.database.util.InstantConverter
import io.github.smyrgeorge.freepath.database.util.StatelessEnvelopeConverter
import io.github.smyrgeorge.freepath.libnet.client.model.StatelessEnvelope
import io.github.smyrgeorge.sqlx4k.annotation.Column
import io.github.smyrgeorge.sqlx4k.annotation.Converter
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Clock
import kotlin.time.Instant

@Table("relay")
data class RelayEntry(
    @Id
    override val id: Int = 0,
    @Converter(InstantConverter::class)
    override var createdAt: Instant = Clock.System.now(),
    @Converter(InstantConverter::class)
    override var updatedAt: Instant = Clock.System.now(),
    /** Full StatelessEnvelope stored as JSON. ByteArray fields are base64-encoded. */
    @Converter(StatelessEnvelopeConverter::class)
    val envelope: StatelessEnvelope,
    /**
     * Remaining copies for THIS replica (binary distribute-and-wait). Mutable: decremented and persisted
     * on each distribute. Seeded from `envelope.relay.copies`.
     */
    val copies: Int = envelope.relay?.copies ?: error("Envelope without relay metadata"),
    /** Generated column: json_extract(envelope, '$.relay.expiresAt'). Read-only — managed by SQLite. */
    @Column(insert = false, update = false)
    @Converter(InstantConverter::class)
    val expiresAt: Instant = envelope.relay?.expiresAt ?: error("Envelope without relay metadata"),
) : Auditable<Int> {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as RelayEntry
        if (id != other.id) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (copies != other.copies) return false
        if (envelope != other.envelope) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + copies
        result = 31 * result + envelope.hashCode()
        return result
    }

    override fun toString(): String =
        "RelayEntry(id=$id, copies=$copies, expiresAt=$expiresAt, updatedAt=$updatedAt)"

    companion object {
        /**
         * Converts a [StatelessEnvelope] to a [RelayEntry] for storage.
         * Requires [StatelessEnvelope.relay] to be non-null — envelopes without relay metadata
         * must not be stored in the relay table.
         */
        fun StatelessEnvelope.toRelayEntry(): RelayEntry {
            requireNotNull(relay) { "Cannot store envelope without relay metadata in relay table" }
            return RelayEntry(envelope = this)
        }
    }
}
