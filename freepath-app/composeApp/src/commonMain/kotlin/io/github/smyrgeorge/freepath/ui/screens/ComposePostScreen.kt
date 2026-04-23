package io.github.smyrgeorge.freepath.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.scale
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.smyrgeorge.freepath.AppResources
import io.github.smyrgeorge.freepath.AppState
import io.github.smyrgeorge.freepath.actor.AppProtocol
import io.github.smyrgeorge.freepath.content.Author
import io.github.smyrgeorge.freepath.content.ContentBody
import io.github.smyrgeorge.freepath.content.ContentType
import io.github.smyrgeorge.freepath.ui.components.AvatarSize
import io.github.smyrgeorge.freepath.ui.components.ButtonSize
import io.github.smyrgeorge.freepath.ui.components.ButtonVariant
import io.github.smyrgeorge.freepath.ui.components.FreepathAvatar
import io.github.smyrgeorge.freepath.ui.components.FreepathButton
import io.github.smyrgeorge.freepath.ui.components.FreepathTopBar
import kotlinx.coroutines.launch

/** Content types available in the compose editor (Contact is excluded). */
private val COMPOSE_TYPES = listOf(ContentType.ARTICLE, ContentType.IMAGE)

@Composable
fun ComposePostScreen(
    onBack: () -> Unit,
    onPublished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedType by remember { mutableStateOf(ContentType.ARTICLE) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var includeAuthor by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }

    val peerId = remember { AppState.identityEntry.peerId }
    val displayName = remember {
        AppState.contactEntry.let { c ->
            c.contact.name?.takeIf { it.isNotBlank() && !it.startsWith("#") }
        } ?: "${peerId.take(4)}·${peerId.takeLast(4)}"
    }
    val avatarLabel = remember { displayName.first().uppercaseChar().toString() }
    val selfAvatar = remember {
        runCatching { AppState.contactContentBody.avatar }.getOrNull()
    }

    val canPublish = when (selectedType) {
        ContentType.ARTICLE -> title.isNotBlank() && body.isNotBlank()
        else -> false // IMAGE not yet implemented
    }

    fun publish() {
        if (!canPublish || publishing) return
        publishing = true
        val author = if (includeAuthor) {
            val contact = AppState.contact
            val contactContent = runCatching { AppState.contactContentBody }.getOrNull()
            Author(
                name = contact.name?.takeIf { it.isNotBlank() && !it.startsWith("#") },
                bio = contactContent?.bio,
                avatar = contactContent?.avatar,
                location = contactContent?.location,
            )
        } else null
        val contentBody = when (selectedType) {
            ContentType.ARTICLE -> ContentBody.Article(
                title = title.trim().take(ContentBody.Article.MAX_TITLE_LENGTH),
                body = body.trim(),
                author = author,
            )

            else -> return // IMAGE not yet implemented
        }
        scope.launch {
            AppResources.system.tell(AppProtocol.PublishContent(contentBody))
            onPublished()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // ── Top bar: title left, Cancel right ────────────────────────────
        FreepathTopBar(
            title = "New Post",
            rightAction = {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        // ── Scrollable content ───────────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ── Author identity ──────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FreepathAvatar(
                    label = avatarLabel,
                    size = AvatarSize.Medium,
                    avatar = selfAvatar,
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

            // ── Editor area (depends on selected type) ───────────────────
            when (selectedType) {
                ContentType.ARTICLE -> ArticleEditor(
                    title = title,
                    onTitleChange = { title = it },
                    body = body,
                    onBodyChange = { body = it },
                )

                ContentType.IMAGE -> {
                    // Placeholder — image uploader not yet implemented
                    Text(
                        text = "Image upload coming soon",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> Unit
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // ── Attach profile toggle (shared across all content types) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Attach my profile",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                    checked = includeAuthor,
                    onCheckedChange = { includeAuthor = it },
                    modifier = Modifier.scale(0.75f),
                )
            }
            Text(
                text = "Your name, bio, avatar, and location will be embedded in this post so recipients can see who wrote it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── Bottom bar: type selector + Publish ──────────────────────────
        HorizontalDivider()
        BottomBar(
            types = COMPOSE_TYPES,
            selectedType = selectedType,
            onSelectType = { selectedType = it },
            canPublish = canPublish,
            publishing = publishing,
            onPublish = ::publish,
        )
    }
}

// ── Article editor ───────────────────────────────────────────────────────────

@Composable
private fun ArticleEditor(
    title: String,
    onTitleChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // Title field
    BasicTextField(
        value = title,
        onValueChange = { if (it.length <= ContentBody.Article.MAX_TITLE_LENGTH) onTitleChange(it) },
        textStyle = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            color = onSurface,
        ),
        cursorBrush = SolidColor(onSurface),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            if (title.isEmpty()) {
                Text(
                    text = "Title (required)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            inner()
        },
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Body field (markdown)
    BasicTextField(
        value = body,
        onValueChange = onBodyChange,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = onSurface),
        cursorBrush = SolidColor(onSurface),
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
        decorationBox = { inner ->
            if (body.isEmpty()) {
                Text(
                    text = "Write your article here… (required)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            inner()
        },
    )
}

// ── Bottom bar: content type chips + Publish button ──────────────────────────

@Composable
private fun BottomBar(
    types: List<ContentType>,
    selectedType: ContentType,
    onSelectType: (ContentType) -> Unit,
    canPublish: Boolean,
    publishing: Boolean,
    onPublish: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Content type chips
        types.forEach { type ->
            val isSelected = type == selectedType
            val isEnabled = type == ContentType.ARTICLE // Only article is implemented
            val label = when (type) {
                ContentType.ARTICLE -> "Article"
                ContentType.IMAGE -> "Image"
                ContentType.CONTACT -> "Contact"
            }

            val bg = when {
                isSelected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
            val fg = when {
                !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                isSelected -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .then(
                        if (isEnabled) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),
                                onClick = { onSelectType(type) },
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Publish button
        FreepathButton(
            onClick = onPublish,
            enabled = canPublish && !publishing,
            size = ButtonSize.Small,
            variant = ButtonVariant.Primary,
        ) {
            Text(
                text = if (publishing) "…" else "Publish",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
