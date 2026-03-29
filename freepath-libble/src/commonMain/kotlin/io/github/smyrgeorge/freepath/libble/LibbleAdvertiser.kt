package io.github.smyrgeorge.freepath.libble

expect class LibbleAdvertiser() {
    suspend fun start(bleBeaconId: ByteArray)
    suspend fun stop()
}
