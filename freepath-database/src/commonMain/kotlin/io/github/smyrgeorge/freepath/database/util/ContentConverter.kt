package io.github.smyrgeorge.freepath.database.util

import io.github.smyrgeorge.freepath.content.ContentEnvelope
import io.github.smyrgeorge.freepath.util.codec.JsonCodec
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder

object ContentConverter : ValueEncoder<ContentEnvelope> {
    override fun encode(value: ContentEnvelope): String =
        JsonCodec.json.encodeToString(value)

    override fun decode(value: ResultSet.Row.Column): ContentEnvelope =
        JsonCodec.json.decodeFromString(value.asString())
}
