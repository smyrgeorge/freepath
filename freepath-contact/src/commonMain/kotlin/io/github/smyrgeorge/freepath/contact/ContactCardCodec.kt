package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.codec.Base58
import io.github.smyrgeorge.freepath.util.codec.JsonCodec
import kotlin.io.encoding.Base64

object ContactCardCodec {

    const val SCHEMA = 1

    // ── Node ID ───────────────────────────────────────────────────────────────

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
    fun deriveNodeId(ed25519PubKey: ByteArray): String {
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

    // ── Signing ───────────────────────────────────────────────────────────────

    /** Signs the JSON-encoded card bytes with [sigKeyPrivate]; returns the raw 64-byte Ed25519 signature. */
    fun sign(card: ContactCard, sigKeyPrivate: ByteArray): ByteArray =
        CryptoProvider.ed25519Sign(sigKeyPrivate, encode(card))

    /**
     * Verifies [signatureBytes] against the card's own `sigKey`.
     * Returns `true` if the signature is valid.
     */
    fun verify(card: ContactCard, signatureBytes: ByteArray): Boolean {
        val sigKeyBytes = Base64.decode(card.sigKey)
        return CryptoProvider.ed25519Verify(sigKeyBytes, encode(card), signatureBytes)
    }

    /** Creates a [ContactCardSigned] by signing [card] with [sigKeyPrivate]. */
    fun seal(card: ContactCard, sigKeyPrivate: ByteArray): ContactCardSigned =
        ContactCardSigned(card, Base64.encode(sign(card, sigKeyPrivate)))

    /**
     * Fully verifies a [ContactCardSigned] per spec-3:
     * (1) schema check, (2) signature verification.
     * Node ID is always correct by construction (derived lazily from sigKey).
     */
    fun open(signed: ContactCardSigned): Result<ContactCard> = runCatching {
        val card = signed.card
        require(card.schema == ContactCard.SCHEMA) { "Unsupported card schema: ${card.schema}" }
        val signatureBytes = Base64.decode(signed.signature)
        require(verify(card, signatureBytes)) { "Invalid card signature" }
        card
    }

    // ── Card update rules (spec 1 Card updates) ──────────────────────────────

    /**
     * Returns `true` if [incoming] should replace [stored] in the local contact list.
     *
     * Rules (applied in order):
     * 1. `incoming.sigKey` must equal `stored.sigKey` (no key rotation).
     * 2. `incoming.updatedAt` must be strictly greater than `stored.updatedAt`.
     *
     * Callers are responsible for verifying the card signature separately before calling this.
     */
    fun shouldUpdate(stored: ContactCard, incoming: ContactCard): Boolean {
        if (stored.sigKey != incoming.sigKey) return false
        return incoming.updatedAt > stored.updatedAt
    }

    // ── JSON encode/decode ────────────────────────────────────────────────────

    /** Encodes [card] to UTF-8 JSON bytes. */
    fun encode(card: ContactCard): ByteArray = JsonCodec.json.encodeToString(card).encodeToByteArray()

    /** Encodes [card] to UTF-8 JSON string. */
    fun encodeToString(card: ContactCard): String = JsonCodec.json.encodeToString(card)

    /** Decodes a [ContactCard] from UTF-8 JSON [bytes]. */
    fun decode(bytes: ByteArray): ContactCard = JsonCodec.json.decodeFromString(bytes.decodeToString())

    /** Decodes a [ContactCard] from UTF-8 JSON [str]. */
    fun decode(str: String): ContactCard = JsonCodec.json.decodeFromString(str)

    /** Encodes [signed] to UTF-8 JSON bytes. */
    fun encode(signed: ContactCardSigned): ByteArray = JsonCodec.json.encodeToString(signed).encodeToByteArray()

    /** Decodes a [ContactCardSigned] from UTF-8 JSON [bytes]. */
    fun decodeSigned(bytes: ByteArray): ContactCardSigned = JsonCodec.json.decodeFromString(bytes.decodeToString())
}
