package io.github.smyrgeorge.freepath.client.codec

import io.github.smyrgeorge.freepath.client.model.ContactInfo
import io.github.smyrgeorge.freepath.client.model.StatelessEnvelope
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.codec.Base58
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

object StatelessEnvelopeCodec {

    const val SCHEMA = 1
    private val HKDF_INFO_PREFIX = "freepath-stateless-v1".encodeToByteArray()

    class EnvelopeException(message: String) : Exception(message)

    fun seal(
        sender: Identity,
        receiverIdRaw: ByteArray,
        receiverEncKeyPublic: ByteArray,
        plaintext: ByteArray,
        timestamp: Long,
        fragmentIndex: Int = 0,
        fragmentCount: Int = 1,
    ): StatelessEnvelope {
        require(fragmentCount >= 1) { "fragmentCount must be >= 1" }
        require(fragmentIndex in 0..<fragmentCount) { "fragmentIndex must be in 0..<fragmentCount" }

        val nonce = CryptoProvider.randomBytes(12)
        val senderIdRaw = sender.nodeIdRaw

        val key = deriveKey(sender.encKeyPrivate, receiverEncKeyPublic, senderIdRaw, receiverIdRaw)
        val aad = buildAad(SCHEMA, senderIdRaw, receiverIdRaw, timestamp, nonce, fragmentIndex, fragmentCount)
        val ciphertext = CryptoProvider.chacha20Poly1305Encrypt(key, nonce, plaintext, aad)

        val sigInput = sigInput(aad, ciphertext)
        val signature = CryptoProvider.ed25519Sign(sender.sigKeyPrivate, sigInput)

        return StatelessEnvelope(
            schema = SCHEMA,
            senderId = Base58.encode(senderIdRaw),
            receiverId = Base58.encode(receiverIdRaw),
            timestamp = timestamp,
            nonce = nonce,
            fragmentIndex = fragmentIndex,
            fragmentCount = fragmentCount,
            payload = ciphertext,
            signature = signature,
        )
    }

    fun open(
        envelope: StatelessEnvelope,
        receiver: Identity,
        contactLookup: (nodeIdRaw: ByteArray) -> ContactInfo?,
    ): ByteArray {
        if (envelope.schema != SCHEMA)
            throw EnvelopeException("Unsupported schema: ${envelope.schema}")
        if (envelope.fragmentCount < 1)
            throw EnvelopeException("Invalid fragmentCount: ${envelope.fragmentCount}")
        if (envelope.fragmentIndex < 0 || envelope.fragmentIndex >= envelope.fragmentCount)
            throw EnvelopeException("fragmentIndex ${envelope.fragmentIndex} out of range for fragmentCount ${envelope.fragmentCount}")

        val receiverIdRaw = runCatching { Base58.decode(envelope.receiverId) }
            .getOrElse { throw EnvelopeException("Invalid receiverId encoding") }
        if (!receiverIdRaw.contentEquals(receiver.nodeIdRaw))
            throw EnvelopeException("Envelope receiverId does not match local nodeId")

        val senderIdRaw = runCatching { Base58.decode(envelope.senderId) }
            .getOrElse { throw EnvelopeException("Invalid senderId encoding") }
        val contact = contactLookup(senderIdRaw)
            ?: throw EnvelopeException("Unknown sender nodeId")

        val nonce = envelope.nonce
        if (nonce.size != 12) throw EnvelopeException("Nonce must be 12 bytes, got ${nonce.size}")
        val ciphertext = envelope.payload
        val signature = envelope.signature

        val aad = buildAad(
            envelope.schema, senderIdRaw, receiverIdRaw,
            envelope.timestamp, nonce, envelope.fragmentIndex, envelope.fragmentCount,
        )
        val sigInput = sigInput(aad, ciphertext)
        if (!CryptoProvider.ed25519Verify(contact.sigKeyPublic, sigInput, signature))
            throw EnvelopeException("Signature verification failed")

        val key = deriveKey(receiver.encKeyPrivate, contact.encKeyPublic, senderIdRaw, receiverIdRaw)
        return runCatching {
            CryptoProvider.chacha20Poly1305Decrypt(key, nonce, ciphertext, aad)
        }.getOrElse { throw EnvelopeException("AEAD decryption failed") }
    }

    /** Serialises [envelope] to protobuf bytes. */
    @OptIn(ExperimentalSerializationApi::class)
    fun encode(envelope: StatelessEnvelope): ByteArray =
        ProtoBuf.encodeToByteArray(StatelessEnvelope.serializer(), envelope)

    /** Deserialises a [StatelessEnvelope] from protobuf [bytes]. */
    @OptIn(ExperimentalSerializationApi::class)
    fun decode(bytes: ByteArray): StatelessEnvelope =
        ProtoBuf.decodeFromByteArray(StatelessEnvelope.serializer(), bytes)

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun deriveKey(
        localEncPriv: ByteArray,
        peerEncPub: ByteArray,
        senderIdRaw: ByteArray,
        receiverIdRaw: ByteArray,
    ): ByteArray {
        val sharedSecret = CryptoProvider.x25519DH(localEncPriv, peerEncPub)
        if (sharedSecret.all { it == 0.toByte() })
            throw EnvelopeException("X25519 produced low-order point (all-zero shared secret)")
        val info = HKDF_INFO_PREFIX + senderIdRaw + receiverIdRaw
        return CryptoProvider.hkdfSha256(ikm = sharedSecret, salt = ByteArray(32), info = info, outputLen = 32)
    }

    // AAD = schema(4BE) ∥ senderIdRaw(32) ∥ receiverIdRaw(32) ∥ timestamp(8BE) ∥ nonce(12) ∥ fragmentIndex(4BE) ∥ fragmentCount(4BE)
    private fun buildAad(
        schema: Int,
        senderIdRaw: ByteArray,
        receiverIdRaw: ByteArray,
        timestamp: Long,
        nonce: ByteArray,
        fragmentIndex: Int,
        fragmentCount: Int,
    ): ByteArray {
        val buf = ByteArray(4 + 32 + 32 + 8 + 12 + 4 + 4)
        var off = 0
        off = BinaryCodec.writeInt32BE(buf, off, schema)
        senderIdRaw.copyInto(buf, off); off += 32
        receiverIdRaw.copyInto(buf, off); off += 32
        off = BinaryCodec.writeInt64BE(buf, off, timestamp)
        nonce.copyInto(buf, off); off += 12
        off = BinaryCodec.writeInt32BE(buf, off, fragmentIndex)
        BinaryCodec.writeInt32BE(buf, off, fragmentCount)
        return buf
    }

    /** Signature input = AAD ∥ ciphertext. */
    private fun sigInput(aad: ByteArray, ciphertext: ByteArray): ByteArray = aad + ciphertext
}
