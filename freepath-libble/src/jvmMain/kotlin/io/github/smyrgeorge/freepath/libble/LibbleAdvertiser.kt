package io.github.smyrgeorge.freepath.libble

import java.util.concurrent.atomic.AtomicBoolean

actual class LibbleAdvertiser actual constructor() {

    private val advertising = AtomicBoolean(false)

    actual suspend fun start() {
        if (!advertising.compareAndSet(false, true)) return
        // Intentionally left blank.
    }

    actual suspend fun stop() {
        if (!advertising.compareAndSet(true, false)) return
        // Intentionally left blank.
    }
}
