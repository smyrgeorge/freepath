package io.github.smyrgeorge.freepath.transport

import io.github.smyrgeorge.freepath.transport.codec.AeadCodec
import io.github.smyrgeorge.freepath.transport.codec.Base58
import io.github.smyrgeorge.freepath.transport.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.transport.crypto.HandshakeHandler
import io.github.smyrgeorge.freepath.transport.model.ContactLookup
import io.github.smyrgeorge.freepath.transport.model.Frame
import io.github.smyrgeorge.freepath.transport.model.FrameType
import io.github.smyrgeorge.freepath.transport.model.LocalIdentity
import io.github.smyrgeorge.freepath.transport.model.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

class StatefulProtocol(
    identity: LocalIdentity,
    contactLookup: ContactLookup,
    private val linkAdapter: LinkAdapter,
    private val onFrameReceived: suspend (peerId: String, frame: Frame, session: SessionState) -> Unit,
    private val handshakeTimeout: Duration = 30.minutes,
) : Protocol {

    private val handshakeHandler = HandshakeHandler(identity, contactLookup)

    /** Active sessions keyed by verified peer nodeId (Base58-encoded). */
    private val sessions = mutableMapOf<String, SessionState>()
    private val sessionMutex = Mutex()

    /**
     * Pending initiator contexts keyed by streamId.
     * Holds the ephemeral private key and raw payload until frame 1 arrives.
     * Each entry records the time it was created so stale contexts can be evicted.
     */
    private data class PendingInitiator(
        val peerId: String,
        val ctx: HandshakeHandler.InitiatorContext,
        val createdAt: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
    )

    private val pendingInitiators = mutableMapOf<String, PendingInitiator>()
    private val initiatorMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun start() {
        linkAdapter.setInboundFrameHandler(::handleInboundFrame)
        linkAdapter.start()
        scope.launch {
            while (isActive) {
                delay(handshakeTimeout / 2)
                evictExpiredHandshakes()
            }
        }
    }

    override suspend fun stop() {
        // Snapshot active peer IDs and send CLOSE to each before tearing down the link.
        val activePeers = sessionMutex.withLock { sessions.keys.toList() }
        for (peerId in activePeers) closeSession(peerId)

        linkAdapter.stop()
        sessionMutex.withLock { sessions.clear() }
        initiatorMutex.withLock { pendingInitiators.clear() }
        scope.cancel()
    }

    /**
     * Evicts any pending initiator contexts that have exceeded [handshakeTimeout].
     * Per spec, incomplete handshake state MUST be abandoned and all associated resources
     * released if the handshake is not completed within an implementation-defined timeout.
     * Called automatically by an internal maintenance coroutine started in [start].
     */
    private suspend fun evictExpiredHandshakes() {
        val expiredPeerIds = mutableListOf<String>()
        initiatorMutex.withLock {
            val iter = pendingInitiators.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.value.createdAt.elapsedNow() > handshakeTimeout) {
                    expiredPeerIds += entry.value.peerId
                    iter.remove()
                }
            }
        }
        for (peerId in expiredPeerIds) linkAdapter.closeConnection(peerId)
    }

    /**
     * Atomically allocates the next outbound seq for [peerId], tearing down the session
     * and throwing if the seq counter is near rollover (spec: MUST tear down before 0xFFFFFFFF).
     */
    private suspend fun acquireOutboundSeq(peerId: String): Pair<SessionState, Long> {
        var rolledOverSession: SessionState? = null
        val result = sessionMutex.withLock {
            val s = sessions[peerId] ?: error("No active session for peer $peerId")
            if (s.outboundSeq >= SessionState.SEQ_ROLLOVER_THRESHOLD) {
                sessions.remove(peerId)
                rolledOverSession = s
                null
            } else {
                Pair(s, s.nextOutboundSeq())
            }
        }
        rolledOverSession?.let { s ->
            sendCloseFrame(peerId, s)
            linkAdapter.closeConnection(peerId)
            error("Session seq near rollover for peer $peerId; session closed, initiate a new handshake")
        }
        return result!!
    }

    override suspend fun send(peerId: String, payload: ByteArray) {
        val (session, seq) = acquireOutboundSeq(peerId)
        // Build a header-only frame to derive the AAD and nonce; payload is not part of either.
        val header = Frame(
            schema = HandshakeHandler.SCHEMA,
            streamId = session.streamId,
            seq = seq,
            type = FrameType.DATA,
            payload = "",
        )

        // Encrypt the payload using the header fields for AAD/nonce.
        val ciphertext = AeadCodec.encrypt(payload, header, session.sessionKey)
        val encryptedFrame = header.copy(payload = Base64.encode(ciphertext))

        // Send via link adapter
        linkAdapter.sendFrame(peerId, encryptedFrame)
    }

    /**
     * Sends an encrypted ACK frame acknowledging [ackedSeq].
     * Per spec, the plaintext payload is the 4-byte big-endian [ackedSeq] value.
     */
    suspend fun sendAck(peerId: String, ackedSeq: Long) {
        val (session, seq) = acquireOutboundSeq(peerId)
        // Spec: ACK plaintext payload is the 4-byte big-endian seq being acknowledged.
        val plaintext = ByteArray(4).also { buf ->
            buf[0] = ((ackedSeq shr 24) and 0xFF).toByte()
            buf[1] = ((ackedSeq shr 16) and 0xFF).toByte()
            buf[2] = ((ackedSeq shr 8) and 0xFF).toByte()
            buf[3] = (ackedSeq and 0xFF).toByte()
        }
        val header = Frame(
            schema = HandshakeHandler.SCHEMA,
            streamId = session.streamId,
            seq = seq,
            type = FrameType.ACK,
            payload = "",
        )
        val ciphertext = AeadCodec.encrypt(plaintext, header, session.sessionKey)
        val encryptedFrame = header.copy(payload = Base64.encode(ciphertext))
        linkAdapter.sendFrame(peerId, encryptedFrame)
    }

    suspend fun initiateHandshake(peerId: String) {
        val streamId = Base58.encode(CryptoProvider.randomBytes(16))
        val (frame0, ctx) = handshakeHandler.createInitiatorFrame(streamId)
        initiatorMutex.withLock { pendingInitiators[streamId] = PendingInitiator(peerId, ctx) }
        linkAdapter.sendFrame(peerId, frame0)
    }

    suspend fun handleInboundFrame(peerId: String, frame: Frame) {
        // Spec: receivers MUST reject any Frame whose schema value is not supported.
        if (frame.schema != HandshakeHandler.SCHEMA) return

        // Phase 1 + 2 helper: validate inbound seq under the mutex and decode the ciphertext.
        // Returns null (signalling early exit) if the session is missing, the seq is invalid,
        // or the payload is not valid Base64.
        suspend fun validateAndDecode(): Pair<SessionState, ByteArray>? {
            val session = sessionMutex.withLock {
                val s = sessions[peerId] ?: return null
                if (!s.isValidInboundSeq(frame.seq)) return null
                s
            }
            val ciphertext = runCatching { Base64.decode(frame.payload) }.getOrNull() ?: return null
            return Pair(session, ciphertext)
        }

        when (frame.type) {
            FrameType.HANDSHAKE -> {
                // Process handshake as responder or complete as initiator
                handleHandshakeFrame(peerId, frame)
            }

            FrameType.DATA, FrameType.ACK -> {
                val (session, ciphertext) = validateAndDecode() ?: return

                // Phase 2: decrypt outside the mutex (AEAD before accepting seq, per spec).
                // Reject frames that fail AEAD verification without advancing state.
                val plaintext = runCatching {
                    AeadCodec.decrypt(ciphertext, frame, session.sessionKey)
                }.getOrElse { return }

                // Phase 3: accept seq under the mutex, re-checking validity to guard against a
                // concurrent frame with the same seq that completed AEAD verification first.
                var rollover = false
                val decryptedFrame = sessionMutex.withLock {
                    val s = sessions[peerId] ?: return
                    if (!s.isValidInboundSeq(frame.seq)) return
                    s.acceptInboundSeq(frame.seq)
                    // Spec: session MUST be torn down before inbound seq reaches 0xFFFFFFFF.
                    if (s.inboundSeq >= SessionState.SEQ_ROLLOVER_THRESHOLD) {
                        sessions.remove(peerId)
                        rollover = true
                    }
                    frame.copy(payload = Base64.encode(plaintext))
                }

                if (rollover) {
                    // Send best-effort CLOSE for the removed session; do not deliver the frame.
                    sendCloseFrame(peerId, session)
                    linkAdapter.closeConnection(peerId)
                    return
                }

                // Deliver to application layer
                onFrameReceived(peerId, decryptedFrame, session)
            }

            FrameType.CLOSE -> {
                val (session, ciphertext) = validateAndDecode() ?: return

                // Phase 2: verify AEAD outside the mutex — spec requires rejecting unauthenticated
                // CLOSE frames. A CLOSE that fails AEAD MUST NOT cause session teardown.
                runCatching { AeadCodec.decrypt(ciphertext, frame, session.sessionKey) }
                    .onFailure { return }

                // Phase 3: re-check seq and accept under the mutex, guarding against a concurrent
                // frame with the same seq that completed AEAD verification first.
                sessionMutex.withLock {
                    val s = sessions[peerId] ?: return
                    if (!s.isValidInboundSeq(frame.seq)) return
                    s.acceptInboundSeq(frame.seq)
                    sessions.remove(peerId)
                }
                linkAdapter.closeConnection(peerId)
            }

            FrameType.UNKNOWN -> {
                // Spec: silently discard any frame with an unrecognised type after AEAD verification.
                // AEAD verification is required so an attacker cannot forge a discard-only frame.
                // Advance inboundSeq after verification to prevent replay of the same seq value.
                // frame.wireType (not "UNKNOWN") is used in the AAD, so verification succeeds for
                // any future type a peer encrypted with a type string unknown to this version.
                val (session, ciphertext) = validateAndDecode() ?: return

                // Phase 2: verify AEAD outside the mutex.
                runCatching { AeadCodec.decrypt(ciphertext, frame, session.sessionKey) }
                    .onFailure { return }

                // Phase 3: accept seq under the mutex; do NOT deliver to application.
                sessionMutex.withLock {
                    val s = sessions[peerId] ?: return
                    if (!s.isValidInboundSeq(frame.seq)) return
                    s.acceptInboundSeq(frame.seq)
                }
            }
        }
    }

    private suspend fun handleHandshakeFrame(peerId: String, frame: Frame) {
        // Spec: discard any HANDSHAKE frame received after the handshake has completed.
        val existingSession = sessionMutex.withLock { sessions[peerId] }
        if (existingSession != null) return

        val pending = initiatorMutex.withLock { pendingInitiators[frame.streamId] }

        if (pending != null) {
            // Discard timed-out initiator context — spec requires abandoning incomplete handshakes.
            if (pending.createdAt.elapsedNow() > handshakeTimeout) {
                initiatorMutex.withLock { pendingInitiators.remove(frame.streamId) }
                return
            }

            // We are the initiator — frame 1 arrived; complete the handshake.
            // Only remove the pending context after successful verification to prevent
            // a malicious replay (same streamId, different peerId) from consuming the
            // context before the legitimate frame 1 arrives.
            val (verifiedPeerId, session) = try {
                handshakeHandler.completeInitiatorHandshake(pending.ctx, frame)
            } catch (_: HandshakeHandler.HandshakeException) {
                // Spec: handshake MUST be aborted on any verification failure.
                initiatorMutex.withLock { pendingInitiators.remove(frame.streamId) }
                linkAdapter.closeConnection(peerId)
                return
            }
            initiatorMutex.withLock { pendingInitiators.remove(frame.streamId) }
            // The verified nodeId from the handshake must match the peer we intended to
            // connect to. A mismatch means the mDNS advertisement carried a spoofed nodeId
            // and the actual responder is a different identity; abort to prevent session
            // state being stored under the wrong peer.
            if (verifiedPeerId != peerId) {
                linkAdapter.closeConnection(peerId)
                return
            }
            sessionMutex.withLock { sessions[peerId] = session }
        } else {
            // We are the responder — frame 0 arrived; reply with frame 1.
            val (responseFrame, session, verifiedPeerId) = try {
                handshakeHandler.processInitiatorFrame(frame)
            } catch (_: HandshakeHandler.HandshakeException) {
                // Spec: handshake MUST be aborted on any verification failure.
                // Close the connection so the slot in LanLinkAdapter.connections is freed
                // immediately rather than waiting for the attacker to disconnect.
                linkAdapter.closeConnection(peerId)
                return
            }
            // The verified nodeId from the handshake must match the peer we're responding to.
            // A mismatch means the connecting peer presented credentials for a different identity.
            if (verifiedPeerId != peerId) {
                linkAdapter.closeConnection(peerId)
                return
            }
            // Send frame 1 before committing the session so that a send failure leaves no
            // orphaned session that would block the initiator from retrying the handshake.
            linkAdapter.sendFrame(peerId, responseFrame)
            sessionMutex.withLock { sessions[peerId] = session }
        }
    }

    suspend fun closeSession(peerId: String) {
        val session = sessionMutex.withLock { sessions.remove(peerId) } ?: return
        sendCloseFrame(peerId, session)
        linkAdapter.closeConnection(peerId)
    }

    /**
     * Builds and sends an encrypted CLOSE frame for [session]. Called both from [closeSession]
     * and from rollover paths where the session has already been removed from the map.
     * The send is best-effort; failures are silently swallowed.
     */
    private suspend fun sendCloseFrame(peerId: String, session: SessionState) {
        val seq = session.nextOutboundSeq()
        val closeFrame = Frame(
            schema = HandshakeHandler.SCHEMA,
            streamId = session.streamId,
            seq = seq,
            type = FrameType.CLOSE,
            payload = Base64.encode(ByteArray(0)),
        )
        val ciphertext = AeadCodec.encrypt(ByteArray(0), closeFrame, session.sessionKey)
        val encryptedFrame = closeFrame.copy(payload = Base64.encode(ciphertext))
        try {
            linkAdapter.sendFrame(peerId, encryptedFrame)
        } catch (_: Exception) {
            // Best effort
        }
    }

    suspend fun hasSession(peerId: String): Boolean =
        sessionMutex.withLock { sessions.containsKey(peerId) }
}
