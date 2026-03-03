package io.github.smyrgeorge.freepath.util.db

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder

object StringListConverter : ValueEncoder<List<String>> {
    override fun encode(value: List<String>): String = value.joinToString(",")
    override fun decode(value: ResultSet.Row.Column): List<String> = value.asString().split(",")
}