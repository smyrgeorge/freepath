package io.github.smyrgeorge.freepath.transport

/**
 * Protocol / Framing Layer: responsible for wrapping application content into [Frame]s,
 * managing stream identifiers and sequence numbers, and encrypting payloads with
 * session keys established via handshake.
 *
 * According to spec/5-transport.md:
 * - Assigns streamId and seq
 * - Encrypts with session key (ChaCha20-Poly1305 for schema version 1)
 * - Wraps into a Frame
 *
 * This interface abstracts the protocol operations from the physical transport.
 * Bidirectional transports use [StatefulProtocol]; unidirectional transports use
 * StatelessEnvelope instead (bypassing the Frame stack entirely).
 */
interface Protocol {

    /**
     * Sends application content [payload] to the peer identified by [peerId].
     *
     * The protocol layer:
     * 1. Assigns streamId and seq for the session
     * 2. Encrypts the payload with the session key
     * 3. Wraps it into a Frame
     * 4. Passes the Frame to the link adapter for transmission
     *
     * @param peerId Base58-encoded Node ID of the recipient
     * @param payload Raw application content bytes (not yet encrypted)
     */
    suspend fun send(peerId: String, payload: ByteArray)

    /**
     * Initiates the protocol layer. For stateful protocols, this may include
     * starting handshake listeners or initializing session state.
     */
    suspend fun start()

    /**
     * Tears down all active sessions and releases protocol-layer resources.
     */
    suspend fun stop()
}
