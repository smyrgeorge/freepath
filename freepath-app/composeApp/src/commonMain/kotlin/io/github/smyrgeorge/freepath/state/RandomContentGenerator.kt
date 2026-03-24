package io.github.smyrgeorge.freepath.state

import io.github.smyrgeorge.freepath.content.ContentBody
import io.github.smyrgeorge.freepath.content.Content
import io.github.smyrgeorge.freepath.content.ContentType
import io.github.smyrgeorge.freepath.content.ImageFormat
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import io.github.smyrgeorge.freepath.database.ContentEntry
import io.github.smyrgeorge.freepath.util.generateCheckerboardPng
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

object RandomContentGenerator {
    fun generateRandomContent(contacts: List<ContactCardEntry>): List<ContentEntry> {
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
                ContentEntry(
                    contentId = contentId,
                    type = type,
                    authorId = contact.peerId,
                    version = 1,
                    content = envelope,
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
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.\n\nDuis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.",
            "Sed ut perspiciatis unde omnis iste natus error sit voluptatem accusantium doloremque laudantium, totam rem aperiam eaque ipsa quae ab illo inventore veritatis et quasi architecto beatae vitae dicta sunt.\n\nNemo enim ipsam voluptatem quia voluptas sit aspernatur aut odit aut fugit, sed quia consequuntur magni dolores eos qui ratione voluptatem sequi nesciunt.",
            "At vero eos et accusamus et iusto odio dignissimos ducimus qui blanditiis praesentium voluptatum deleniti atque corrupti quos dolores et quas molestias excepturi sint occaecati cupiditate non provident.\n\nSimilique sunt in culpa qui officia deserunt mollitia animi, id est laborum et dolorum fuga. Et harum quidem rerum facilis est et expedita distinctio.",
            "Nam libero tempore, cum soluta nobis est eligendi optio cumque nihil impedit quo minus id quod maxime placeat facere possimus, omnis voluptas assumenda est, omnis dolor repellendus.\n\nTemporibus autem quibusdam et aut officiis debitis rerum necessitatibus saepe eveniet ut et voluptates repudiandae sint et molestiae non recusandae.",
            "Quis autem vel eum iure reprehenderit qui in ea voluptate velit esse quam nihil molestiae consequatur, vel illum qui dolorem eum fugiat quo voluptas nulla pariatur.\n\nUt enim ad minima veniam, quis nostrum exercitationem ullam corporis suscipit laboriosam, nisi ut aliquid ex ea commodi consequatur.",
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
