package io.github.smyrgeorge.freepath.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.smyrgeorge.freepath.AppState
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.content.ContentBody
import io.github.smyrgeorge.freepath.database.ContactEntry
import io.github.smyrgeorge.freepath.database.ContentEntry
import io.github.smyrgeorge.freepath.ui.components.AvatarSize
import io.github.smyrgeorge.freepath.ui.components.FreepathAvatar
import io.github.smyrgeorge.freepath.ui.components.FreepathTopBar
import io.github.smyrgeorge.freepath.util.formatRelativeTime
import io.github.smyrgeorge.freepath.util.toImageBitmap
import kotlin.io.encoding.Base64

// ── Pure helpers (testable without Compose) ───────────────────────────────────

fun trustLabel(contacts: List<ContactEntry>, authorId: String): String =
    when (contacts.firstOrNull { it.peerId == authorId }?.trustLevel) {
        TrustLevel.TRUSTED -> "Trusted"
        TrustLevel.KNOWN -> "Known"
        TrustLevel.BLOCKED -> "Blocked"
        null -> "Stranger"
    }

private const val BODY_SNIPPET_MAX = 120

/** Regex that matches common Markdown syntax tokens. */
private val markdownSyntax = Regex(
    """(\*{1,3}|_{1,3}|~{2}|`{1,3}|#{1,6}\s|>\s?|!\[|]\([^)]*\)|\[|\]|^-{3,}$|^\|.*\|$|^[-|:\s]+$)""",
    RegexOption.MULTILINE,
)

/** Strip Markdown formatting, take the first 3 non-blank lines, and join them into a single snippet. */
private fun stripMarkdownPreview(raw: String): String {
    val plain = markdownSyntax.replace(raw, "")
    val lines = plain.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(3)
        .joinToString(" ")
    return if (lines.length > BODY_SNIPPET_MAX) "${lines.take(BODY_SNIPPET_MAX)}…" else lines
}

fun ContentBody.bodySnippet(): String? = when (this) {
    is ContentBody.Article -> stripMarkdownPreview(body)
    is ContentBody.Image -> caption
    is ContentBody.Contact -> null
}

fun ContentBody.previewText(): String = when (this) {
    is ContentBody.Article -> title
    is ContentBody.Image -> "Image"
    is ContentBody.Contact -> "Contact"
}

fun ContentBody.fullText(): String = when (this) {
    is ContentBody.Article -> "$title\n\n$body"
    is ContentBody.Image -> caption ?: "(Image)"
    is ContentBody.Contact -> bio ?: ""
}

// ── Composables ───────────────────────────────────────────────────────────────

@Composable
fun FeedScreen(
    modifier: Modifier = Modifier,
    onPostClick: (ContentEntry) -> Unit,
) {
    val feedEntries by AppState.feedEntries.collectAsState()
    val contacts by AppState.contacts.collectAsState()
    val contactContents by AppState.contactContents.collectAsState()

    LaunchedEffect(Unit) { AppState.loadFeed() }

    Column(modifier = modifier.fillMaxSize()) {
        FreepathTopBar(title = "Freepath") {
            // Sync strip (inside FreepathTopBar's content lambda — rendered below the divider)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Last sync: — · — devices",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (feedEntries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No posts yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(feedEntries, key = { it.contentId }) { entry ->
                    FeedPostCard(
                        entry = entry,
                        contacts = contacts,
                        contactContents = contactContents,
                        onClick = { onPostClick(entry) },
                    )
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun FeedPostCard(
    entry: ContentEntry,
    contacts: List<ContactEntry>,
    contactContents: Map<String, ContentBody.Contact>,
    onClick: () -> Unit,
) {
    val contact = contacts.firstOrNull { it.peerId == entry.authorId }
    val displayName = contact?.let { c ->
        c.name?.takeIf { it.isNotBlank() }
            ?: c.contact.name?.takeIf { it.isNotBlank() && !it.startsWith("#") }
    } ?: "${entry.authorId.take(4)}·${entry.authorId.takeLast(4)}"
    val avatarLabel = if (contact != null) displayName.first().uppercaseChar().toString() else "?"
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Header: avatar + name/hops
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FreepathAvatar(
                label = avatarLabel,
                avatar = contactContents[contact?.peerId]?.avatar,
                size = AvatarSize.Small,
            )
            Column {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                )
                Text(
                    text = formatRelativeTime(entry.content.createdAt.toEpochMilliseconds()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Content preview
        val body = entry.content.body
        if (body is ContentBody.Image) {
            FeedImagePreview(body)
        } else {
            Text(
                text = body.previewText(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val snippet = body.bodySnippet()
            if (snippet != null) {
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

    }
}

@Composable
private fun FeedImagePreview(body: ContentBody.Image) {
    val imageBitmap = remember(body.data) {
        runCatching { Base64.decode(body.data).toImageBitmap() }.getOrNull()
    }
    if (imageBitmap != null) {
        Image(
            painter = BitmapPainter(imageBitmap),
            contentDescription = body.caption,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "🖼",
                style = MaterialTheme.typography.displaySmall,
            )
        }
    }
    val caption = body.caption
    if (caption != null) {
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
