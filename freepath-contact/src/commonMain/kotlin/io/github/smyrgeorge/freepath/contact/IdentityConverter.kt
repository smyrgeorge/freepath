package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import kotlin.io.encoding.Base64

object IdentityConverter : ValueEncoder<Identity> {
    override fun encode(value: Identity): String {
        return listOf(
            Base64.encode(value.nodeIdRaw),
            Base64.encode(value.sigKeyPublic),
            Base64.encode(value.sigKeyPrivate),
            Base64.encode(value.encKeyPublic),
            Base64.encode(value.encKeyPrivate)
        ).joinToString(".")
    }

    override fun decode(value: ResultSet.Row.Column): Identity {
        val parts = value.asString().split(".")
        require(parts.size == 5) { "Invalid Identity format: expected 5 parts, got ${parts.size}" }

        return Identity(
            nodeIdRaw = Base64.decode(parts[0]),
            sigKeyPublic = Base64.decode(parts[1]),
            sigKeyPrivate = Base64.decode(parts[2]),
            encKeyPublic = Base64.decode(parts[3]),
            encKeyPrivate = Base64.decode(parts[4])
        )
    }
}
