package io.github.smyrgeorge.freepath.content

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Author(
    @ProtoNumber(1) val name: String? = null,     // Max 128 chars
    @ProtoNumber(2) val bio: String? = null,      // Max 256 chars
    @ProtoNumber(3) val avatar: String? = null,   // Base64-encoded image, max 64 KB
    @ProtoNumber(4) val location: String? = null,  // Max 128 chars
) {
    init {
        require(name == null || name.length <= ContentBody.Contact.MAX_LOCATION_LENGTH) { "name exceeds maximum length of ${ContentBody.Contact.MAX_LOCATION_LENGTH} characters" }
        require(bio == null || bio.length <= ContentBody.Contact.MAX_BIO_LENGTH) { "bio exceeds maximum length of ${ContentBody.Contact.MAX_BIO_LENGTH} characters" }
        require(avatar == null || avatar.length <= ContentBody.Contact.MAX_AVATAR_SIZE) { "avatar exceeds maximum size of ${ContentBody.Contact.MAX_AVATAR_SIZE} characters" }
        require(location == null || location.length <= ContentBody.Contact.MAX_LOCATION_LENGTH) { "location exceeds maximum length of ${ContentBody.Contact.MAX_LOCATION_LENGTH} characters" }
    }
}
