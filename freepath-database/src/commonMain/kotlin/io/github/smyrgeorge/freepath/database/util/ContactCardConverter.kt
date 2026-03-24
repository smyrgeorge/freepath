package io.github.smyrgeorge.freepath.database.util

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.util.codec.JsonCodec
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder

object ContactCardConverter : ValueEncoder<ContactCard> {
    override fun encode(value: ContactCard): String = JsonCodec.json.encodeToString(value)
    override fun decode(value: ResultSet.Row.Column): ContactCard = JsonCodec.json.decodeFromString(value.asString())
}
