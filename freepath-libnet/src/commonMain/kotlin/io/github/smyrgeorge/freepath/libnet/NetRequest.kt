package io.github.smyrgeorge.freepath.libnet

data class NetRequest(
    val senderId: String,
    val recipientId: String,
    val reqId: Long,
    val payload: ByteArray,
    val transport: Transport,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as NetRequest
        return reqId == other.reqId
    }

    override fun hashCode(): Int = reqId.hashCode()
    override fun toString(): String =
        "NetRequest(senderId='$senderId', recipientId='$recipientId', reqId=$reqId, transport=$transport, payload=${payload.size} bytes)"
}
