package io.github.smyrgeorge.freepath.transport

import io.github.smyrgeorge.freepath.transport.model.Frame

/**
 * A Link Adapter: a self-contained component responsible for fragmenting outbound [Frame]s
 * to fit the transport's MTU, reassembling inbound fragments, and delivering received
 * packets to the upper layer.
 *
 * The Link Adapter wraps each serialised Frame — or a fragment of one — in a
 * [io.github.smyrgeorge.freepath.transport.model.LinkAdapterPacket] before handing it to the physical transport.
 *
 * Link Adapters operate at the Transport / Physical layer and handle:
 * - Fragmentation when a Frame exceeds the transport's MTU
 * - Reassembly of incoming fragments
 * - Physical transport operations (bind ports, advertising, scanning, connections)
 *
 * Link Adapters MUST NOT perform encryption. Encryption is the responsibility of the
 * Protocol layer (StatefulProtocol for bidirectional transports).
 *
 * Unidirectional transports (optical, sound, USB) bypass the Frame stack entirely and
 * use `StatelessEnvelope` — implementations of those adapters should extend this interface
 * but send envelopes rather than framed session traffic.
 *
 * Adding a new transport means implementing this interface; the Frame format, the handshake,
 * and everything built on top stay exactly as they are.
 */
interface LinkAdapter {

    /**
     * Registers the handler that the link adapter calls once per fully-reassembled inbound
     * [Frame]. The adapter is responsible for decoding the physical transport's wire format,
     * reassembling [io.github.smyrgeorge.freepath.transport.model.LinkAdapterPacket] fragments,
     * and then invoking this handler with the peer's Base58-encoded Node ID and the decoded
     * [Frame].
     *
     * MUST be called before [start]. After [stop] returns the adapter MUST NOT invoke this
     * handler again.
     */
    fun setInboundFrameHandler(handler: suspend (peerId: String, frame: Frame) -> Unit)

    /**
     * Starts the link adapter: binds ports, begins advertising / scanning, and accepts
     * inbound connections. Must be called after [setInboundFrameHandler] and before [sendFrame].
     */
    suspend fun start()

    /**
     * Tears down all active connections, stops advertising / scanning, and releases
     * all resources associated with this link adapter. After [stop] returns, the adapter
     * MUST NOT deliver any further frames to the protocol layer.
     */
    suspend fun stop()

    /**
     * Closes the physical connection to [peerId] and releases its associated resources.
     * Called by the Protocol layer after sending a CLOSE frame to signal orderly session
     * teardown (seq rollover, idle timeout, explicit close, inbound CLOSE received).
     *
     * If no connection exists for [peerId] this is a no-op.
     */
    suspend fun closeConnection(peerId: String)

    /**
     * Sends [frame] to the peer identified by [peerId] (Base58-encoded Node ID).
     * The link adapter is responsible for fragmenting the frame if it exceeds the link MTU
     * and transmitting the resulting [io.github.smyrgeorge.freepath.transport.model.LinkAdapterPacket]s
     * over the physical transport.
     *
     * The frame payload is assumed to already be encrypted by the Protocol layer.
     */
    suspend fun sendFrame(peerId: String, frame: Frame)
}
