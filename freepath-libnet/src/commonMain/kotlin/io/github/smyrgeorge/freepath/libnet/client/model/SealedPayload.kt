package io.github.smyrgeorge.freepath.libnet.client.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * The plaintext structure encrypted inside a [StatelessEnvelope].
 *
 * Moving [senderId], [type], and [signature] inside the ciphertext means relay nodes
 * learn nothing about the sender, the message category, or the authentication proof —
 * all three are only visible to the intended recipient after decryption.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
class SealedPayload(
    /** Sender's peerId (Base58 multihash). Used by the recipient to look up the contact for signature verification. */
    @ProtoNumber(1) val senderId: String,
    /** Application-level message type (e.g. TYPE_CHAT, TYPE_CONTENT). Hidden from relay nodes. */
    @ProtoNumber(2) val type: Int,
    /** Ed25519 signature over AAD ∥ [payload]. Sealed inside so only the recipient can hold it as proof. */
    @ProtoNumber(3) val signature: ByteArray,
    /** Original application payload bytes. */
    @ProtoNumber(4) val payload: ByteArray,
)
