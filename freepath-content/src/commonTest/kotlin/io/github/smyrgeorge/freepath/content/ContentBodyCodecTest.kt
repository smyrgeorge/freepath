package io.github.smyrgeorge.freepath.content

import io.github.smyrgeorge.freepath.util.codec.ProtobufCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class ContentBodyCodecTest {

    private fun articleBody(text: String = "hello") = ContentBody.Article("Title $text", text)

    // ── ContentBody protobuf round-trip ────────────────────────────────────────

    @Test
    fun contentBody_article_encodeDecode_roundTrip() {
        val body: ContentBody = ContentBody.Article("My Title", "Body text")
        val decoded = ProtobufCodec.protobuf.decodeFromByteArray<ContentBody>(
            ProtobufCodec.protobuf.encodeToByteArray(body)
        )
        assertEquals(body, decoded)
    }

    @Test
    fun contentBody_image_encodeDecode_roundTrip() {
        val body: ContentBody = ContentBody.Image(
            data = "aGVsbG8=",
            format = ImageFormat.PNG,
            width = 64,
            height = 64,
        )
        val decoded = ProtobufCodec.protobuf.decodeFromByteArray<ContentBody>(
            ProtobufCodec.protobuf.encodeToByteArray(body)
        )
        assertEquals(body, decoded)
    }

    @Test
    fun contentBody_contact_encodeDecode_roundTrip() {
        val body: ContentBody = ContentBody.Contact(bio = "Hello")
        val decoded = ProtobufCodec.protobuf.decodeFromByteArray<ContentBody>(
            ProtobufCodec.protobuf.encodeToByteArray(body)
        )
        assertEquals(body, decoded)
    }

    // ── deriveId ──────────────────────────────────────────────────────────────

    @Test
    fun deriveId_isDeterministic() {
        val body = articleBody()
        assertEquals(ContentBodyCodec.deriveId(body), ContentBodyCodec.deriveId(body))
    }

    @Test
    fun deriveId_differsForDifferentBodies() {
        val id1 = ContentBodyCodec.deriveId(articleBody("hello"))
        val id2 = ContentBodyCodec.deriveId(articleBody("world"))
        assertTrue(id1 != id2)
    }

    @Test
    fun deriveId_isBase58() {
        val id = ContentBodyCodec.deriveId(articleBody())
        assertTrue(id.all { it in "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz" })
        assertTrue(id.isNotEmpty())
    }
}
