package io.github.smyrgeorge.freepath.libnet.client.codec

import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.libnet.client.model.SealedPayload
import io.github.smyrgeorge.freepath.libnet.client.model.StatelessEnvelope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.time.Instant

object StatelessEnvelopeCodec {
    const val SCHEMA = 1
    private val HKDF_INFO_PREFIX = "freepath-stateless-v1".encodeToByteArray()

    /**
     * Seals [plaintext] for [receiverIdRaw].
     *
     * - An ephemeral X25519 key pair is generated per envelope so the relay cannot correlate
     *   messages to a sender even by long-term encKey, and provides per-message forward secrecy.
     * - [type], the sender peerId, and the Ed25519 signature are all packed into a protobuf
     *   [SealedPayload] and encrypted together, so relay nodes learn nothing beyond [receiverId].
     * - The signature covers `AAD ∥ plaintext`, binding receiver, timestamp, nonce, and content.
     * - [receiverId] is the libp2p-format peer ID stored in the envelope for routing.
     *   [receiverIdRaw] is the underlying SHA-256 bytes used for AAD and key derivation.
     */
    fun seal(
        sender: Identity,
        receiverId: String,
        receiverIdRaw: ByteArray,
        receiverEncKey: ByteArray,
        type: Byte,
        plaintext: ByteArray,
        timestamp: Instant,
    ): StatelessEnvelope {
        require(timestamp >= Instant.fromEpochMilliseconds(0)) {
            "timestamp must be non-negative Unix epoch milliseconds"
        }

        val nonce = CryptoProvider.randomBytes(12)
        val aad = buildAad(SCHEMA, receiverIdRaw, timestamp, nonce)

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

        val ephKeyPair = CryptoProvider.generateX25519KeyPair()
        val key = deriveKey(ephKeyPair.privateKey, receiverEncKey, receiverIdRaw)
        val ciphertext = CryptoProvider.chacha20Poly1305Encrypt(key, nonce, innerBytes, aad)

        return StatelessEnvelope(
            schema = SCHEMA,
            receiverId = receiverId,
            timestamp = timestamp,
            nonce = nonce,
            ephemeralKey = ephKeyPair.publicKey,
            payload = ciphertext,
        )
    }

    /**
     * Opens and authenticates [envelope] for [receiver].
     *
     * Steps:
     * 1. Verify the envelope is addressed to [receiver].
     * 2. Derive the message key from [receiver]'s static decryption key and the ephemeral key.
     * 3. AEAD-decrypt the payload (also authenticates AAD).
     * 4. Deserialize the [SealedPayload] to extract sender, type, signature, and plaintext.
     * 5. Look up the sender's contact and verify their Ed25519 signature.
     *
     * Returns `(type, plaintext)` on success; throws on any failure.
     */
    fun open(
        envelope: StatelessEnvelope,
        receiver: Identity,
        contactLookup: (peerId: String) -> Contact?,
    ): Pair<Byte, ByteArray> {
        if (envelope.schema != SCHEMA) error("Unsupported schema: ${envelope.schema}")
        if (envelope.receiverId != receiver.peerId)
            error("Envelope receiverId does not match local peerId")

        val receiverIdRaw = receiver.peerIdRaw
        val nonce = envelope.nonce
        if (nonce.size != 12) error("Nonce must be 12 bytes, got ${nonce.size}")
        if (envelope.ephemeralKey.size != 32) error("ephemeralKey must be 32 bytes, got ${envelope.ephemeralKey.size}")

        val aad = buildAad(envelope.schema, receiverIdRaw, envelope.timestamp, nonce)
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
     * Key = HKDF-SHA256(X25519(localEncPriv, peerEncKey), salt=32 zeros, info="freepath-stateless-v1" ∥ receiverIdRaw).
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

    // AAD = schema(4BE) ∥ receiverIdRaw(32) ∥ timestamp(8BE) ∥ nonce(12)
    private fun buildAad(schema: Int, receiverIdRaw: ByteArray, timestamp: Instant, nonce: ByteArray): ByteArray {
        val buf = ByteArray(4 + 32 + 8 + 12)
        var off = 0
        off = BinaryCodec.writeInt32BE(buf, off, schema)
        receiverIdRaw.copyInto(buf, off); off += 32
        off = BinaryCodec.writeInt64BE(buf, off, timestamp.toEpochMilliseconds())
        nonce.copyInto(buf, off)
        return buf
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun encodeSealedPayload(payload: SealedPayload): ByteArray = ProtoBuf.encodeToByteArray(payload)

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeSealedPayload(bytes: ByteArray): SealedPayload = ProtoBuf.decodeFromByteArray(bytes)
}
