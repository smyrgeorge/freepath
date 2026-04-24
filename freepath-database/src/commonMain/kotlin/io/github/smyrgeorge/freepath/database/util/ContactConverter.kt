package io.github.smyrgeorge.freepath.database.util

import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.util.codec.JsonCodec
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder

object ContactConverter : ValueEncoder<Contact> {
    override fun encode(value: Contact): String = JsonCodec.json.encodeToString(value)
    override fun decode(value: ResultSet.Row.Column): Contact = JsonCodec.json.decodeFromString(value.asString())
}
