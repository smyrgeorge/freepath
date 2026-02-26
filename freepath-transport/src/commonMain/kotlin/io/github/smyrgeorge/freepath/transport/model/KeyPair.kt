package io.github.smyrgeorge.freepath.transport.model

data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyPair) return false
        return privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = 31 * privateKey.contentHashCode() + publicKey.contentHashCode()
}
