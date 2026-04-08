package io.github.smyrgeorge.freepath.database.util

import io.github.smyrgeorge.freepath.libnet.client.model.StatelessEnvelope
import io.github.smyrgeorge.freepath.util.codec.JsonCodec
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder

object StatelessEnvelopeConverter : ValueEncoder<StatelessEnvelope> {
    override fun encode(value: StatelessEnvelope): String = JsonCodec.json.encodeToString(value)
    override fun decode(value: ResultSet.Row.Column): StatelessEnvelope =
        JsonCodec.json.decodeFromString(value.asString())
}
