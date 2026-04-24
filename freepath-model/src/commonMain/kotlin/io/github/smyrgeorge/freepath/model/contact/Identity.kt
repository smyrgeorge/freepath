package io.github.smyrgeorge.freepath.model.contact

import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider

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
    val peerId: String by lazy { ContactCodec.derivePeerId(sigKeyPublic) }
    val peerIdHash: ByteArray by lazy { CryptoProvider.sha256(peerIdRaw) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identity) return false
        return peerIdRaw.contentEquals(other.peerIdRaw)
    }

    override fun hashCode(): Int = peerIdRaw.contentHashCode()
}
