package io.github.smyrgeorge.freepath.libble

expect class LibbleAdvertiser() {
    /**
     * Start advertising with [psm] and optional [identityToken] (8 bytes).
     *
     * - Android: PSM (2 bytes LE) + token (8 bytes) in scan response service data.
     * - iOS: local name `"fp:PPPP:TTTTTTTTTTTTTTTT"` (PSM hex + token hex).
     */
    suspend fun start(psm: Int, identityToken: ByteArray?)
    suspend fun stop()
}
