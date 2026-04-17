package io.github.smyrgeorge.freepath.libp2p

internal object Libp2pCallback {
    @JvmStatic
    fun onEvent(
        eventHandle: Long,
        kind: Byte,
        reqId: Long,
        peerId: String,
        addr: String,
        key: ByteArray,
        value: ByteArray,
    ) {
        val handler = Libp2pModule.getEventHandler(eventHandle) ?: return
        val event = when (kind.toInt()) {
            0 -> Libp2pEvent.PeerConnected(peerId, addr)
            1 -> Libp2pEvent.PeerDisconnected(peerId)
            3 -> Libp2pEvent.NewListenAddr(peerId) // addr stored in peerId field by Rust
            4 -> Libp2pEvent.PeerIdentified(peerId)
            6 -> Libp2pEvent.RequestReceived(reqId, senderId = peerId, recipientId = addr, value)
            7 -> Libp2pEvent.ResponseReceived(reqId, senderId = peerId, recipientId = addr, value)
            8 -> Libp2pEvent.RequestFailed(reqId, senderId = peerId, recipientId = addr, error = value.decodeToString())
            9 -> Libp2pEvent.RelayConnected(relayPeerId = peerId)
            10 -> Libp2pEvent.RelayRegistered(
                relayPeerId = peerId,
                namespace = addr,
                ttl = value.decodeToString().toLongOrNull() ?: 0L
            )

            11 -> Libp2pEvent.RelayRegistrationFailed(relayPeerId = peerId, error = value.decodeToString())
            14 -> Libp2pEvent.AutonatProbeFailed(
                testedAddr = peerId,
                server = addr,
                error = value.decodeToString()
            )

            15 -> Libp2pEvent.AutonatProbeSucceeded(testedAddr = peerId, server = addr)
            16 -> Libp2pEvent.UpnpGatewayNotFound
            17 -> Libp2pEvent.UpnpNonRoutableGateway
            18 -> Libp2pEvent.UpnpNewExternalAddr(addr = peerId)
            19 -> Libp2pEvent.UpnpExpiredExternalAddr(addr = peerId)
            else -> return
        }
        handler(event)
    }

    @JvmStatic
    fun isKnownContact(handlerId: Long, peerId: String): Boolean =
        Libp2pModule.isKnownContact(handlerId, peerId)
}
