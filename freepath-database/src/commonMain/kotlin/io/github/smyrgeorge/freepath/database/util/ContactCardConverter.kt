package io.github.smyrgeorge.freepath.database.util

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.ContactCardCodec
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder

object ContactCardConverter : ValueEncoder<ContactCard> {
    override fun encode(value: ContactCard): String = ContactCardCodec.encodeToString(value)
    override fun decode(value: ResultSet.Row.Column): ContactCard = ContactCardCodec.decode(value.asString())
}