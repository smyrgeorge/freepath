package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual fun sqlite(
    url: String,
    options: ConnectionPool.Options,
    encoders: ValueEncoderRegistry
): ISQLite {
    val resolvedUrl = when {
        // In-memory database — not a filesystem path, so don't resolve it against Documents.
        url == ":memory:" -> url
        // Absolute path — use as-is.
        url.startsWith("/") -> url
        // Relative name — store under the app's Documents directory.
        else -> {
            val docs = NSFileManager.defaultManager.URLForDirectory(
                directory = NSDocumentDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            )?.path ?: error("Cannot resolve Documents directory")
            "$docs/$url"
        }
    }
    return sqlite(
        url = resolvedUrl,
        options = options,
        encoders = encoders,
    )
}
