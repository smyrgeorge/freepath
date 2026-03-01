package io.github.smyrgeorge.freepath.transport.crypto

import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.Base58
import io.github.smyrgeorge.freepath.transport.model.ContactLookup
import io.github.smyrgeorge.freepath.transport.model.Frame
import io.github.smyrgeorge.freepath.transport.model.FrameType
import io.github.smyrgeorge.freepath.transport.model.LocalIdentity
import io.github.smyrgeorge.freepath.transport.model.SessionState
import kotlin.io.encoding.Base64

class HandshakeHandler(
    private val identity: LocalIdentity,
    private val contactLookup: ContactLookup,
) {
    fun createInitiatorFrame(streamId: String): Pair<Frame, InitiatorContext> {
        val ephemeral = CryptoProvider.generateX25519KeyPair()
        val rawPayload = buildPayload(ephemeral.publicKey)
        val frame = Frame(
            schema = SCHEMA,
            streamId = streamId,
            seq = 0L,
            type = FrameType.HANDSHAKE,
            payload = Base64.encode(rawPayload),
        )
        return Pair(frame, InitiatorContext(ephemeral.privateKey, rawPayload))
    }

    /**
     * Completes the initiator side of the handshake and returns the cryptographically
     * verified peer nodeId alongside the derived [SessionState]. Callers MUST verify
     * that the returned nodeId matches the peer they intended to connect to before
     * registering the session.
     */
    fun completeInitiatorHandshake(ctx: InitiatorContext, responderFrame: Frame): Pair<String, SessionState> {
        val rawPayload1 = Base64.decode(responderFrame.payload)
        val fields = parseAndVerifyPayload(rawPayload1)
        val verifiedPeerId = Base58.encode(fields.nodeIdRaw)
        val session = deriveSession(
            localEphPriv = ctx.ephemeralPrivateKey,
            peerEphPub = fields.ephemeralKey,
            payload0 = ctx.rawPayload,
            payload1 = rawPayload1,
            streamId = responderFrame.streamId,
        )
        return Pair(verifiedPeerId, session)
    }

    /**
     * Processes an initiator HANDSHAKE frame and returns the response frame, the derived
     * [SessionState], and the cryptographically verified initiator nodeId. Callers MUST
     * verify that the returned nodeId matches the peer they are responding to before
     * registering the session.
     */
    fun processInitiatorFrame(initiatorFrame: Frame): Triple<Frame, SessionState, String> {
        val rawPayload0 = Base64.decode(initiatorFrame.payload)
        val fields = parseAndVerifyPayload(rawPayload0)
        val verifiedPeerId = Base58.encode(fields.nodeIdRaw)

        val ephemeral = CryptoProvider.generateX25519KeyPair()
        val rawPayload1 = buildPayload(ephemeral.publicKey)
        val responseFrame = Frame(
            schema = SCHEMA,
            streamId = initiatorFrame.streamId,
            seq = 0L,
            type = FrameType.HANDSHAKE,
            payload = Base64.encode(rawPayload1),
        )
        val session = deriveSession(
            localEphPriv = ephemeral.privateKey,
            peerEphPub = fields.ephemeralKey,
            payload0 = rawPayload0,
            payload1 = rawPayload1,
            streamId = initiatorFrame.streamId,
        )
        return Triple(responseFrame, session, verifiedPeerId)
    }

    // ---- Helpers ---------------------------------------------------------

    private fun buildPayload(ephemeralPublicKey: ByteArray): ByteArray {
        // SIGNATURE covers (EPHEMERAL_KEY ∥ NODEID_RAW)
        val signedData = ephemeralPublicKey + identity.nodeIdRaw
        val signature = CryptoProvider.ed25519Sign(identity.sigKeyPrivate, signedData)
        return ephemeralPublicKey + identity.sigKeyPublic + identity.nodeIdRaw + signature
    }

    private fun parseAndVerifyPayload(payload: ByteArray): HandshakeFields {
        if (payload.size != PAYLOAD_SIZE)
            throw HandshakeException("Invalid handshake payload size: ${payload.size}")

        val fields = HandshakeFields(
            ephemeralKey = payload.copyOfRange(0, 32),
            sigKey = payload.copyOfRange(32, 64),
            nodeIdRaw = payload.copyOfRange(64, 80),
            signature = payload.copyOfRange(80, 144),
        )

        // Look up the peer's sigKey from the contact list — do NOT trust the received sigKey.
        // Verify the received SIGKEY matches the key on file (spec requirement), then verify
        // the signature using the trusted key from the contact list.
        val trustedSigKey = contactLookup.getSigKey(fields.nodeIdRaw)
            ?: throw HandshakeException("Unknown peer nodeId")

        if (!fields.sigKey.contentEquals(trustedSigKey))
            throw HandshakeException("Received SIGKEY does not match contact list")

        val signedData = fields.ephemeralKey + fields.nodeIdRaw
        if (!CryptoProvider.ed25519Verify(trustedSigKey, signedData, fields.signature))
            throw HandshakeException("Handshake signature verification failed")

        return fields
    }

    private fun deriveSession(
        localEphPriv: ByteArray,
        peerEphPub: ByteArray,
        payload0: ByteArray,
        payload1: ByteArray,
        streamId: String,
    ): SessionState {
        val sharedSecret = CryptoProvider.x25519DH(localEphPriv, peerEphPub)
        // Low-order point check: reject all-zero output
        if (sharedSecret.all { it == 0.toByte() })
            throw HandshakeException("X25519 produced low-order point (all-zero shared secret)")

        val sessionKey = CryptoProvider.hkdfSha256(
            ikm = sharedSecret,
            salt = ByteArray(32),          // 32 zero bytes per spec
            info = payload0 + payload1,    // binds key to this specific exchange
            outputLen = 32,
        )
        return SessionState(streamId = streamId, sessionKey = sessionKey)
    }

    /** Context held by the initiator between creating frame 0 and receiving frame 1. */
    class InitiatorContext(
        val ephemeralPrivateKey: ByteArray,
        val rawPayload: ByteArray,
    )

    private class HandshakeFields(
        val ephemeralKey: ByteArray,
        val sigKey: ByteArray,
        val nodeIdRaw: ByteArray,
        val signature: ByteArray,
    )

    class HandshakeException(message: String) : Exception(message)

    companion object {
        const val SCHEMA = 1
        const val PAYLOAD_SIZE = 144  // 32 + 32 + 16 + 64
    }
}
