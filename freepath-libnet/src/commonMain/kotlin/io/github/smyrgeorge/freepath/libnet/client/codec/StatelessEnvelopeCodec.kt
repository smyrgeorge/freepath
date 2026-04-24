package io.github.smyrgeorge.freepath.libnet.client.codec

import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.model.contact.Identity
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.libnet.client.model.RelayMetadata
import io.github.smyrgeorge.freepath.libnet.client.model.RelayOptions
import io.github.smyrgeorge.freepath.libnet.client.model.SealedPayload
import io.github.smyrgeorge.freepath.libnet.client.model.StatelessEnvelope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.time.Instant

object StatelessEnvelopeCodec {
    const val SCHEMA = 2
    private val HKDF_INFO_PREFIX = "freepath-stateless-v1".encodeToByteArray()

    /**
     * Seals [plaintext] for the node identified by [receiverIdRaw].
     *
     * - [receiverIdRaw] is hashed (sha256) before being placed in the envelope so relay nodes
     *   cannot learn the receiver's peerId from the wire format.
     * - An ephemeral X25519 key pair is generated per envelope for forward secrecy.
     * - If [relay] is non-null, [RelayMetadata] is constructed with
     *   `messageId = sha256(nonce + ephemeralKey)` and appended to the AAD so that
     *   [messageId] and [priority] are tamper-evident. [ttl] is excluded from AAD
     *   so relay nodes may legitimately decrement it.
     * - The signature covers `AAD ∥ plaintext`, binding receiver, timestamp, nonce,
     *   and immutable relay fields to the content.
     */
    fun seal(
        sender: Identity,
        receiverIdRaw: ByteArray,
        receiverEncKey: ByteArray,
        type: Byte,
        plaintext: ByteArray,
        timestamp: Instant,
        relay: RelayOptions? = null,
    ): StatelessEnvelope {
        require(timestamp >= Instant.fromEpochMilliseconds(0)) {
            "timestamp must be non-negative Unix epoch milliseconds"
        }

        val nonce = CryptoProvider.randomBytes(12)
        // Generate ephKeyPair before buildAad so messageId = sha256(nonce + ephemeralKey)
        // can be included in the AAD alongside other immutable relay fields.
        val ephKeyPair = CryptoProvider.generateX25519KeyPair()

        val receiverIdHash = CryptoProvider.sha256(receiverIdRaw)

        val relayMetadata = relay?.let {
            val messageId = CryptoProvider.sha256(nonce + ephKeyPair.publicKey)
            RelayMetadata(ttl = it.ttl, messageId = messageId, priority = it.priority, pow = it.pow)
        }

        val aad = buildAad(SCHEMA, receiverIdRaw, timestamp, nonce, relayMetadata)

        val sigInput = aad + plaintext
        val signature = CryptoProvider.ed25519Sign(sender.sigKeyPrivate, sigInput)

        val innerBytes = encodeSealedPayload(
            SealedPayload(
                senderId = sender.peerId,
                type = type.toInt() and 0xFF,
                signature = signature,
                payload = plaintext,
            )
        )

        val key = deriveKey(ephKeyPair.privateKey, receiverEncKey, receiverIdRaw)
        val ciphertext = CryptoProvider.chacha20Poly1305Encrypt(key, nonce, innerBytes, aad)

        return StatelessEnvelope(
            schema = SCHEMA,
            receiverIdHash = receiverIdHash,
            timestamp = timestamp,
            nonce = nonce,
            ephemeralKey = ephKeyPair.publicKey,
            payload = ciphertext,
            relay = relayMetadata,
        )
    }

    /**
     * Opens and authenticates [envelope] for [receiver].
     *
     * Steps:
     * 1. Verify schema is supported.
     * 2. Verify [envelope.receiverIdHash] matches sha256([receiver.peerIdRaw]).
     * 3. Rebuild AAD (including immutable relay fields if present).
     * 4. Derive message key and AEAD-decrypt — this also authenticates the AAD,
     *    so tampered [messageId] or [priority] cause decryption to fail automatically.
     * 5. Deserialize the [SealedPayload] and verify the Ed25519 signature.
     *
     * Returns `(type, plaintext)` on success; throws on any failure.
     */
    fun open(
        envelope: StatelessEnvelope,
        receiver: Identity,
        contactLookup: (peerId: String) -> Contact?,
    ): Pair<Byte, ByteArray> {
        if (envelope.schema != SCHEMA) error("Unsupported schema: ${envelope.schema}")

        if (!envelope.receiverIdHash.contentEquals(receiver.peerIdHash))
            error("Envelope receiverIdHash does not match local node")

        val receiverIdRaw = receiver.peerIdRaw
        val nonce = envelope.nonce
        if (nonce.size != 12) error("Nonce must be 12 bytes, got ${nonce.size}")
        if (envelope.ephemeralKey.size != 32) error("ephemeralKey must be 32 bytes, got ${envelope.ephemeralKey.size}")

        val aad = buildAad(envelope.schema, receiverIdRaw, envelope.timestamp, nonce, envelope.relay)
        val key = deriveKey(receiver.encKeyPrivate, envelope.ephemeralKey, receiverIdRaw)

        val innerBytes = runCatching {
            CryptoProvider.chacha20Poly1305Decrypt(key, nonce, envelope.payload, aad)
        }.getOrElse { error("AEAD decryption failed") }

        val sealed = runCatching { decodeSealedPayload(innerBytes) }
            .getOrElse { error("Failed to deserialize inner payload") }

        val contact = contactLookup(sealed.senderId) ?: error("Unknown sender peerId: ${sealed.senderId}")
        val sigInput = aad + sealed.payload
        if (!CryptoProvider.ed25519Verify(contact.sigKeyPublic, sigInput, sealed.signature))
            error("Signature verification failed")

        return sealed.type.toByte() to sealed.payload
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun encode(envelope: StatelessEnvelope): ByteArray = ProtoBuf.encodeToByteArray(envelope)

    @OptIn(ExperimentalSerializationApi::class)
    fun decode(bytes: ByteArray): StatelessEnvelope = ProtoBuf.decodeFromByteArray(bytes)

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Key = HKDF-SHA256(X25519(localEncPriv, peerEncKey), salt=32 zeros,
     *                   info="freepath-stateless-v1" ∥ receiverIdRaw).
     *
     * On seal: [localEncPriv] is the ephemeral private key, [peerEncKey] is the receiver's static key.
     * On open: [localEncPriv] is the receiver's static private key, [peerEncKey] is the ephemeral public key.
     */
    private fun deriveKey(localEncPriv: ByteArray, peerEncKey: ByteArray, receiverIdRaw: ByteArray): ByteArray {
        val sharedSecret = CryptoProvider.x25519DH(localEncPriv, peerEncKey)
        if (sharedSecret.all { it == 0.toByte() }) error("X25519 produced low-order point (all-zero shared secret)")
        val info = HKDF_INFO_PREFIX + receiverIdRaw
        return CryptoProvider.hkdfSha256(ikm = sharedSecret, salt = ByteArray(32), info = info, outputLen = 32)
    }

    /**
     * AAD = schema(4BE) ∥ receiverIdRaw(32) ∥ timestamp(8BE) ∥ nonce(12)
     *       [ ∥ messageId(32) ∥ priority(4BE) ]   ← appended only when relay != null
     *
     * TTL and pow are excluded from AAD: TTL is mutable (relay nodes decrement it);
     * pow is a placeholder not yet implemented.
     */
    private fun buildAad(
        schema: Int,
        receiverIdRaw: ByteArray,
        timestamp: Instant,
        nonce: ByteArray,
        relay: RelayMetadata? = null,
    ): ByteArray {
        require(receiverIdRaw.size == 32) { "receiverIdRaw must be 32 bytes (sha256 of peerId), got ${receiverIdRaw.size}" }
        val relaySize = if (relay != null) 32 + 4 else 0   // messageId(32) + priority(4BE)
        val buf = ByteArray(4 + 32 + 8 + 12 + relaySize)
        var off = 0
        off = BinaryCodec.writeInt32BE(buf, off, schema)
        receiverIdRaw.copyInto(buf, off)
        off += 32
        off = BinaryCodec.writeInt64BE(buf, off, timestamp.toEpochMilliseconds())
        nonce.copyInto(buf, off)
        off += 12
        if (relay != null) {
            relay.messageId.copyInto(buf, off)
            off += 32
            BinaryCodec.writeInt32BE(buf, off, relay.priority)
        }
        return buf
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun encodeSealedPayload(payload: SealedPayload): ByteArray = ProtoBuf.encodeToByteArray(payload)

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeSealedPayload(bytes: ByteArray): SealedPayload = ProtoBuf.decodeFromByteArray(bytes)
}
