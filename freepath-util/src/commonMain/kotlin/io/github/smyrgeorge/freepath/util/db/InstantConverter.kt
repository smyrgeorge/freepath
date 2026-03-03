package io.github.smyrgeorge.freepath.util.db

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.time.Instant

object InstantConverter : ValueEncoder<Instant> {
    override fun encode(value: Instant): Long = value.toEpochMilliseconds()
    override fun decode(value: ResultSet.Row.Column): Instant = Instant.fromEpochMilliseconds(value.asLong())
}