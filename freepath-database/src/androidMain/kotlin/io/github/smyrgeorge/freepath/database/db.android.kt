package io.github.smyrgeorge.freepath.database

import android.content.Context
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite

object AndroidContextHolder {
    lateinit var applicationContext: Context
}

actual fun sqlite(
    url: String,
    options: ConnectionPool.Options,
    encoders: ValueEncoderRegistry
): ISQLite {
    return sqlite(
        context = AndroidContextHolder.applicationContext,
        url = url,
        options = options,
        encoders = encoders,
    )
}
