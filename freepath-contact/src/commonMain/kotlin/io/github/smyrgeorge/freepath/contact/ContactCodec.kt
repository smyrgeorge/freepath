package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.codec.Base58
import io.github.smyrgeorge.freepath.util.codec.ProtobufCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.io.encoding.Base64

@OptIn(ExperimentalSerializationApi::class)
object ContactCodec {
    const val SCHEMA = 1

    /**
     * Derives the Node ID from a raw Ed25519 public key following the modern libp2p Peer ID spec.
     *
     * Steps:
     * 1. Protobuf-encode as `PublicKey { KeyType=Ed25519(1), Data=pubKey }`:
     *    `[0x08, 0x01, 0x12, 0x20] ∥ pubKey` = 36 bytes.
     * 2. Wrap in an identity multihash `[0x00, 0x24] ∥ protobuf` = 38 bytes.
     *    `0x00` = identity hash code (key is small enough to inline, no hashing needed).
     *    `0x24` = varint 36 (protobuf byte length).
     * 3. Base58-encode the 38-byte multihash (no multibase prefix per libp2p spec).
     *
     * Always produces a 52-character Base58 string starting with "1".
     * Compatible with libp2p Peer IDs for Ed25519 keys.
     */
    fun derivePeerId(ed25519PubKey: ByteArray): String {
        require(ed25519PubKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        // protobuf encode PublicKey
        val protobuf = ByteArray(2 + 2 + 32)
        var i = 0
        protobuf[i++] = 0x08
        protobuf[i++] = 0x01
        protobuf[i++] = 0x12
        protobuf[i++] = 0x20
        ed25519PubKey.copyInto(protobuf, i)

        // multihash identity
        val multihash = ByteArray(2 + protobuf.size)
        multihash[0] = 0x00
        multihash[1] = protobuf.size.toByte()
        protobuf.copyInto(multihash, 2)
        return Base58.encode(multihash)
    }

    /** Signs the protobuf-encoded contact bytes with [sigKeyPrivate]; returns the raw 64-byte Ed25519 signature. */
    fun sign(contact: Contact, sigKeyPrivate: ByteArray): ByteArray =
        CryptoProvider.ed25519Sign(sigKeyPrivate, encode(contact))

    /**
     * Verifies [signatureBytes] against the contact's own `sigKey`.
     * Returns `true` if the signature is valid.
     */
    fun verify(contact: Contact, signatureBytes: ByteArray): Boolean {
        val sigKeyBytes = Base64.decode(contact.sigKey)
        return CryptoProvider.ed25519Verify(sigKeyBytes, encode(contact), signatureBytes)
    }

    /**
     * Returns `true` if [incoming] should replace [stored] in the local contact list.
     *
     * Rules (applied in order):
     * 1. `incoming.sigKey` must equal `stored.sigKey` (no key rotation).
     * 2. `incoming.updatedAt` must be strictly greater than `stored.updatedAt`.
     *
     * Callers are responsible for verifying the contact signature separately before calling this.
     */
    fun shouldUpdate(stored: Contact, incoming: Contact): Boolean {
        if (stored.sigKey != incoming.sigKey) return false
        return incoming.updatedAt > stored.updatedAt
    }

    fun encode(contact: Contact): ByteArray = ProtobufCodec.protobuf.encodeToByteArray(contact)
    fun decode(bytes: ByteArray): Contact = ProtobufCodec.protobuf.decodeFromByteArray(bytes)
}
