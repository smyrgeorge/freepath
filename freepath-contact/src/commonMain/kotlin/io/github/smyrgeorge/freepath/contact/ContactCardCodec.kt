package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.Base58
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

object ContactCardCodec {

    const val SCHEMA = 1

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    // ── Node ID ───────────────────────────────────────────────────────────────

    /** Derives the Node ID from a raw Ed25519 public key per spec 1. */
    fun deriveNodeId(sigKeyPublicBytes: ByteArray): String =
        Base58.encode(CryptoProvider.sha256(sigKeyPublicBytes).copyOfRange(0, 16))

    /** Returns `true` if [card].nodeId matches the value derived locally from [card].sigKey. */
    fun validateNodeId(card: ContactCard): Boolean {
        val sigKeyBytes = Base64.decode(card.sigKey)
        return deriveNodeId(sigKeyBytes) == card.nodeId
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

    /** Creates a [SignedContactCard] by signing [card] with [sigKeyPrivate]. */
    fun seal(card: ContactCard, sigKeyPrivate: ByteArray): SignedContactCard =
        SignedContactCard(card, Base64.encode(sign(card, sigKeyPrivate)))

    /**
     * Verifies the signature on [signed] and validates the Node ID.
     * Returns the inner [ContactCard] on success, or throws [IllegalStateException] on failure.
     */
    fun open(signed: SignedContactCard): ContactCard {
        val sigBytes = Base64.decode(signed.signature)
        check(verify(signed.card, sigBytes)) { "Invalid signature on contact card" }
        check(validateNodeId(signed.card)) { "nodeId mismatch in contact card" }
        return signed.card
    }

    // ── Card update rules (spec 1 Card updates) ──────────────────────────────

    /**
     * Returns `true` if [incoming] should replace [stored] in the local contact list.
     *
     * Rules (applied in order):
     * 1. `incoming.nodeId` must match the value derived from `incoming.sigKey`.
     * 2. `incoming.sigKey` must equal `stored.sigKey` (no key rotation).
     * 3. `incoming.updatedAt` must be strictly greater than `stored.updatedAt`.
     *
     * Callers are responsible for verifying the card signature separately before calling this.
     */
    fun shouldUpdate(stored: ContactCard, incoming: ContactCard): Boolean {
        if (!validateNodeId(incoming)) return false
        if (stored.sigKey != incoming.sigKey) return false
        return incoming.updatedAt > stored.updatedAt
    }

    // ── JSON encode/decode ────────────────────────────────────────────────────

    /** Encodes [card] to UTF-8 JSON bytes. */
    fun encode(card: ContactCard): ByteArray = json.encodeToString(card).encodeToByteArray()

    /** Decodes a [ContactCard] from UTF-8 JSON [bytes]. */
    fun decode(bytes: ByteArray): ContactCard = json.decodeFromString(bytes.decodeToString())

    /** Encodes [signed] to UTF-8 JSON bytes. */
    fun encode(signed: SignedContactCard): ByteArray = json.encodeToString(signed).encodeToByteArray()

    /** Decodes a [SignedContactCard] from UTF-8 JSON [bytes]. */
    fun decodeSigned(bytes: ByteArray): SignedContactCard = json.decodeFromString(bytes.decodeToString())
}
