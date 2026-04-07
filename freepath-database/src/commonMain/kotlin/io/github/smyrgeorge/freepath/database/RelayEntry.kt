package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.database.util.Auditable
import io.github.smyrgeorge.freepath.database.util.ByteArrayConverter
import io.github.smyrgeorge.freepath.database.util.InstantConverter
import io.github.smyrgeorge.sqlx4k.annotation.Converter
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@Table("relay")
data class RelayEntry(
    @Id
    override val id: Int = 0,
    @Converter(InstantConverter::class)
    override var createdAt: Instant = Clock.System.now(),
    @Converter(InstantConverter::class)
    override var updatedAt: Instant = Clock.System.now(),
    /** The peerId of the intended recipient (Base58 multihash, visible in the envelope header). */
    val receiverPeerId: String,
    /** The raw encrypted wire bytes (version header + protobuf envelope), stored as Base64. */
    @Converter(ByteArrayConverter::class)
    val payload: ByteArray,
    /** When this entry should be discarded (epoch millis). Default TTL: 7 days. */
    @Converter(InstantConverter::class)
    val expireAt: Instant = Clock.System.now() + 7.days,
) : Auditable<Int> {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as RelayEntry

        if (id != other.id) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (receiverPeerId != other.receiverPeerId) return false
        if (expireAt != other.expireAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + receiverPeerId.hashCode()
        result = 31 * result + expireAt.hashCode()
        return result
    }

    override fun toString(): String =
        "RelayEntry(receiverPeerId='$receiverPeerId', updatedAt=$updatedAt, createdAt=$createdAt, id=$id, expireAt=$expireAt)"
}
