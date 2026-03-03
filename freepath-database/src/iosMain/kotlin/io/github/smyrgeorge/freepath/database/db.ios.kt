package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite

actual fun sqlite(
    url: String,
    options: ConnectionPool.Options,
    encoders: ValueEncoderRegistry
): ISQLite {
    return sqlite(
        url = url,
        options = options,
        encoders = encoders,
    )
}
