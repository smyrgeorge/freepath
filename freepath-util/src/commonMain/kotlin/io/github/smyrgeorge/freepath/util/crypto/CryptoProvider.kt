@file:OptIn(DelicateCryptographyApi::class)

package io.github.smyrgeorge.freepath.util.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH
import dev.whyoleg.cryptography.random.CryptographyRandom

object CryptoProvider {
    private val xdh by lazy { cryptographyProvider.get(XDH) }
    private val eddsa by lazy { cryptographyProvider.get(EdDSA) }
    private val chacha by lazy { cryptographyProvider.get(ChaCha20Poly1305) }
    private val hkdf by lazy { cryptographyProvider.get(HKDF) }
    private val sha256Hasher by lazy { cryptographyProvider.get(SHA256).hasher() }

    fun generateX25519KeyPair(): KeyPair {
        val kp = xdh.keyPairGenerator(XDH.Curve.X25519).generateKeyBlocking()
        return KeyPair(
            privateKey = kp.privateKey.encodeToByteArrayBlocking(XDH.PrivateKey.Format.RAW),
            publicKey = kp.publicKey.encodeToByteArrayBlocking(XDH.PublicKey.Format.RAW),
        )
    }

    fun x25519DH(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val priv = xdh.privateKeyDecoder(XDH.Curve.X25519)
            .decodeFromByteArrayBlocking(XDH.PrivateKey.Format.RAW, privateKey)
        val pub = xdh.publicKeyDecoder(XDH.Curve.X25519)
            .decodeFromByteArrayBlocking(XDH.PublicKey.Format.RAW, publicKey)
        return priv.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(pub)
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLen: Int): ByteArray =
        hkdf.secretDerivation(
            digest = SHA256,
            outputSize = outputLen.bytes,
            salt = salt,
            info = info,
        ).deriveSecretToByteArrayBlocking(ikm)

    fun chacha20Poly1305Encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        chachaKey(key).cipher().encryptWithIvBlocking(nonce, plaintext, aad)

    fun chacha20Poly1305Decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        chachaKey(key).cipher().decryptWithIvBlocking(nonce, ciphertext, aad)

    fun generateEd25519KeyPair(): KeyPair {
        val kp = eddsa.keyPairGenerator(EdDSA.Curve.Ed25519).generateKeyBlocking()
        return KeyPair(
            privateKey = kp.privateKey.encodeToByteArrayBlocking(EdDSA.PrivateKey.Format.RAW),
            publicKey = kp.publicKey.encodeToByteArrayBlocking(EdDSA.PublicKey.Format.RAW),
        )
    }

    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray =
        eddsa.privateKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArrayBlocking(EdDSA.PrivateKey.Format.RAW, privateKey)
            .signatureGenerator()
            .generateSignatureBlocking(message)

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        eddsa.publicKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.RAW, publicKey)
            .signatureVerifier()
            .tryVerifySignatureBlocking(message, signature)

    fun randomBytes(size: Int): ByteArray = CryptographyRandom.nextBytes(size)

    fun sha256(input: ByteArray): ByteArray = sha256Hasher.hashBlocking(input)

    private fun chachaKey(key: ByteArray): ChaCha20Poly1305.Key =
        chacha.keyDecoder().decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, key)
}
