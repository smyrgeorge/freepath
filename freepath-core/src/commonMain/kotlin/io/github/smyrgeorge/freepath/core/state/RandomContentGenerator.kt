package io.github.smyrgeorge.freepath.core.state

import io.github.smyrgeorge.freepath.database.ContactEntry
import io.github.smyrgeorge.freepath.model.content.Content
import io.github.smyrgeorge.freepath.model.content.ContentBody
import io.github.smyrgeorge.freepath.model.content.ContentCodec
import io.github.smyrgeorge.freepath.model.content.ContentType
import io.github.smyrgeorge.freepath.model.content.ImageFormat
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

object RandomContentGenerator {
    fun generateSelfContent(
        selfPeerId: String,
        selfSigKeyPrivate: ByteArray,
    ): List<Content> {
        return listOf(
            randomArticleBody(),
            randomImageBody(),
        ).map { body ->
            ContentCodec.seal(
                body = body,
                authorId = selfPeerId,
                sigKeyPrivate = selfSigKeyPrivate,
            )
        }
    }

    fun generateContactContent(
        contacts: List<ContactEntry>,
    ): List<Content> {
        val now = Clock.System.now().toEpochMilliseconds()
        return contacts.flatMap { contact ->
            listOf(
                ContentType.ARTICLE to randomArticleBody(),
                ContentType.IMAGE to randomImageBody(),
            ).mapIndexed { i, (type, body) ->
                val createdAt = now - ((i + 1) * 3_600_000L + Random.nextLong(0, 86_400_000L))
                val contentId = "dev-${contact.peerId.takeLast(6)}-$createdAt"
                Content(
                    id = contentId,
                    type = type,
                    authorId = contact.peerId,
                    createdAt = Instant.fromEpochMilliseconds(createdAt),
                    signature = "dev",
                    body = body,
                )
            }
        }
    }

    private fun randomArticleBody(): ContentBody.Article {
        val titles = listOf(
            "Lorem ipsum dolor sit amet",
            "Consectetur adipiscing elit",
            "Sed do eiusmod tempor",
            "Ut labore et dolore magna",
            "Quis nostrud exercitation",
            "Duis aute irure dolor",
        )
        val bodies = listOf(
            """## Overview

Lorem ipsum dolor sit amet, **consectetur adipiscing elit**, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.

### Key Points

- Duis aute irure dolor in *reprehenderit* in voluptate
- Velit esse cillum dolore eu fugiat nulla pariatur
- Excepteur sint occaecat cupidatat non proident

> Sunt in culpa qui officia deserunt mollit anim id est laborum.""",

            """## Introduction

Sed ut perspiciatis unde omnis iste natus error sit voluptatem **accusantium doloremque** laudantium, totam rem aperiam eaque ipsa quae ab illo inventore veritatis.

### Details

Nemo enim ipsam voluptatem quia voluptas sit aspernatur aut odit aut fugit:

1. Consequuntur magni *dolores eos*
2. Ratione voluptatem sequi nesciunt
3. Neque porro quisquam est

```
val example = "quasi architecto beatae vitae"
```""",

            """## Background

At vero eos et accusamus et iusto odio **dignissimos ducimus** qui blanditiis praesentium voluptatum deleniti atque corrupti quos dolores.

### Analysis

Similique sunt in culpa qui officia deserunt mollitia animi, id est laborum et dolorum fuga.

| Category | Status |
|----------|--------|
| Alpha | *Active* |
| Beta | **Pending** |
| Gamma | Complete |

Et harum quidem rerum facilis est et expedita distinctio.""",

            """## Summary

Nam libero tempore, cum soluta nobis est eligendi optio cumque **nihil impedit** quo minus id quod maxime placeat facere possimus.

> Omnis voluptas assumenda est, omnis dolor repellendus.

### Considerations

Temporibus autem quibusdam et aut officiis debitis rerum necessitatibus saepe eveniet:

- Voluptates *repudiandae* sint
- Molestiae **non recusandae**
- Itaque earum rerum hic tenetur

See [the specification](https://example.com) for more details.""",

            """## Motivation

Quis autem vel eum iure reprehenderit qui in ea voluptate velit esse quam nihil molestiae consequatur.

### Approach

Ut enim ad minima veniam, quis nostrum exercitationem ullam corporis suscipit **laboriosam**, nisi ut aliquid ex ea commodi consequatur.

1. First, identify the *core issue*
2. Then, evaluate possible solutions
3. Finally, implement the **best approach**

---

Vel illum qui dolorem eum fugiat quo voluptas nulla pariatur.""",
        )
        return ContentBody.Article(title = titles.random(), body = bodies.random())
    }

    private fun randomImageBody(): ContentBody.Image {
        val captions = listOf(
            "Lorem ipsum",
            "Dolor sit amet",
            "Consectetur adipiscing",
            "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua, consectetur adipiscing elit amet.",
        )
        val side = 512
        val tile = listOf(16, 32, 64, 128).random()
        val phase = Random.nextInt(2)
        return ContentBody.Image(
            data = Base64.encode(checkerboardPng(side, tile, phase)),
            format = ImageFormat.PNG,
            width = side,
            height = side,
            caption = captions.random(),
        )
    }

    private fun checkerboardPng(side: Int, tile: Int, phase: Int): ByteArray {
        val pixels = ByteArray(side * side)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val light = ((x / tile) + (y / tile) + phase) % 2 == 0
                pixels[y * side + x] = if (light) 0xFF.toByte() else 0x00.toByte()
            }
        }
        return encodeGrayscalePng(side, side, pixels)
    }

    // Minimal pure-Kotlin PNG encoder used for dev-generated content. Stays in commonMain by
    // emitting uncompressed DEFLATE (stored) blocks instead of relying on a zlib implementation.
    private fun encodeGrayscalePng(width: Int, height: Int, pixels: ByteArray): ByteArray {
        require(pixels.size == width * height) { "pixels must be width*height bytes" }

        val ihdr = ByteArray(13)
        writeInt32BE(ihdr, 0, width)
        writeInt32BE(ihdr, 4, height)
        ihdr[8] = 8 // bit depth; color type 0 (grayscale), compression 0, filter 0, interlace 0

        val filtered = ByteArray(height * (1 + width))
        for (y in 0 until height) {
            pixels.copyInto(filtered, y * (1 + width) + 1, y * width, (y + 1) * width)
        }

        return PNG_SIGNATURE +
                pngChunk("IHDR", ihdr) +
                pngChunk("IDAT", zlibStored(filtered)) +
                pngChunk("IEND", ByteArray(0))
    }

    private val PNG_SIGNATURE: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.encodeToByteArray()
        val len = ByteArray(4).also { writeInt32BE(it, 0, data.size) }
        val crc = ByteArray(4).also { writeInt32BE(it, 0, crc32(typeBytes + data)) }
        return len + typeBytes + data + crc
    }

    private fun zlibStored(data: ByteArray): ByteArray {
        val maxBlock = 65535
        val blocks = if (data.isEmpty()) 1 else (data.size + maxBlock - 1) / maxBlock
        val out = ByteArray(2 + blocks * 5 + data.size + 4)
        var idx = 0
        out[idx++] = 0x78.toByte() // CMF: deflate, 32K window
        out[idx++] = 0x01.toByte() // FLG: no preset dict, fastest

        var pos = 0
        do {
            val len = minOf(maxBlock, data.size - pos)
            val isFinal = pos + len >= data.size
            out[idx++] = if (isFinal) 0x01.toByte() else 0x00.toByte()
            writeInt16LE(out, idx, len); idx += 2
            writeInt16LE(out, idx, len.inv() and 0xFFFF); idx += 2
            if (len > 0) data.copyInto(out, idx, pos, pos + len)
            idx += len
            pos += len
        } while (pos < data.size)

        writeInt32BE(out, idx, adler32(data))
        return out
    }

    private fun writeInt32BE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 24 and 0xFF).toByte()
        buf[off + 1] = (v ushr 16 and 0xFF).toByte()
        buf[off + 2] = (v ushr 8 and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    private fun writeInt16LE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = (v ushr 8 and 0xFF).toByte()
    }

    private fun crc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 == 1) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return crc.inv()
    }

    private fun adler32(data: ByteArray): Int {
        var a = 1
        var b = 0
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }
}
