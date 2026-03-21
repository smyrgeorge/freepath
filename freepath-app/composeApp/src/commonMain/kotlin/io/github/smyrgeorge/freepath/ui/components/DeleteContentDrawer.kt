package io.github.smyrgeorge.freepath.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.smyrgeorge.composeapp.generated.resources.Res
import io.github.smyrgeorge.composeapp.generated.resources.dev_delete_content_confirm_body
import io.github.smyrgeorge.composeapp.generated.resources.dev_delete_content_confirm_button
import io.github.smyrgeorge.composeapp.generated.resources.dev_delete_content_confirm_title
import io.github.smyrgeorge.composeapp.generated.resources.dev_reset_cancel
import io.github.smyrgeorge.freepath.AppState
import io.github.smyrgeorge.freepath.AppViewState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun DeleteContentDrawer() {
    val isVisible by AppViewState.showDeleteContentConfirmation.collectAsState()
    val scope = rememberCoroutineScope()

    var drawerHeightPx by remember { mutableFloatStateOf(0f) }
    val offsetAnim = remember { Animatable(2000f) }

    fun offScreenPx() = drawerHeightPx.takeIf { it > 0f } ?: 2000f

    LaunchedEffect(isVisible) {
        if (!isVisible) {
            offsetAnim.animateTo(offScreenPx(), tween(300))
        } else {
            val measuredHeight = snapshotFlow { drawerHeightPx }.first { it > 0f }
            offsetAnim.snapTo(measuredHeight)
            delay(100.milliseconds)
            offsetAnim.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    if (isVisible || offsetAnim.value < offScreenPx()) {
        // Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { AppViewState.hideDeleteContentConfirmation() },
                )
        )

        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { drawerHeightPx = it.height.toFloat() }
                    .offset { IntOffset(0, offsetAnim.value.roundToInt()) }
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp)
                    .padding(bottom = bottomInset + 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(Res.string.dev_delete_content_confirm_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.dev_delete_content_confirm_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FreepathButton(
                        onClick = { AppViewState.hideDeleteContentConfirmation() },
                        modifier = Modifier.weight(1f),
                        variant = ButtonVariant.Outline,
                    ) {
                        Text(
                            text = stringResource(Res.string.dev_reset_cancel),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    FreepathButton(
                        onClick = {
                            scope.launch {
                                AppViewState.hideDeleteContentConfirmation()
                                AppState.deleteAllContent()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        variant = ButtonVariant.Destructive,
                    ) {
                        Text(
                            text = stringResource(Res.string.dev_delete_content_confirm_button),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onError,
                        )
                    }
                }
            }
        }
    }
}
