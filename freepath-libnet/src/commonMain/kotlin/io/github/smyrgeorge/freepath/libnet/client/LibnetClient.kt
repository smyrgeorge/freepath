package io.github.smyrgeorge.freepath.libnet.client

import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.content.Content
import io.github.smyrgeorge.freepath.content.ContentCodec
import io.github.smyrgeorge.freepath.libnet.LibnetModule
import io.github.smyrgeorge.freepath.libnet.NetRequest
import io.github.smyrgeorge.freepath.libnet.client.codec.LibnetClientCodec
import io.github.smyrgeorge.freepath.libnet.client.model.ChatMessage
import io.github.smyrgeorge.freepath.libnet.client.model.failure
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

class LibnetClient(
    private val identity: Identity,
    private val libnet: LibnetModule,
    private val contactLookup: (peerId: String) -> Contact?,
    private val onChatMessageReceived: suspend (ChatMessage) -> Result<Unit>,
    private val onContentReceived: suspend (Content) -> Result<Unit>,
) {
    private val log: Logger = Logger.of(this::class)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch { libnet.requests.consumeAsFlow().collect { r -> open(r) } }
    }

    fun stop() {
        scope.cancel()
    }

    suspend fun send(message: ChatMessage): Result<Unit> {
        val receiver = receiverContact(message.receiverId).getOrElse { return Result.failure(it) }
        val payload = seal(receiver, TYPE_CHAT, message.encodeToByteArray())
        return libnet.request(message.receiverId, payload).map { }
    }

    suspend fun send(content: Content, receiverId: String): Result<Unit> {
        val receiver = receiverContact(receiverId).getOrElse { return Result.failure(it) }
        val payload = seal(receiver, TYPE_CONTENT, ContentCodec.encode(content))
        return libnet.request(receiverId, payload).map { }
    }

    private fun receiverContact(peerId: String): Result<Contact> =
        contactLookup(peerId)?.let { Result.success(it) }
            ?: failure("No contact card for $peerId, cannot encrypt")

    private suspend fun open(request: NetRequest) {
        val (senderId, recipientId, reqId, payload) = request
        val (type, plaintext) = decrypt(senderId, payload).getOrElse {
            val reason = it.message ?: "Unknown error"
            log.error { "[open]: $reason" }
            nack(reqId, reason)
            return
        }

        when (type) {
            TYPE_CHAT -> {
                val message = ChatMessage.decodeFromByteArray(plaintext, senderId, recipientId)
                onChatMessageReceived(message).onFailure {
                    val reason = "Failed to deliver chat message from $senderId: ${it.message}"
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

    private fun decrypt(senderId: String, payload: ByteArray): Result<Pair<Byte, ByteArray>> =
        LibnetClientCodec.open(payload, identity, contactLookup)?.let { Result.success(it) }
            ?: failure("Failed to decrypt message from $senderId")

    companion object {
        const val TYPE_CHAT: Byte = 1
        const val TYPE_CONTENT: Byte = 2
    }
}
