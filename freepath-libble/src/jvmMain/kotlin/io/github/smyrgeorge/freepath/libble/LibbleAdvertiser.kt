package io.github.smyrgeorge.freepath.libble

import kotlin.concurrent.atomics.AtomicBoolean

actual class LibbleAdvertiser actual constructor() {

    private val advertising = AtomicBoolean(false)

    actual suspend fun start(psm: Int, identityToken: ByteArray?) {
        if (!advertising.compareAndSet(expectedValue = false, newValue = true)) return
    }

    actual suspend fun stop() {
        if (!advertising.compareAndSet(expectedValue = true, newValue = false)) return
    }
}
