package io.github.smyrgeorge.freepath.libp2p

sealed class Libp2pEvent {
    data class PeerConnected(val peerId: String, val addr: String) : Libp2pEvent()
    data class PeerDisconnected(val peerId: String) : Libp2pEvent()
    data class NewListenAddr(val addr: String) : Libp2pEvent()
    data class PeerIdentified(val peerId: String) : Libp2pEvent()
    data class MdnsPeerDiscovered(val peerId: String, val addr: String) : Libp2pEvent()
    data class MdnsPeerExpired(val peerId: String) : Libp2pEvent()

    /** Relay peer was identified — connection + identify completed. */
    data class RelayConnected(val relayPeerId: String) : Libp2pEvent()

    /** Successfully registered with a rendezvous relay. */
    data class RelayRegistered(val relayPeerId: String, val namespace: String, val ttl: Long) : Libp2pEvent()

    /** Failed to register with a rendezvous relay. */
    data class RelayRegistrationFailed(val relayPeerId: String, val error: String) : Libp2pEvent()

    /** AutoNAT v2 probe succeeded — tested addr was verified reachable via the server. */
    data class AutonatProbeSucceeded(val testedAddr: String, val server: String) : Libp2pEvent()

    /** AutoNAT v2 probe failed — tested addr was not reachable via the server. */
    data class AutonatProbeFailed(val testedAddr: String, val server: String, val error: String) : Libp2pEvent()

    /** UPnP: no IGD gateway found on the local network. */
    data object UpnpGatewayNotFound : Libp2pEvent()

    /** UPnP: gateway is not routable (carrier-grade NAT or private IP). */
    data object UpnpNonRoutableGateway : Libp2pEvent()

    /** UPnP: new external address mapped on the gateway. */
    data class UpnpNewExternalAddr(val addr: String) : Libp2pEvent()

    /** UPnP: mapped external address expired / was removed. */
    data class UpnpExpiredExternalAddr(val addr: String) : Libp2pEvent()

    data class RequestReceived(
        val reqId: Long,
        val senderId: String,
        val recipientId: String,
        val payload: ByteArray
    ) : Libp2pEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as RequestReceived
            return reqId == other.reqId
        }

        override fun hashCode(): Int = reqId.hashCode()
        override fun toString(): String =
            "RequestReceived(reqId=$reqId, senderId='$senderId', recipientId='$recipientId', payload=${payload.size} bytes)"
    }

    sealed class Response : Libp2pEvent()

    data class RequestFailed(
        val reqId: Long,
        val senderId: String,
        val recipientId: String,
        val error: String
    ) : Response()

    data class ResponseReceived(
        val reqId: Long,
        val senderId: String,
        val recipientId: String,
        val payload: ByteArray
    ) : Response() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as ResponseReceived
            return reqId == other.reqId
        }

        override fun hashCode(): Int = reqId.hashCode()
        override fun toString(): String =
            "ResponseReceived(reqId=$reqId, senderId='$senderId', recipientId='$recipientId', payload=${payload.size} bytes)"
    }
}
