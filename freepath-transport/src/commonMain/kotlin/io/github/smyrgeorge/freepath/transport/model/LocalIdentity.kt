package io.github.smyrgeorge.freepath.transport.model

data class LocalIdentity(
    /** Raw 16-byte Node ID: SHA-256(sigKey)[0..15]. */
    val nodeIdRaw: ByteArray,
    /** Ed25519 public key (32 bytes). */
    val sigKeyPublic: ByteArray,
    /** Ed25519 private key seed (32 bytes). */
    val sigKeyPrivate: ByteArray,
    /** X25519 public key (32 bytes) used for StatelessEnvelope encryption. */
    val encKeyPublic: ByteArray,
    /** X25519 private key (32 bytes) used for StatelessEnvelope decryption. */
    val encKeyPrivate: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalIdentity) return false
        return nodeIdRaw.contentEquals(other.nodeIdRaw)
    }

    override fun hashCode(): Int = nodeIdRaw.contentHashCode()
}
