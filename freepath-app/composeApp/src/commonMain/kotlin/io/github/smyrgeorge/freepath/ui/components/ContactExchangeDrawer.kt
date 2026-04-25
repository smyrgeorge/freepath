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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.smyrgeorge.freepath.AppResources
import io.github.smyrgeorge.freepath.AppViewState
import io.github.smyrgeorge.freepath.core.actor.ContactExchangeProtocol
import io.github.smyrgeorge.freepath.core.state.model.ExchangeDrawerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Full-screen overlay for the contact exchange flow (LAN and BLE) (both requestor and recipient sides).
 * Rendered at the App level so it appears above the tab bar.
 */
@Composable
fun ContactExchangeDrawer() {
    val drawerState by AppViewState.exchangeDrawer.collectAsState()
    val scope = rememberCoroutineScope()

    val isVisible = drawerState !is ExchangeDrawerState.Hidden

    var currentState by remember { mutableStateOf<ExchangeDrawerState>(ExchangeDrawerState.Hidden) }
    var drawerHeightPx by remember { mutableFloatStateOf(0f) }
    val offsetAnim = remember { Animatable(2000f) }

    fun offScreenPx() = drawerHeightPx.takeIf { it > 0f } ?: 2000f

    LaunchedEffect(drawerState) {
        when (drawerState) {
            is ExchangeDrawerState.Hidden -> {
                // Animate out
                offsetAnim.animateTo(offScreenPx(), tween(300))
                currentState = ExchangeDrawerState.Hidden
            }

            else -> {
                // Update visible state, then animate in
                currentState = drawerState
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
    }

    // Keep currentState alive during exit animation
    val activeState = if (isVisible) drawerState else currentState

    if (activeState !is ExchangeDrawerState.Hidden) {
        // Scrim — not tappable for ResponderWaiting (prevents accidental dismiss while exchange runs)
        val isWaitingState = activeState is ExchangeDrawerState.ResponderWaiting
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .then(
                    if (!isWaitingState) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { scope.launch { AppResources.contactExchange.tell(ContactExchangeProtocol.Cancelled) } },
                        )
                    } else {
                        Modifier
                    }
                )
        )

        Box(modifier = Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
            when (activeState) {
                is ExchangeDrawerState.RequestorEnterPin -> {
                    RequestorEnterPinDrawer(
                        offsetProvider = { offsetAnim.value.roundToInt() },
                        onHeightMeasured = { drawerHeightPx = it },
                        onSubmit = { pin ->
                            scope.launch {
                                AppResources.contactExchange.tell(
                                    ContactExchangeProtocol.BeginInitiator(
                                        activeState.peripheralId,
                                        pin
                                    )
                                )
                            }
                        },
                        onCancel = { scope.launch { AppResources.contactExchange.tell(ContactExchangeProtocol.Cancelled) } },
                    )
                }

                is ExchangeDrawerState.ResponderWaiting -> {
                    ResponderWaitingDrawer(
                        state = activeState,
                        offsetProvider = { offsetAnim.value.roundToInt() },
                        onHeightMeasured = { drawerHeightPx = it },
                        onCancel = { scope.launch { AppResources.contactExchange.tell(ContactExchangeProtocol.Cancelled) } },
                    )
                }

                is ExchangeDrawerState.Failed -> {
                    FailedDrawer(
                        state = activeState,
                        offsetProvider = { offsetAnim.value.roundToInt() },
                        onHeightMeasured = { drawerHeightPx = it },
                        onDismiss = { scope.launch { AppResources.contactExchange.tell(ContactExchangeProtocol.Cancelled) } },
                    )
                }

                else -> {}
            }
        }
    }
}

@Composable
private fun DrawerShell(
    offsetProvider: () -> Int,
    onHeightMeasured: (Float) -> Unit,
    content: @Composable () -> Unit,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightMeasured(it.height.toFloat()) }
            .offset { IntOffset(0, offsetProvider()) }
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
        // Drag handle (decorative only)
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
        Spacer(modifier = Modifier.height(20.dp))
        content()
    }
}

@Composable
private fun RequestorEnterPinDrawer(
    offsetProvider: () -> Int,
    onHeightMeasured: (Float) -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var enteredPin by remember { mutableStateOf("") }

    DrawerShell(offsetProvider = offsetProvider, onHeightMeasured = onHeightMeasured) {
        Text(
            text = "Add via Bluetooth",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Enter the 4-digit PIN shown on the other device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = enteredPin,
            onValueChange = { new -> enteredPin = new.filter { it.isDigit() }.take(4) },
            placeholder = {
                Text(
                    text = "4-digit PIN",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (enteredPin.length == 4) onSubmit(enteredPin) },
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                textAlign = TextAlign.Center,
                letterSpacing = 8.sp,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FreepathButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                variant = ButtonVariant.Outline,
            ) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            FreepathButton(
                onClick = { onSubmit(enteredPin) },
                modifier = Modifier.weight(1f),
                variant = ButtonVariant.Primary,
                enabled = enteredPin.length == 4,
            ) {
                Text(
                    text = "Connect",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun ResponderWaitingDrawer(
    state: ExchangeDrawerState.ResponderWaiting,
    offsetProvider: () -> Int,
    onHeightMeasured: (Float) -> Unit,
    onCancel: () -> Unit,
) {
    DrawerShell(offsetProvider = offsetProvider, onHeightMeasured = onHeightMeasured) {
        Text(
            text = "Waiting for peer",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "The other device should now tap \"Add\" to connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.pin.forEach { digit ->
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = digit.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Waiting for initiator to connect…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        FreepathButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            variant = ButtonVariant.Outline,
        ) {
            Text(
                text = "Cancel",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun FailedDrawer(
    state: ExchangeDrawerState.Failed,
    offsetProvider: () -> Int,
    onHeightMeasured: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    DrawerShell(offsetProvider = offsetProvider, onHeightMeasured = onHeightMeasured) {
        Text(
            text = "Exchange failed",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(24.dp))
        FreepathButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            variant = ButtonVariant.Outline,
        ) {
            Text(
                text = "Dismiss",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
