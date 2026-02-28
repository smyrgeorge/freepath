package io.github.smyrgeorge.freepath.transport.lan

import android.content.Context

object FreepathContext {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    internal fun get(): Context = checkNotNull(appContext) {
        "FreepathContext not initialised. Call FreepathContext.init(context) in Application.onCreate()."
    }
}
