package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.contact.IdentityConverter
import io.github.smyrgeorge.freepath.database.util.Auditable
import io.github.smyrgeorge.freepath.database.util.InstantConverter
import io.github.smyrgeorge.sqlx4k.annotation.Converter
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Clock
import kotlin.time.Instant

@Table("identity")
data class IdentityEntry(
    /** Primary key. */
    @Id
    override val id: Int = 0,
    @Converter(InstantConverter::class)
    override var createdAt: Instant = Clock.System.now(),
    @Converter(InstantConverter::class)
    override var updatedAt: Instant = Clock.System.now(),
    /** Unique key. Derived locally from the contact's sigKey. */
    val nodeId: String,
    /** Complete identity: nodeIdRaw, sigKey pair (public + private), encKey pair (public + private). */
    @Converter(IdentityConverter::class)
    val data: Identity,
) : Auditable<Int> {
    init {
        require(id >= 0) { "id must be non-negative" }
        require(nodeId.matches(BASE58_REGEX)) {
            "nodeId must be a 22-character Base58 string"
        }
    }

    override fun toString(): String {
        return "IdentityEntry(nodeId='$nodeId', updatedAt=$updatedAt, createdAt=$createdAt, id=$id)"
    }

    companion object {
        private val BASE58_REGEX = Regex("[1-9A-HJ-NP-Za-km-z]{22}")
    }
}
