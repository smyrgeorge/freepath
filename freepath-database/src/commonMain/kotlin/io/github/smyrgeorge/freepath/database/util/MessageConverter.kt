package io.github.smyrgeorge.freepath.database.util

import io.github.smyrgeorge.freepath.model.content.Message
import io.github.smyrgeorge.freepath.util.codec.JsonCodec
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
object MessageConverter : ValueEncoder<Message> {
    override fun encode(value: Message): String = JsonCodec.json.encodeToString(value)
    override fun decode(value: ResultSet.Row.Column): Message = JsonCodec.json.decodeFromString(value.asString())
}
