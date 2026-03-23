package io.github.smyrgeorge.freepath.libble

expect class LibbleAdvertiser() {
    suspend fun start()
    suspend fun stop()
}
