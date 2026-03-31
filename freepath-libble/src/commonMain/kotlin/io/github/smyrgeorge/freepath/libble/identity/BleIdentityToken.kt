package io.github.smyrgeorge.freepath.libble.identity

import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.libble.identity.BleIdentityToken.ROTATION_INTERVAL_MS
import io.github.smyrgeorge.freepath.libble.identity.BleIdentityToken.TOKEN_LEN

/**
 * Rotating BLE identity token derived from a shared secret established during contact exchange.
 *
 * Each device advertises `token = HKDF-SHA256(identitySecret, epoch_bytes, info, 8)` where
 * `epoch = unix_millis / ROTATION_INTERVAL_MS`. Only contacts that hold the same shared secret
 * can compute the expected token and match the advertisement to a known peer.
 *
 * Properties:
 * - **No passive tracking:** token rotates every [ROTATION_INTERVAL_MS], so observers cannot
 *   correlate a device across rotation boundaries.
 * - **No active probing:** only holders of the shared secret can compute the expected token.
 * - **No connection needed:** matching happens at scan time from advertisement data.
 */
object BleIdentityToken {

    /** Token rotation interval: 15 minutes. */
    const val ROTATION_INTERVAL_MS = 15L * 60 * 1000

    /** Truncated token length in bytes. 8 bytes = 64 bits — sufficient for ~hundreds of contacts. */
    const val TOKEN_LEN = 8

    private val INFO = "freepath-ble-v2-token".encodeToByteArray()

    /**
     * Compute the identity token for the current epoch.
     *
     * @param identitySecret 32-byte shared secret from the contact exchange.
     * @param nowMillis current time in unix epoch milliseconds.
     * @return [TOKEN_LEN]-byte truncated token.
     */
    fun compute(identitySecret: ByteArray, nowMillis: Long): ByteArray {
        val epoch = nowMillis / ROTATION_INTERVAL_MS
        val salt = epochToBytes(epoch)
        return CryptoProvider.hkdfSha256(ikm = identitySecret, salt = salt, info = INFO, outputLen = TOKEN_LEN)
    }

    /**
     * Try to match an advertised [token] against a set of known contacts.
     *
     * Computes expected tokens for the current epoch and the previous epoch (to handle
     * the boundary window where advertiser and scanner may be in different epochs).
     *
     * @param token the [TOKEN_LEN]-byte token extracted from the advertisement.
     * @param contacts map of peerId → 32-byte identitySecret for all known contacts.
     * @param nowMillis current time in unix epoch milliseconds.
     * @return the matched peerId, or null if no contact matches.
     */
    fun match(token: ByteArray, contacts: Map<String, ByteArray>, nowMillis: Long): String? {
        if (token.size != TOKEN_LEN) return null
        val epoch = nowMillis / ROTATION_INTERVAL_MS
        for ((peerId, secret) in contacts) {
            // Check current epoch.
            if (constantTimeEquals(token, CryptoProvider.hkdfSha256(secret, epochToBytes(epoch), INFO, TOKEN_LEN))) {
                return peerId
            }
            // Check previous epoch (handles rotation boundary).
            if (epoch > 0 && constantTimeEquals(
                    a = token,
                    b = CryptoProvider.hkdfSha256(secret, epochToBytes(epoch - 1), INFO, TOKEN_LEN)
                )
            ) {
                return peerId
            }
        }
        return null
    }

    private fun epochToBytes(epoch: Long): ByteArray = ByteArray(8) { i -> (epoch shr (i * 8)).toByte() }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }
}
