package io.github.smyrgeorge.freepath.libble.exchange

import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.contact.ContactSignedCodec
import io.github.smyrgeorge.freepath.crypto.CryptoProvider

internal object BleExchangeCrypto {

    const val STATUS_SUCCESS: Byte = 0x00
    const val STATUS_FAILURE: Byte = 0x01
    const val STATUS_ERROR: Byte = 0x02

    const val ROLE_I: Byte = 0x01
    const val ROLE_R: Byte = 0x02

    private val INFO_SESSION = "freepath-ble-v2-session".encodeToByteArray()
    private val INFO_PIN = "freepath-ble-v2-pin".encodeToByteArray()
    private val INFO_PIN_CONFIRM_I = "freepath-ble-v2-pin-I".encodeToByteArray()
    private val INFO_PIN_CONFIRM_R = "freepath-ble-v2-pin-R".encodeToByteArray()

    class SessionKeys(
        val sessionKey: ByteArray,
        val sessionId: ByteArray,
        val pinConfirmI: ByteArray,
        val pinConfirmR: ByteArray,
    )

    /**
     * Derives all session keys from the local ephemeral private key, the peer's
     * ephemeral public key, and the shared PIN.
     *
     * [isInitiator] controls which public key is treated as i_eph_pub in the
     * canonical salt and session_id (always i_eph_pub || r_eph_pub).
     */
    fun deriveKeys(
        ownEphPriv: ByteArray,
        ownEphPub: ByteArray,
        peerEphPub: ByteArray,
        isInitiator: Boolean,
        pin: String,
    ): SessionKeys {
        val sharedSecret = CryptoProvider.x25519DH(ownEphPriv, peerEphPub)
        val (iEphPub, rEphPub) = if (isInitiator) ownEphPub to peerEphPub else peerEphPub to ownEphPub
        val sessionId = CryptoProvider.sha256(iEphPub + rEphPub)
        val sessionKey = CryptoProvider.hkdfSha256(sharedSecret, iEphPub + rEphPub, INFO_SESSION, 32)
        val pinKey = CryptoProvider.hkdfSha256(sessionKey, pin.encodeToByteArray(), INFO_PIN, 32)
        val pinConfirmI = CryptoProvider.hkdfSha256(pinKey, sessionId, INFO_PIN_CONFIRM_I, 32)
        val pinConfirmR = CryptoProvider.hkdfSha256(pinKey, sessionId, INFO_PIN_CONFIRM_R, 32)
        return SessionKeys(sessionKey, sessionId, pinConfirmI, pinConfirmR)
    }

    /** Returns `nonce (12 bytes) || ciphertext+tag` for [contact]. */
    fun encryptContact(
        contact: Contact,
        sigKeyPrivate: ByteArray,
        sessionKey: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val signed = ContactSignedCodec.seal(contact, sigKeyPrivate)
        val cardBytes = ContactSignedCodec.encode(signed)
        val nonce = CryptoProvider.randomBytes(12)
        val ciphertext = CryptoProvider.chacha20Poly1305Encrypt(sessionKey, nonce, cardBytes, aad)
        return nonce + ciphertext
    }

    /** Decrypts and verifies a card from `nonce || ciphertext+tag`. */
    fun decryptContact(encryptedContact: ByteArray, sessionKey: ByteArray, aad: ByteArray): Contact {
        require(encryptedContact.size > 12) { "encrypted card payload too short" }
        val nonce = encryptedContact.copyOfRange(0, 12)
        val ciphertext = encryptedContact.copyOfRange(12, encryptedContact.size)
        val cardBytes = CryptoProvider.chacha20Poly1305Decrypt(sessionKey, nonce, ciphertext, aad)
        return ContactSignedCodec.open(ContactSignedCodec.decode(cardBytes)).getOrThrow()
    }

    /** `role_byte || session_id` */
    fun cardAad(role: Byte, sessionId: ByteArray): ByteArray = byteArrayOf(role) + sessionId

    /** Constant-time byte array comparison. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }
}
