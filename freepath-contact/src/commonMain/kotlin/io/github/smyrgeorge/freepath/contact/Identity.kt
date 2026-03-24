package io.github.smyrgeorge.freepath.contact

data class Identity(
    /** Raw 32-byte Peer ID: SHA-256(sigKey). */
    val peerIdRaw: ByteArray,
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
        if (other !is Identity) return false
        return peerIdRaw.contentEquals(other.peerIdRaw)
    }

    override fun hashCode(): Int = peerIdRaw.contentHashCode()
}
