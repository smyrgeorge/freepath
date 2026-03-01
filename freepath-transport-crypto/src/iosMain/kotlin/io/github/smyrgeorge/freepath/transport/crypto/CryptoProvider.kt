@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.smyrgeorge.freepath.transport.crypto

import CryptoBridge.CryptoBridge
import platform.Foundation.NSData

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object CryptoProvider {

    actual fun generateX25519KeyPair(): KeyPair {
        val result = CryptoBridge.generateX25519KeyPair()
        return KeyPair((result[0] as NSData).toByteArray(), (result[1] as NSData).toByteArray())
    }

    actual fun x25519DH(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        objcCall { CryptoBridge.x25519DHWithPrivateKey(privateKey.toNSData(), publicKey.toNSData(), it) }.toByteArray()

    actual fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLen: Int): ByteArray =
        CryptoBridge.hkdfSha256WithIkm(
            ikm = ikm.toNSData(),
            salt = salt.toNSData(),
            info = info.toNSData(),
            outputLen = outputLen.toLong()
        ).toByteArray()

    actual fun chacha20Poly1305Encrypt(
        key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray
    ): ByteArray {
        // ChaChaPoly.combined = nonce(12) ∥ ciphertext ∥ tag — strip nonce prefix
        // so output matches the JVM wire format (ciphertext ∥ tag only).
        val combined = objcCall {
            CryptoBridge.chachaEncryptWithKey(
                key = key.toNSData(),
                nonce = nonce.toNSData(),
                plaintext = plaintext.toNSData(),
                aad = aad.toNSData(),
                it
            )
        }.toByteArray()
        return combined.copyOfRange(12, combined.size)
    }

    actual fun chacha20Poly1305Decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        // ChaChaPoly.SealedBox(combined:) expects nonce(12) ∥ ciphertext ∥ tag.
        val combined = nonce + ciphertext
        return objcCall {
            CryptoBridge.chachaDecryptWithKey(
                key = key.toNSData(),
                ciphertext = combined.toNSData(),
                aad = aad.toNSData(),
                error = it
            )
        }.toByteArray()
    }

    actual fun generateEd25519KeyPair(): KeyPair {
        val result = CryptoBridge.generateEd25519KeyPair()
        return KeyPair((result[0] as NSData).toByteArray(), (result[1] as NSData).toByteArray())
    }

    actual fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray =
        objcCall { CryptoBridge.signWithPrivateKey(privateKey.toNSData(), message.toNSData(), it) }.toByteArray()

    actual fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        CryptoBridge.verifyWithPublicKey(publicKey.toNSData(), message.toNSData(), signature.toNSData())

    actual fun randomBytes(size: Int): ByteArray = CryptoBridge.randomBytes(size.toLong()).toByteArray()

    actual fun sha256(input: ByteArray): ByteArray = CryptoBridge.sha256(input.toNSData()).toByteArray()
}

