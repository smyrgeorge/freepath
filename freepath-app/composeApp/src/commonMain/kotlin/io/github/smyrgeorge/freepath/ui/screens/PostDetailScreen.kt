package io.github.smyrgeorge.freepath.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import io.github.smyrgeorge.freepath.AppState
import io.github.smyrgeorge.freepath.content.ContentBody
import io.github.smyrgeorge.freepath.database.ContentEntry
import io.github.smyrgeorge.freepath.ui.components.AvatarSize
import io.github.smyrgeorge.freepath.ui.components.FreepathAvatar
import io.github.smyrgeorge.freepath.ui.components.FreepathTopBar
import io.github.smyrgeorge.freepath.util.formatRelativeTime
import io.github.smyrgeorge.freepath.util.toImageBitmap
import kotlin.io.encoding.Base64

@Composable
fun PostDetailScreen(
    entry: ContentEntry,
    onBack: () -> Unit,
) {
    val contacts by AppState.contacts.collectAsState()
    val contactContents by AppState.contactContents.collectAsState()
    val contact = contacts.firstOrNull { it.peerId == entry.authorId }
    val displayName = contact?.let { c ->
        c.name?.takeIf { it.isNotBlank() }
            ?: c.contact.name?.takeIf { it.isNotBlank() && !it.startsWith("#") }
    } ?: "${entry.authorId.take(4)}·${entry.authorId.takeLast(4)}"
    val avatarLabel = if (contact != null) displayName.first().uppercaseChar().toString() else "?"

    Column(modifier = Modifier.fillMaxSize()) {
        FreepathTopBar(
            title = "Post",
            leftAction = {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(),
                            onClick = onBack,
                        )
                        .padding(8.dp),
                )
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Author row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FreepathAvatar(
                    label = avatarLabel,
                    size = AvatarSize.Medium,
                    avatar = contactContents[contact?.peerId]?.avatar,
                )
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Content body
            when (val body = entry.content.body) {
                is ContentBody.Image -> DetailImage(body)
                is ContentBody.Article -> {
                    Text(
                        text = body.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Markdown(
                        content = body.body,
                        colors = markdownColor(
                            text = MaterialTheme.colorScheme.onSurface,
                        ),
                        typography = markdownTypography(
                            paragraph = MaterialTheme.typography.bodyMedium,
                        ),
                    )
                }

                else -> {
                    Text(
                        text = body.fullText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Provenance
            Text(
                text = "Provenance",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Created ${formatRelativeTime(entry.content.createdAt.toEpochMilliseconds())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Received from $displayName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun DetailImage(body: ContentBody.Image) {
    val imageBitmap = remember(body.data) {
        runCatching { Base64.decode(body.data).toImageBitmap() }.getOrNull()
    }
    if (imageBitmap != null) {
        Image(
            painter = BitmapPainter(imageBitmap),
            contentDescription = body.caption,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("🖼", style = MaterialTheme.typography.displaySmall)
        }
    }
    val caption = body.caption
    if (caption != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
