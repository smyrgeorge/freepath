package io.github.smyrgeorge.freepath.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.smyrgeorge.composeapp.generated.resources.Res
import io.github.smyrgeorge.composeapp.generated.resources.network_accept
import io.github.smyrgeorge.composeapp.generated.resources.network_add_contact_desc
import io.github.smyrgeorge.composeapp.generated.resources.network_add_contact_title
import io.github.smyrgeorge.composeapp.generated.resources.network_add_qr_hint
import io.github.smyrgeorge.composeapp.generated.resources.network_add_qr_title
import io.github.smyrgeorge.composeapp.generated.resources.network_empty_hint
import io.github.smyrgeorge.composeapp.generated.resources.network_invalid_link
import io.github.smyrgeorge.composeapp.generated.resources.network_paste_link
import io.github.smyrgeorge.composeapp.generated.resources.network_paste_link_hint
import io.github.smyrgeorge.composeapp.generated.resources.network_reject
import io.github.smyrgeorge.composeapp.generated.resources.network_section_blocked
import io.github.smyrgeorge.composeapp.generated.resources.network_section_known
import io.github.smyrgeorge.composeapp.generated.resources.network_section_trusted
import io.github.smyrgeorge.composeapp.generated.resources.network_title
import io.github.smyrgeorge.composeapp.generated.resources.network_trust
import io.github.smyrgeorge.composeapp.generated.resources.node_id_label
import io.github.smyrgeorge.freepath.AppResources
import io.github.smyrgeorge.freepath.AppState
import io.github.smyrgeorge.freepath.AppViewState
import io.github.smyrgeorge.freepath.Protocol
import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.contact.exchange.QrCodeContactExchange
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import io.github.smyrgeorge.freepath.ui.components.ButtonVariant
import io.github.smyrgeorge.freepath.ui.components.FreepathButton
import io.github.smyrgeorge.freepath.ui.components.FreepathFingerprint
import io.github.smyrgeorge.freepath.ui.components.FreepathTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Composable
fun NetworkScreen(modifier: Modifier = Modifier, onContactClick: ((ContactCardEntry) -> Unit)? = null) {
    val contacts by AppState.contacts.collectAsState()

    val trusted = contacts.filter { it.trustLevel == TrustLevel.TRUSTED }
    val known = contacts.filter { it.trustLevel == TrustLevel.KNOWN }
    val blocked = contacts.filter { it.trustLevel == TrustLevel.BLOCKED }

    Column(modifier = modifier.fillMaxSize()) {
        FreepathTopBar(
            title = stringResource(Res.string.network_title),
            rightAction = { AddButton() },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { AddByQrCard() }

            if (contacts.isEmpty()) {
                item {
                    Text(
                        text = stringResource(Res.string.network_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }
            }

            if (trusted.isNotEmpty()) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                item {
                    NetworkSectionHeader(
                        "${stringResource(Res.string.network_section_trusted)} (${trusted.size})"
                    )
                }
                items(trusted, key = { it.nodeId }) { entry -> ContactRow(entry, onContactClick = onContactClick) }
            }

            if (known.isNotEmpty()) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                item {
                    NetworkSectionHeader(
                        "${stringResource(Res.string.network_section_known)} (${known.size})"
                    )
                }
                items(known, key = { it.nodeId }) { entry -> ContactRow(entry, onContactClick = onContactClick) }
            }

            if (blocked.isNotEmpty()) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                item {
                    NetworkSectionHeader(
                        "${stringResource(Res.string.network_section_blocked)} (${blocked.size})"
                    )
                }
                items(blocked, key = { it.nodeId }) { entry -> ContactRow(entry, grayed = true) }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun AddByQrCard() {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = {},
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .border(1.5.dp, onSurface.copy(alpha = 0.25f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "QR",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.network_add_qr_title),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.network_add_qr_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NetworkSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun ContactRow(
    entry: ContactCardEntry,
    grayed: Boolean = false,
    onContactClick: ((ContactCardEntry) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val contentAlpha = if (grayed) 0.5f else 1f

    val localName = entry.name?.takeIf { it.isNotBlank() }
    val cardName = entry.card.name?.takeIf { it.isNotBlank() && !it.startsWith("#") }
    val displayName = localName ?: cardName ?: entry.nodeId.take(12)
    val avatarLabel = displayName.first().uppercaseChar().toString()

    val avatarBg = if (entry.trustLevel == TrustLevel.TRUSTED)
        MaterialTheme.colorScheme.secondaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .then(
                if (onContactClick != null && !grayed)
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        onClick = { onContactClick(entry) },
                    )
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(avatarBg.copy(alpha = contentAlpha), CircleShape)
                .border(2.dp, onSurface.copy(alpha = contentAlpha), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (grayed) "✕" else avatarLabel,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = contentAlpha),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = onSurface.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (entry.trustLevel) {
                    TrustLevel.BLOCKED -> stringResource(Res.string.network_section_blocked)
                    else -> "Added ${contactAge(entry.createdAt)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
        }

        when (entry.trustLevel) {
            TrustLevel.TRUSTED -> {
                Box(
                    modifier = Modifier
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(50.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.network_section_trusted),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TrustLevel.KNOWN -> {
                FreepathButton(
                    onClick = {
                        scope.launch { AppResources.system.tell(Protocol.SetTrustLevel(entry, TrustLevel.TRUSTED)) }
                    },
                    modifier = Modifier.width(72.dp),
                    variant = ButtonVariant.Outline,
                ) {
                    Text(
                        text = stringResource(Res.string.network_trust),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            TrustLevel.BLOCKED -> Unit
        }
    }
}

private fun contactAge(createdAt: Instant): String {
    val age = Clock.System.now() - createdAt
    val days = age.inWholeDays
    val hours = age.inWholeHours
    return when {
        days >= 14 -> "${days / 7}w ago"
        days >= 1 -> "${days}d ago"
        hours >= 1 -> "${hours}h ago"
        else -> "just now"
    }
}

/**
 * Full-screen overlay for the "Add contact" flow.
 * Rendered at the App level so it appears above the tab bar.
 */
@Composable
fun AddContactDrawerOverlay() {
    val show by AppViewState.showAddContactDrawer.collectAsState()
    var currentlyShown by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    var pendingCard by remember { mutableStateOf<ContactCard?>(null) }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var drawerHeightPx by remember { mutableFloatStateOf(0f) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val offsetAnim = remember { Animatable(2000f) }

    fun offScreenPx() = drawerHeightPx.takeIf { it > 0f } ?: with(density) { 800.dp.toPx() }

    fun dismiss() {
        val card = pendingCard
        pendingCard = null
        scope.launch {
            dragOffsetPx = 0f
            offsetAnim.animateTo(offScreenPx(), tween(300))
            AppViewState.closeAddContactDrawer()
            currentlyShown = false
            text = ""
            hasError = false
            offsetAnim.snapTo(offScreenPx())
            if (card != null) {
                delay(50.milliseconds)
                AppViewState.showContactCard(card)
            }
        }
    }

    // Validate on every text change.
    LaunchedEffect(text) {
        if (text.isBlank()) {
            hasError = false
            return@LaunchedEffect
        }
        val card = QrCodeContactExchange.decode(text.trim()).getOrNull()
        if (card != null) {
            pendingCard = card
            dismiss()
        } else {
            hasError = true
        }
    }

    LaunchedEffect(show) {
        if (show) {
            currentlyShown = true
            dragOffsetPx = 0f
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

    val dismissThresholdPx = with(density) { 120.dp.toPx() }

    AnimatedVisibility(
        visible = currentlyShown,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(300)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismiss() },
                )
        )
    }

    if (currentlyShown) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            AddContactDrawer(
                text = text,
                onTextChange = { text = it },
                hasError = hasError,
                offsetProvider = { (offsetAnim.value + dragOffsetPx).roundToInt() },
                onHeightMeasured = { drawerHeightPx = it },
                onDrag = { delta -> dragOffsetPx = (dragOffsetPx + delta).coerceAtLeast(0f) },
                onDragStopped = { velocity ->
                    if (dragOffsetPx > dismissThresholdPx || velocity > 800f) {
                        dragOffsetPx = 0f
                        dismiss()
                    } else {
                        scope.launch {
                            dragOffsetPx = 0f
                            offsetAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            )
                        }
                    }
                },
                onPaste = { text = clipboard.getText()?.text ?: return@AddContactDrawer },
                onDismiss = { dismiss() },
            )
        }
    }
}

@Composable
private fun AddContactDrawer(
    text: String,
    onTextChange: (String) -> Unit,
    hasError: Boolean,
    offsetProvider: () -> Int,
    onHeightMeasured: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragStopped: (velocity: Float) -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val onSurface = MaterialTheme.colorScheme.onSurface

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
        // Drag handle
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { onDrag(it) },
                    onDragStopped = { onDragStopped(it) },
                )
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(Res.string.network_add_contact_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = onSurface,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = stringResource(Res.string.network_add_contact_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(Res.string.network_paste_link_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            isError = hasError,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(12.dp),
        )

        if (hasError) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.network_invalid_link),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        FreepathButton(
            onClick = onPaste,
            modifier = Modifier.fillMaxWidth(),
            variant = ButtonVariant.Outline,
        ) {
            Text(
                text = stringResource(Res.string.network_paste_link),
                style = MaterialTheme.typography.labelLarge,
                color = onSurface,
            )
        }
    }
}

/**
 * Full-screen overlay with scrim + animated bottom drawer for incoming contact cards.
 * Rendered at the App level so it appears above the tab bar.
 */
@Composable
fun ContactDrawerOverlay() {
    val pendingCard by AppViewState.pendingContactCard.collectAsState()
    // Keep the card alive during the exit animation.
    var currentCard by remember { mutableStateOf<ContactCard?>(null) }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Measured drawer height in px; used as the off-screen starting/ending offset.
    var drawerHeightPx by remember { mutableFloatStateOf(0f) }

    // Raw drag delta in px applied on top of the animated position.
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    // Single Animatable drives both the enter/exit animations and the drag snap-back.
    val offsetAnim = remember { Animatable(2000f) } // start well below screen

    fun offScreenPx() = drawerHeightPx.takeIf { it > 0f } ?: with(density) { 800.dp.toPx() }

    fun dismissAnimated() {
        scope.launch {
            dragOffsetPx = 0f
            offsetAnim.animateTo(offScreenPx(), tween(300))
            AppViewState.clearPendingContactCard()
            currentCard = null
            offsetAnim.snapTo(offScreenPx())
        }
    }

    fun acceptAnimated(card: ContactCard) {
        scope.launch { AppResources.system.tell(Protocol.AcceptContact(card)) }
        dismissAnimated()
    }

    LaunchedEffect(pendingCard) {
        if (pendingCard != null) {
            currentCard = pendingCard
            dragOffsetPx = 0f
            // Wait for the drawer to measure itself so we start from the exact bottom edge.
            val measuredHeight = snapshotFlow { drawerHeightPx }.first { it > 0f }
            offsetAnim.snapTo(measuredHeight)
            // Brief pause to let the screen fully settle before sliding in.
            delay(300.milliseconds)
            offsetAnim.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    val showScrim = currentCard != null
    val dismissThresholdPx = with(density) { 120.dp.toPx() }

    // Scrim
    AnimatedVisibility(
        visible = showScrim,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(300)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismissAnimated() },
                )
        )
    }

    // Drawer
    if (currentCard != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            currentCard?.let { card ->
                ContactDrawer(
                    card = card,
                    offsetProvider = { (offsetAnim.value + dragOffsetPx).roundToInt() },
                    onHeightMeasured = { drawerHeightPx = it },
                    onDrag = { delta ->
                        dragOffsetPx = (dragOffsetPx + delta).coerceAtLeast(0f)
                    },
                    onDragStopped = { velocity ->
                        if (dragOffsetPx > dismissThresholdPx || velocity > 800f) {
                            dragOffsetPx = 0f
                            dismissAnimated()
                        } else {
                            scope.launch {
                                dragOffsetPx = 0f
                                offsetAnim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                            }
                        }
                    },
                    onAccept = { acceptAnimated(card) },
                    onDismiss = { dismissAnimated() },
                )
            }
        }
    }
}

@Composable
private fun ContactDrawer(
    card: ContactCard,
    offsetProvider: () -> Int,
    onHeightMeasured: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragStopped: (velocity: Float) -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val onSurface = MaterialTheme.colorScheme.onSurface

    val displayName = card.name?.takeIf { it.isNotBlank() && !it.startsWith("#") }
        ?: card.nodeId.take(8)
    val avatarLabel = displayName.first().uppercaseChar().toString()

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
        // Drag handle
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { onDrag(it) },
                    onDragStopped = { onDragStopped(it) },
                )
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Avatar
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                .border(2.dp, onSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = avatarLabel,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Name
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = onSurface,
            textAlign = TextAlign.Center,
        )

        // Location
        val location = card.location
        if (!location.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = location,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Node ID fingerprint
        Text(
            text = stringResource(Res.string.node_id_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        FreepathFingerprint(text = card.nodeId)

        // Bio
        val bio = card.bio
        if (!bio.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = bio,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Accept / Reject buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FreepathButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                variant = ButtonVariant.Outline,
            ) {
                Text(
                    text = stringResource(Res.string.network_reject),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            FreepathButton(
                onClick = onAccept,
                modifier = Modifier.weight(1f),
                variant = ButtonVariant.Primary,
            ) {
                Text(
                    text = stringResource(Res.string.network_accept),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun AddButton() {
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .border(2.dp, color, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = { AppViewState.openAddContactDrawer() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+",
            modifier = Modifier.offset(y = (-1).dp),
            style = TextStyle(
                fontSize = 20.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
            color = color,
        )
    }
}
