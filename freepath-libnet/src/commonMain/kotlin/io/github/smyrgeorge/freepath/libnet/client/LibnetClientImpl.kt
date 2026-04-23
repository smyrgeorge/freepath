package io.github.smyrgeorge.freepath.libnet.client

import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.content.Content
import io.github.smyrgeorge.freepath.content.ContentCodec
import io.github.smyrgeorge.freepath.content.Message
import io.github.smyrgeorge.freepath.content.MessageCodec
import io.github.smyrgeorge.freepath.libnet.LibnetModule
import io.github.smyrgeorge.freepath.libnet.NetRequest
import io.github.smyrgeorge.freepath.libnet.client.codec.LibnetClientCodec
import io.github.smyrgeorge.freepath.libnet.client.model.StatelessEnvelope
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

class LibnetClientImpl(
    private val identity: Identity,
    private val libnet: LibnetModule,
    private val contactLookup: (peerId: String) -> Contact?,
    private val onMessageReceived: suspend (Message) -> Result<Unit>,
    private val onContentReceived: suspend (Content) -> Result<Unit>,
    private val onRelayPacket: suspend (envelope: StatelessEnvelope) -> Unit,
) : LibnetClient {
    private val log: Logger = Logger.of(this::class)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun start() {
        scope.launch { libnet.requests.consumeAsFlow().collect { r -> open(r) } }
    }

    override fun stop() {
        scope.cancel()
    }

    override suspend fun send(
        message: Message,
        receiverId: String,
        reqId: Long,
        onFrameSent: (reqId: Long, frameIndex: Int, frameCount: Int) -> Unit,
    ): Result<Unit> {
        val receiver = receiverContact(receiverId).getOrElse { return Result.failure(it) }
        val payload = seal(receiver, TYPE_CHAT, MessageCodec.encode(message))
        return libnet.request(reqId, receiverId, payload, onFrameSent).map { }
    }

    override suspend fun send(
        content: Content,
        receiverId: String,
        reqId: Long,
        onFrameSent: (reqId: Long, frameIndex: Int, frameCount: Int) -> Unit,
    ): Result<Unit> {
        val receiver = receiverContact(receiverId).getOrElse { return Result.failure(it) }
        val payload = seal(receiver, TYPE_CONTENT, ContentCodec.encode(content))
        return libnet.request(reqId, receiverId, payload, onFrameSent).map { }
    }

    override suspend fun relay(
        payload: ByteArray,
        receiverId: String,
        reqId: Long,
    ): Result<Unit> = libnet.request(reqId, receiverId, payload) { _, _, _ -> }.map { }

    private suspend fun open(request: NetRequest) {
        val (senderId, _, reqId, payload) = request

        val envelope = LibnetClientCodec.decode(payload) ?: run {
            log.error { "[open]: malformed packet from $senderId" }
            nack(reqId, "Malformed packet")
            return
        }

        if (!envelope.receiverIdHash.contentEquals(identity.peerIdHash)) {
            log.debug { "[open]: storing relay packet (receiverIdHash mismatch)" }
            onRelayPacket(envelope)
            ack(reqId)
            return
        }

        val (type, plaintext) = open(senderId, envelope).getOrElse {
            val reason = it.message ?: "Unknown error"
            log.error { "[open]: $reason" }
            nack(reqId, reason)
            return
        }

        when (type) {
            TYPE_CHAT -> {
                val message = MessageCodec.decode(plaintext).getOrElse {
                    val reason = "Failed to decode message from $senderId: ${it.message}"
                    log.error { "[open]: $reason" }
                    nack(reqId, reason)
                    return
                }
                onMessageReceived(message).onFailure {
                    val reason = "Failed to deliver message from $senderId: ${it.message}"
                    log.error { "[open]: $reason" }
                    nack(reqId, reason)
                    return
                }.also {
                    ack(reqId)
                }
            }

            TYPE_CONTENT -> {
                val content = ContentCodec.decode(plaintext).getOrElse {
                    val reason = "Failed to decode content from $senderId: ${it.message}"
                    log.error { "[open]: $reason" }
                    nack(reqId, reason)
                    return
                }
                onContentReceived(content).onFailure {
                    val reason = "Failed to deliver content from $senderId: ${it.message}"
                    log.error { "[open]: $reason" }
                    nack(reqId, reason)
                    return
                }.also {
                    ack(reqId)
                }
            }

            else -> {
                val reason = "Unknown message type $type from $senderId"
                log.warn { "[open]: $reason" }
                nack(reqId, reason)
            }
        }
    }

    private suspend fun ack(reqId: Long) {
        libnet.sendResponse(reqId, ByteArray(0))
    }

    private suspend fun nack(reqId: Long, reason: String) {
        libnet.sendResponseFailed(reqId, reason)
    }

    private fun seal(receiver: Contact, type: Byte, plaintext: ByteArray): ByteArray =
        LibnetClientCodec.seal(identity, receiver, type, plaintext)

    private fun open(senderId: String, envelope: StatelessEnvelope): Result<Pair<Byte, ByteArray>> =
        LibnetClientCodec.open(envelope, identity, contactLookup)?.let { Result.success(it) }
            ?: failure("Failed to decrypt message from $senderId")

    private fun receiverContact(peerId: String): Result<Contact> =
        contactLookup(peerId)?.let { Result.success(it) }
            ?: failure("No contact card for $peerId, cannot encrypt")

    companion object {
        const val TYPE_CHAT: Byte = LibnetClientCodec.TYPE_CHAT
        const val TYPE_CONTENT: Byte = LibnetClientCodec.TYPE_CONTENT

        private fun <T> failure(reason: String): Result<T> = Result.failure(IllegalStateException(reason))
    }
}
