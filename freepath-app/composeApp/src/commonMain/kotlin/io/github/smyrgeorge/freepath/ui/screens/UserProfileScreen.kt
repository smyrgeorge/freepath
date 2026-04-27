package io.github.smyrgeorge.freepath.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import io.github.smyrgeorge.freepath.database.ContentEntry
import io.github.smyrgeorge.freepath.model.content.ContentBody
import io.github.smyrgeorge.freepath.ui.components.AvatarSize
import io.github.smyrgeorge.freepath.ui.components.FreepathAvatar
import io.github.smyrgeorge.freepath.ui.components.FreepathFingerprint
import io.github.smyrgeorge.freepath.ui.components.FreepathTopBar
import io.github.smyrgeorge.freepath.util.formatRelativeTime
import io.github.smyrgeorge.freepath.util.toImageBitmap
import kotlin.io.encoding.Base64

@Composable
fun UserProfileScreen(
    peerId: String,
    onBack: () -> Unit,
    onPostClick: (ContentEntry) -> Unit,
) {
    val contacts by AppState.contacts.collectAsState()
    val contactContents by AppState.contactContents.collectAsState()
    val profileEntries by AppState.profileEntries.collectAsState()

    LaunchedEffect(peerId) { AppState.loadProfile(peerId) }

    val contact = contacts.firstOrNull { it.peerId == peerId }
    val displayName = contact?.let { c ->
        c.name?.takeIf { it.isNotBlank() }
            ?: c.contact.name?.takeIf { it.isNotBlank() && !it.startsWith("#") }
    } ?: "${peerId.take(4)}·${peerId.takeLast(4)}"
    val avatarLabel = if (contact != null) displayName.first().uppercaseChar().toString() else "?"

    Column(modifier = Modifier.fillMaxSize()) {
        FreepathTopBar(
            title = "Profile",
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                IdentityCard(
                    displayName = displayName,
                    avatarLabel = avatarLabel,
                    avatar = contactContents[peerId]?.avatar,
                    peerId = peerId,
                )
            }

            if (profileEntries.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No posts yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(profileEntries, key = { it.contentId }) { entry ->
                    ProfilePostCard(
                        entry = entry,
                        onClick = { onPostClick(entry) },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun IdentityCard(
    displayName: String,
    avatarLabel: String,
    avatar: String?,
    peerId: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FreepathAvatar(
            label = avatarLabel,
            avatar = avatar,
            size = AvatarSize.Large,
        )
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FreepathFingerprint(text = peerId)
    }
}

@Composable
private fun ProfilePostCard(
    entry: ContentEntry,
    onClick: () -> Unit,
) {
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
        Text(
            text = formatRelativeTime(entry.content.createdAt.toEpochMilliseconds()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val body = entry.content.body) {
            is ContentBody.Image -> ProfileImagePreview(body)
            else -> {
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
}

@Composable
private fun ProfileImagePreview(body: ContentBody.Image) {
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
            Text("🖼", style = MaterialTheme.typography.displaySmall)
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
