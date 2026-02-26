package io.github.smyrgeorge.freepath.transport.model

fun interface ContactLookup {
    fun getSigKey(nodeIdRaw: ByteArray): ByteArray?
}
