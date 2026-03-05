package io.github.smyrgeorge.freepath.database.util

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder

object StringListConverter : ValueEncoder<List<String>> {
    override fun encode(value: List<String>): String = value.joinToString(",")
    override fun decode(value: ResultSet.Row.Column): List<String> {
        val raw = value.asString()
        return if (raw.isEmpty()) emptyList() else raw.split(",")
    }
}
