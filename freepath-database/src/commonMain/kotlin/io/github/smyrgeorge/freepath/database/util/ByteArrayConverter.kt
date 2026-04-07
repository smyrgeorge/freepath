package io.github.smyrgeorge.freepath.database.util

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import kotlin.io.encoding.Base64

object ByteArrayConverter : ValueEncoder<ByteArray> {
    override fun encode(value: ByteArray): String = Base64.encode(value)
    override fun decode(value: ResultSet.Row.Column): ByteArray = Base64.decode(value.asString())
}
