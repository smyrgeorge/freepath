package io.github.smyrgeorge.freepath.transport.crypto

import io.github.smyrgeorge.freepath.transport.model.KeyPair

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object CryptoProvider {
    fun generateX25519KeyPair(): KeyPair
    fun x25519DH(privateKey: ByteArray, publicKey: ByteArray): ByteArray
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLen: Int): ByteArray
    fun chacha20Poly1305Encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
    fun chacha20Poly1305Decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray
    fun generateEd25519KeyPair(): KeyPair
    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray
    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
    fun randomBytes(size: Int): ByteArray
}
