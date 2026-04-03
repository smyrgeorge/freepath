package io.github.smyrgeorge.freepath.state

import io.github.smyrgeorge.freepath.content.Content
import io.github.smyrgeorge.freepath.content.ContentBody
import io.github.smyrgeorge.freepath.content.ContentCodec
import io.github.smyrgeorge.freepath.content.ContentType
import io.github.smyrgeorge.freepath.content.ImageFormat
import io.github.smyrgeorge.freepath.database.ContactEntry
import io.github.smyrgeorge.freepath.database.ContentEntry
import io.github.smyrgeorge.freepath.database.ContentTrust
import io.github.smyrgeorge.freepath.util.generateCheckerboardPng
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

object RandomContentGenerator {
    fun generateSelfContent(
        selfPeerId: String,
        selfSigKeyPrivate: ByteArray,
    ): List<ContentEntry> {
        val now = Clock.System.now().toEpochMilliseconds()
        return listOf(
            randomArticleBody(),
            randomImageBody(),
        ).mapIndexed { i, body ->
            val createdAt = now - ((i + 1) * 1_800_000L + Random.nextLong(0, 3_600_000L))
            val envelope = ContentCodec.seal(
                body = body,
                authorId = selfPeerId,
                sigKeyPrivate = selfSigKeyPrivate,
            ).copy(createdAt = Instant.fromEpochMilliseconds(createdAt))
            ContentEntry.from(envelope, trust = ContentTrust.VERIFIED)
        }
    }

    fun generateContactContent(
        contacts: List<ContactEntry>,
    ): List<ContentEntry> {
        val now = Clock.System.now().toEpochMilliseconds()
        return contacts.flatMap { contact ->
            listOf(
                ContentType.ARTICLE to randomArticleBody(),
                ContentType.IMAGE to randomImageBody(),
            ).mapIndexed { i, (type, body) ->
                val createdAt = now - ((i + 1) * 3_600_000L + Random.nextLong(0, 86_400_000L))
                val contentId = "dev-${contact.peerId.takeLast(6)}-$createdAt"
                val envelope = Content(
                    id = contentId,
                    type = type,
                    authorId = contact.peerId,
                    createdAt = Instant.fromEpochMilliseconds(createdAt),
                    signature = "dev",
                    body = body,
                )
                ContentEntry.from(envelope)
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
        return ContentBody.Image(
            data = Base64.encode(generateCheckerboardPng()),
            format = ImageFormat.PNG,
            width = 512,
            height = 512,
            caption = captions.random(),
        )
    }
}
