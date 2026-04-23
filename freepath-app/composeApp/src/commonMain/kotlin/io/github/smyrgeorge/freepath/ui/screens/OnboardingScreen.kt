package io.github.smyrgeorge.freepath.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.smyrgeorge.composeapp.generated.resources.Res
import io.github.smyrgeorge.composeapp.generated.resources.app_name
import io.github.smyrgeorge.composeapp.generated.resources.bio_hint
import io.github.smyrgeorge.composeapp.generated.resources.bio_label
import io.github.smyrgeorge.composeapp.generated.resources.continue_button
import io.github.smyrgeorge.composeapp.generated.resources.finish_button
import io.github.smyrgeorge.composeapp.generated.resources.identity_description
import io.github.smyrgeorge.composeapp.generated.resources.identity_title
import io.github.smyrgeorge.composeapp.generated.resources.location_hint
import io.github.smyrgeorge.composeapp.generated.resources.location_label
import io.github.smyrgeorge.composeapp.generated.resources.name_hint
import io.github.smyrgeorge.composeapp.generated.resources.name_label
import io.github.smyrgeorge.composeapp.generated.resources.node_id_label
import io.github.smyrgeorge.composeapp.generated.resources.onboarding_random_profile_note
import io.github.smyrgeorge.composeapp.generated.resources.profile_title
import io.github.smyrgeorge.composeapp.generated.resources.security_note
import io.github.smyrgeorge.freepath.AppState
import io.github.smyrgeorge.freepath.core.state.RandomAvatarGenerator
import io.github.smyrgeorge.freepath.core.state.RandomProfileGenerator
import io.github.smyrgeorge.freepath.ui.components.ButtonVariant
import io.github.smyrgeorge.freepath.ui.components.FreepathButton
import io.github.smyrgeorge.freepath.ui.components.FreepathCard
import io.github.smyrgeorge.freepath.ui.components.FreepathFingerprint
import io.github.smyrgeorge.freepath.util.toImageBitmap
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64

@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    onContinue: () -> Unit,
) {
    var showProfile by remember { mutableStateOf(false) }
    val peerId = AppState.identityEntry.peerId
    val profile = remember(peerId) { RandomProfileGenerator.profileFor(peerId) }
    var avatar by remember { mutableStateOf<String?>(null) }
    var avatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshingAvatar by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(profile.name, profile.bio) {
        val b64 = RandomAvatarGenerator.randomAvatar(profile.name)
        avatar = b64
        avatarBitmap = b64?.let { runCatching { Base64.decode(it).toImageBitmap() }.getOrNull() }
    }

    val onRefreshAvatar: () -> Unit = {
        if (!isRefreshingAvatar) {
            scope.launch {
                isRefreshingAvatar = true
                val b64 = RandomAvatarGenerator.randomAvatar(profile.name)
                if (b64 != null) {
                    avatar = b64
                    avatarBitmap = runCatching { Base64.decode(b64).toImageBitmap() }.getOrNull()
                }
                isRefreshingAvatar = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "⬡",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.background,
                )
            }
            Text(
                text = stringResource(Res.string.app_name),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        if (!showProfile) {
            // Step 1: all content centered vertically
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                FreepathCard(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(Res.string.identity_title),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(Res.string.node_id_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            FreepathFingerprint(text = AppState.identityEntry.peerId)
                            Text(
                                text = stringResource(Res.string.identity_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    },
                )

                Spacer(modifier = Modifier.height(24.dp))

                FreepathButton(
                    onClick = { showProfile = true },
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Primary,
                ) {
                    Text(
                        text = stringResource(Res.string.continue_button),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(Res.string.security_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // Step 2: avatar + profile card, scrollable
            val avatarLabel = remember(profile.name) {
                profile.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            }
            val onSurface = MaterialTheme.colorScheme.onSurface

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Avatar with refresh button
                Box(modifier = Modifier.size(80.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                            .border(2.dp, onSurface, CircleShape)
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (avatarBitmap != null) {
                            Image(
                                painter = BitmapPainter(avatarBitmap!!),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                text = avatarLabel,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = !isRefreshingAvatar,
                                onClick = onRefreshAvatar,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "↺",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                FreepathCard(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = stringResource(Res.string.profile_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            OutlinedTextField(
                                value = profile.name,
                                onValueChange = {},
                                label = { Text(stringResource(Res.string.name_label)) },
                                placeholder = { Text(stringResource(Res.string.name_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                readOnly = true,
                            )
                            OutlinedTextField(
                                value = profile.bio,
                                onValueChange = {},
                                label = { Text(stringResource(Res.string.bio_label)) },
                                placeholder = { Text(stringResource(Res.string.bio_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 4,
                                readOnly = true,
                            )
                            OutlinedTextField(
                                value = profile.location,
                                onValueChange = {},
                                label = { Text(stringResource(Res.string.location_label)) },
                                placeholder = { Text(stringResource(Res.string.location_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                readOnly = true,
                            )
                            Text(
                                text = stringResource(Res.string.onboarding_random_profile_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                )

                Spacer(modifier = Modifier.height(24.dp))

                FreepathButton(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            AppState.completeOnboarding(profile.name, profile.bio, profile.location, avatar)
                            onContinue()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Primary,
                    enabled = !isLoading,
                ) {
                    Text(
                        text = stringResource(Res.string.finish_button),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(Res.string.security_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
