package io.github.smyrgeorge.freepath.contact.exchange

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.ContactCardCodec
import io.github.smyrgeorge.freepath.contact.ContactCardSigned
import io.github.smyrgeorge.freepath.contact.ContactCardSignedCodec
import io.github.smyrgeorge.freepath.contact.exchange.QrCodeContactExchange.decode
import kotlin.io.encoding.Base64

object QrCodeContactExchange : ContactExchange {

    override val method: ContactExchangeMethod = ContactExchangeMethod.QR

    private const val SCHEME = "freepath"
    private const val PATH_CONTACT = "contact"
    private const val VERSION = "v1"
    private const val PREFIX = "$SCHEME://$PATH_CONTACT/$VERSION/"

    override fun encode(card: ContactCard, sigKeyPrivate: ByteArray): ByteArray {
        val signed = ContactCardSignedCodec.seal(card, sigKeyPrivate)
        return encode(signed).encodeToByteArray()
    }

    fun encode(signed: ContactCardSigned): String {
        val bytes = ContactCardSignedCodec.encode(signed)
        val base64Url = Base64.encode(bytes)
        return "$PREFIX$base64Url"
    }

    fun decode(qrCode: String): Result<ContactCard> = decode(qrCode.encodeToByteArray())

    override fun decode(data: ByteArray): Result<ContactCard> {
        val qrCode = data.decodeToString()

        // Validate prefix
        if (!qrCode.startsWith(PREFIX)) {
            return Result.failure(IllegalArgumentException("Invalid QR code format: expected prefix '$PREFIX'"))
        }

        // Extract Base64 payload
        val base64Payload = qrCode.substring(PREFIX.length)
        if (base64Payload.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty QR code payload"))
        }

        // Decode Base64 (add padding if needed)
        val paddedBase64 = addBase64Padding(base64Payload)
        val bytes: ByteArray
        try {
            bytes = Base64.decode(paddedBase64)
        } catch (e: Exception) {
            return Result.failure(
                IllegalArgumentException("Failed to decode Base64 payload", e)
            )
        }

        // Parse signed card
        val signed: ContactCardSigned
        try {
            signed = ContactCardSignedCodec.decode(bytes)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Failed to parse contact card PROTOBUF", e))
        }

        // (1) Schema check - per spec 3: step 1
        if (signed.card.schema != ContactCard.SCHEMA) {
            return Result.failure(IllegalStateException("Unsupported card schema: ${signed.card.schema}"))
        }

        // (2) Verify signature - per spec 3: step 2
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
            val bytes = Base64.decode(paddedBase64)
            ContactCardSignedCodec.decode(bytes)
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
        val length = ContactCardSignedCodec.encode(signed).size
        val base64Length = ((length + 2) / 3) * 4 // Base64 encoding overhead
        return PREFIX.length + base64Length
    }
}
