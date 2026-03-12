package io.github.smyrgeorge.freepath.libp2p

sealed class Libp2pEvent {
    data class PeerConnected(val peerId: String, val addr: String) : Libp2pEvent()
    data class PeerDisconnected(val peerId: String) : Libp2pEvent()
    data class NewListenAddr(val addr: String) : Libp2pEvent()
    data class PeerIdentified(val peerId: String) : Libp2pEvent()
    data class MdnsPeerDiscovered(val peerId: String, val addr: String) : Libp2pEvent()
    data class MdnsPeerExpired(val peerId: String) : Libp2pEvent()

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
