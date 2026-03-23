package io.github.smyrgeorge.freepath.client

import io.github.smyrgeorge.actor4k.actor.ref.ActorRef
import io.github.smyrgeorge.freepath.Protocol
import io.github.smyrgeorge.freepath.client.codec.AppClientCodec
import io.github.smyrgeorge.freepath.client.model.ChatMessage
import io.github.smyrgeorge.freepath.client.model.failure
import io.github.smyrgeorge.freepath.client.model.success
import io.github.smyrgeorge.freepath.client.model.toResult
import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.content.ContentCodec
import io.github.smyrgeorge.freepath.content.ContentEnvelope
import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent
import io.github.smyrgeorge.freepath.state.AbstractAppResources
import io.github.smyrgeorge.freepath.state.AbstractAppState
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

class AppClient(
    private val system: ActorRef,
    private val state: AbstractAppState,
    private val resources: AbstractAppResources,
) {
    private val log: Logger = Logger.of(this::class)
    private val libp2p = resources.libp2p
    private val libp2pMetrics = resources.libp2p.metrics.value

    init {
        libp2p.scope.launch { libp2p.requests.consumeAsFlow().collect { r -> open(r) } }
    }

    private fun onlineRecieverOf(peerId: String): Result<ContactCard> {
        val peerId = peerId.takeIf { libp2pMetrics.value.identifiedPeers.contains(it) }
            ?: return failure("Peer $peerId not identified yet, message not sent")
        return state.contacts.value
            .firstOrNull { it.nodeId == peerId }?.card
            ?.let { Result.success(it) }
            ?: failure("No contact card for $peerId, cannot encrypt")
    }

    suspend fun send(message: ChatMessage): Result<Unit> {
        val receiver = onlineRecieverOf(message.receiverId).getOrElse { return Result.failure(it) }
        val payload = seal(receiver, TYPE_CHAT, message.encodeToByteArray())
        return libp2p.request(message.receiverId, payload).toResult().map { }
    }

    suspend fun send(envelope: ContentEnvelope, receiverId: String): Result<Unit> {
        val receiver = onlineRecieverOf(receiverId).getOrElse { return Result.failure(it) }
        val payload = seal(receiver, TYPE_CONTENT, ContentCodec.encode(envelope))
        return libp2p.request(receiverId, payload).toResult().map { }
    }

    private suspend fun ack(reqId: Long) {
        libp2p.sendResponse(reqId, ByteArray(0))
    }

    private suspend fun nack(reqId: Long, reason: String) {
        libp2p.sendResponseFailed(reqId, reason)
    }

    private suspend fun open(event: Libp2pEvent.RequestReceived) {
        val (reqId, senderId, recipientId, payload) = event
        val (type, plaintext) = open(senderId, payload).getOrElse {
            val reason = it.message ?: "Unknown error"
            log.error { "[openRequest]: $reason" }
            nack(reqId, reason)
            return@open
        }

        when (type) {
            TYPE_CHAT -> {
                val message = ChatMessage.decodeFromByteArray(plaintext, senderId, recipientId)
                val cmd = Protocol.ChatMessageReceived(senderId, recipientId, message)
                system.tell(cmd).getOrElse {
                    val reason = "Failed to deliver message from $senderId"
                    log.error { "[openRequest]: $reason" }
                    nack(reqId, reason)
                    return@open
                }.also {
                    ack(reqId)
                }
            }

            TYPE_CONTENT -> {
                val envelope = ContentCodec.decode(plaintext).getOrElse {
                    val reason = "Failed to decode content from $senderId: ${it.message}"
                    log.error { "[openRequest]: $reason" }
                    nack(reqId, reason)
                    return@open
                }
                val cmd = Protocol.ContentReceived(envelope)
                system.tell(cmd).getOrElse {
                    val reason = "Failed to deliver content from $senderId"
                    log.error { "[openRequest]: $reason" }
                    nack(reqId, reason)
                    return@open
                }.also {
                    ack(reqId)
                }
            }

            else -> {
                val reason = "Unknown message type $type from $senderId"
                log.warn { "[openRequest]: $reason" }
                nack(reqId, reason)
            }
        }
    }

    private fun seal(receiverCard: ContactCard, type: Byte, plaintext: ByteArray): ByteArray =
        AppClientCodec.seal(state.identity, receiverCard, type, plaintext)

    private fun open(peerId: String, payload: ByteArray): Result<Pair<Byte, ByteArray>> {
        val decrypted = AppClientCodec.open(payload, resources.identity, resources.contactLookup)
            ?: return failure("Failed to decrypt message from $peerId")
        return decrypted.success()
    }

    companion object {
        const val TYPE_CHAT: Byte = 1
        const val TYPE_CONTENT: Byte = 2
    }
}
