package io.github.smyrgeorge.freepath.database.util

import io.github.smyrgeorge.freepath.content.Content
import io.github.smyrgeorge.freepath.util.codec.JsonCodec
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder

object ContentConverter : ValueEncoder<Content> {
    override fun encode(value: Content): String = JsonCodec.json.encodeToString(value)
    override fun decode(value: ResultSet.Row.Column): Content = JsonCodec.json.decodeFromString(value.asString())
}
