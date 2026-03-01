package io.github.smyrgeorge.freepath.transport.codec

import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.transport.model.Frame

object AeadCodec {
    fun encrypt(plaintext: ByteArray, frame: Frame, sessionKey: ByteArray): ByteArray =
        CryptoProvider.chacha20Poly1305Encrypt(sessionKey, buildNonce(frame.seq), plaintext, buildAad(frame))

    fun decrypt(ciphertext: ByteArray, frame: Frame, sessionKey: ByteArray): ByteArray =
        CryptoProvider.chacha20Poly1305Decrypt(sessionKey, buildNonce(frame.seq), ciphertext, buildAad(frame))

    private fun buildNonce(seq: Long): ByteArray {
        val nonce = ByteArray(12)
        BinaryCodec.writeUInt32BE(nonce, 8, seq)
        return nonce
    }

    private fun buildAad(frame: Frame): ByteArray {
        val typeBytes = frame.wireType.encodeToByteArray()
        val streamIdBytes = frame.streamId.encodeToByteArray()
        val aad = ByteArray(4 + 4 + 1 + typeBytes.size + streamIdBytes.size)
        var off = 0
        off = BinaryCodec.writeInt32BE(aad, off, frame.schema)
        off = BinaryCodec.writeUInt32BE(aad, off, frame.seq)
        aad[off++] = typeBytes.size.toByte()
        typeBytes.copyInto(aad, off); off += typeBytes.size
        streamIdBytes.copyInto(aad, off)
        return aad
    }
}
