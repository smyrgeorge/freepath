package io.github.smyrgeorge.freepath.contact.exchange

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.ContactCardCodec
import io.github.smyrgeorge.freepath.contact.ContactCardSigned
import kotlin.io.encoding.Base64

/**
 * Handles QR code-based contact exchange (unidirectional mode).
 *
 * Encodes a signed contact card into a QR-compatible string format and decodes
 * it back, verifying the signature and Node ID.
 *
 * QR Code Format:
 * - The QR code contains a Base64-encoded [io.github.smyrgeorge.freepath.contact.ContactCardSigned] JSON.
 * - The format is: `freepath://contact/v1/<base64url-encoded-signed-card>`
 * - The scheme prefix allows future extensibility and clear identification.
 */
object QrCodeContactExchange : ContactExchange {

    override val method: ContactExchangeMethod = ContactExchangeMethod.QR

    private const val SCHEME = "freepath"
    private const val PATH_CONTACT = "contact"
    private const val VERSION = "v1"
    private const val PREFIX = "$SCHEME://$PATH_CONTACT/$VERSION/"

    /**
     * Encodes a [card] and [signature] into a QR code string.
     *
     * @param card The contact card to share.
     * @param signature The Base64-encoded Ed25519 signature of the card.
     * @return A QR code string ready for display.
     */
    fun encode(card: ContactCard, signature: String): String {
        val signed = ContactCardSigned(card, signature)
        val jsonBytes = ContactCardCodec.encode(signed)
        val base64Url = Base64.encode(jsonBytes)
        return "$PREFIX$base64Url"
    }

    /**
     * Encodes a [card] by signing it with [sigKeyPrivate] and returns a QR code string.
     *
     * @param card The contact card to share.
     * @param sigKeyPrivate The Ed25519 private key for signing.
     * @return A QR code string ready for display.
     */
    override fun encode(card: ContactCard, sigKeyPrivate: ByteArray): ByteArray {
        val signed = ContactCardCodec.seal(card, sigKeyPrivate)
        return encode(signed).encodeToByteArray()
    }

    /**
     * Encodes a [signed] card into a QR code string.
     *
     * @param signed The signed contact card to encode.
     * @return A QR code string ready for display.
     */
    fun encode(signed: ContactCardSigned): String {
        val jsonBytes = ContactCardCodec.encode(signed)
        val base64Url = Base64.encode(jsonBytes)
        return "$PREFIX$base64Url"
    }

    /**
     * Decodes and verifies a QR code string, returning the verified [ContactCard].
     *
     * Performs the following checks per spec 3:
     * 1. Schema check - verifies the card schema is supported.
     * 2. Node ID verification - derives Node ID from sigKey and compares.
     * 3. Signature verification - verifies the card signature.
     *
     * @param qrCode The QR code string to decode.
     * @return [Result.success] with the verified card, or [Result.failure] on error.
     */
    fun decode(qrCode: String): Result<ContactCard> {
        return decode(qrCode.encodeToByteArray())
    }

    /**
     * Decodes and verifies a QR code string, returning the verified [ContactCard].
     *
     * Performs the following checks per spec 3:
     * 1. Schema check - verifies the card schema is supported.
     * 2. Node ID verification - derives Node ID from sigKey and compares.
     * 3. Signature verification - verifies the card signature.
     *
     * @param data The QR code data to decode.
     * @return [Result.success] with the verified card, or [Result.failure] on error.
     */
    override fun decode(data: ByteArray): Result<ContactCard> {
        val qrCode = data.decodeToString()

        // Validate prefix
        if (!qrCode.startsWith(PREFIX)) {
            return Result.failure(
                IllegalArgumentException("Invalid QR code format: expected prefix '$PREFIX'")
            )
        }

        // Extract Base64 payload
        val base64Payload = qrCode.substring(PREFIX.length)
        if (base64Payload.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty QR code payload"))
        }

        // Decode Base64 (add padding if needed)
        val paddedBase64 = addBase64Padding(base64Payload)
        val jsonBytes: ByteArray
        try {
            jsonBytes = Base64.decode(paddedBase64)
        } catch (e: Exception) {
            return Result.failure(
                IllegalArgumentException("Failed to decode Base64 payload", e)
            )
        }

        // Parse signed card
        val signed: ContactCardSigned
        try {
            signed = ContactCardCodec.decodeSigned(jsonBytes)
        } catch (e: Exception) {
            return Result.failure(
                IllegalArgumentException("Failed to parse contact card JSON", e)
            )
        }

        // (1) Schema check - per spec 3: step 1
        if (signed.card.schema != ContactCard.SCHEMA) {
            return Result.failure(IllegalStateException("Unsupported card schema: ${signed.card.schema}"))
        }

        // (2) Verify Node ID - per spec 3: step 2
        if (!ContactCardCodec.validateNodeId(signed.card)) {
            return Result.failure(IllegalStateException("Node ID mismatch"))
        }

        // (3) Verify signature - per spec 3: step 3
        val signatureBytes = Base64.decode(signed.signature)
        if (!ContactCardCodec.verify(signed.card, signatureBytes)) {
            return Result.failure(IllegalStateException("Invalid card signature"))
        }

        return Result.success(signed.card)
    }

    /**
     * Decodes a QR code string and returns the raw [ContactCardSigned] without verification.
     * Use [decode] for the standard flow with verification.
     *
     * @param qrCode The QR code string to decode.
     * @return The decoded [ContactCardSigned], or null if parsing fails.
     */
    fun decodeRaw(qrCode: String): ContactCardSigned? {
        if (!qrCode.startsWith(PREFIX)) return null

        val base64Payload = qrCode.substring(PREFIX.length)
        if (base64Payload.isEmpty()) return null

        return try {
            val paddedBase64 = addBase64Padding(base64Payload)
            val jsonBytes = Base64.decode(paddedBase64)
            ContactCardCodec.decodeSigned(jsonBytes)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Adds padding to a Base64 string if needed.
     * Base64 strings should have length divisible by 4, padded with '='.
     */
    private fun addBase64Padding(base64: String): String {
        val padding = (4 - (base64.length % 4)) % 4
        return base64 + "=".repeat(padding)
    }

    /**
     * Returns the maximum expected QR code string length for capacity planning.
     * This is approximate and depends on the card's optional fields.
     */
    fun estimateQrCodeLength(card: ContactCard): Int {
        val signed = ContactCardSigned(card, Base64.encode(ByteArray(64))) // 64-byte signature
        val jsonLength = ContactCardCodec.encode(signed).size
        val base64Length = ((jsonLength + 2) / 3) * 4 // Base64 encoding overhead
        return PREFIX.length + base64Length
    }

}
