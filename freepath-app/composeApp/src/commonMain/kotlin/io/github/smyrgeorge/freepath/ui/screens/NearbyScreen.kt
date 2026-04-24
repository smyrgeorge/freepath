package io.github.smyrgeorge.freepath.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.smyrgeorge.composeapp.generated.resources.Res
import io.github.smyrgeorge.composeapp.generated.resources.nearby_title
import io.github.smyrgeorge.freepath.AppResources
import io.github.smyrgeorge.freepath.AppState
import io.github.smyrgeorge.freepath.model.content.ContentBody
import io.github.smyrgeorge.freepath.core.actor.AppProtocol
import io.github.smyrgeorge.freepath.core.state.abbrev
import io.github.smyrgeorge.freepath.core.state.model.ConnectionSource
import io.github.smyrgeorge.freepath.database.ContactEntry
import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.libnet.Transport
import io.github.smyrgeorge.freepath.ui.components.AvatarSize
import io.github.smyrgeorge.freepath.ui.components.ButtonSize
import io.github.smyrgeorge.freepath.ui.components.ButtonVariant
import io.github.smyrgeorge.freepath.ui.components.FreepathAvatar
import io.github.smyrgeorge.freepath.ui.components.FreepathButton
import io.github.smyrgeorge.freepath.ui.components.FreepathTopBar
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NearbyScreen(
    modifier: Modifier = Modifier,
    onContactClick: ((ContactEntry) -> Unit)? = null,
) {
    val allNearby by AppState.nearbyPeers.collectAsState()
    val contacts by AppState.contacts.collectAsState()
    val contactContents by AppState.contactContents.collectAsState()
    val contactByPeerId = contacts.associateBy { it.peerId }

    val bleMetrics by AppResources.libble.metrics.value.collectAsState()

    // Only peers that are actually in our contact list
    val identifiedContacts = allNearby.filter { (peerId, _) -> peerId in contactByPeerId }

    // Freepath peers nearby on LAN but not yet in our contacts
    val unidentifiedLanPeers = allNearby
        .filter { (peerId, sources) -> ConnectionSource.LAN in sources && peerId !in contactByPeerId }
        .keys
        .toList()

    // BLE peripherals not matched to any contact
    val unidentifiedBlePeripherals = bleMetrics.discoveredPeripherals
        .filterKeys { it !in bleMetrics.identifiedPeripherals }
        .values.toList()

    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        FreepathTopBar(
            title = stringResource(Res.string.nearby_title),
            rightAction = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (Transport.LIBBLE.isSupported) {
                        FreepathButton(
                            onClick = {
                                scope.launch {
                                    AppResources.system.tell(AppProtocol.BleInitiateResponderContactExchange)
                                }
                            },
                            variant = ButtonVariant.Outline,
                            size = ButtonSize.Small,
                        ) {
                            Text(
                                text = "Receive",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    ScanningIndicator()
                }
            },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            RadarView(
                peers = identifiedContacts.keys.toList(),
                contactByPeerId = contactByPeerId,
                contactContents = contactContents,
            )
        }

        val ownTokenHex = bleMetrics.advertisedTokenHex
        if (ownTokenHex != null) {
            Text(
                text = "You appear as #${ownTokenHex.abbrev()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 80.dp),
        ) {
            // ── Unidentified section (shown above contacts) ─────────────────────
            if (unidentifiedBlePeripherals.isNotEmpty() || unidentifiedLanPeers.isNotEmpty()) {
                item {
                    SectionHeader("Nearby Devices")
                }
                items(unidentifiedBlePeripherals, key = { it.peripheralId }) { peer ->
                    BlePeerCard(peer)
                }
                items(unidentifiedLanPeers, key = { it }) { peerId ->
                    LanUnknownPeerCard(peerId)
                }
            }

            // ── Identified contacts section ─────────────────────────────────────
            if (identifiedContacts.isNotEmpty()) {
                if (unidentifiedBlePeripherals.isNotEmpty() || unidentifiedLanPeers.isNotEmpty()) {
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                }
                item {
                    SectionHeader("Contacts Nearby")
                }
                items(identifiedContacts.entries.toList(), key = { it.key }) { (peerId, sources) ->
                    val contactEntry = contactByPeerId[peerId]
                    IdentifiedPeerCard(
                        peerId = peerId,
                        contact = contactEntry,
                        content = contactContents[peerId],
                        sources = sources,
                        onClick = onContactClick?.takeIf { contactEntry != null }?.let { cb ->
                            { cb(contactEntry!!) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun ScanningIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = "Scanning",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = dotAlpha),
                    CircleShape,
                )
        )
    }
}

@Composable
private fun RadarView(
    peers: List<String>,
    contactByPeerId: Map<String, ContactEntry>,
    contactContents: Map<String, ContentBody.Contact>,
    modifier: Modifier = Modifier,
) {
    val radarSize = 200.dp
    val ringColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val onSurface = MaterialTheme.colorScheme.onSurface

    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseAlpha",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseScale",
    )

    Box(
        modifier = modifier.size(radarSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(radarSize)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val strokeWidth = 3.5.dp.toPx()
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f)
            val stroke = Stroke(width = strokeWidth, pathEffect = dashEffect)
            drawCircle(color = ringColor, radius = size.width / 2f - strokeWidth / 2f, center = center, style = stroke)
            drawCircle(color = ringColor, radius = size.width * 0.335f, center = center, style = stroke)
            drawCircle(color = ringColor, radius = size.width * 0.17f, center = center, style = stroke)
        }

        Box(
            Modifier
                .size(radarSize * pulseScale)
                .border(1.dp, onSurface.copy(alpha = pulseAlpha), CircleShape)
        )

        val deviceRadius = radarSize.value * 0.35f
        peers.forEachIndexed { index, peerId ->
            val angle = (2.0 * PI * index / peers.size - PI / 2).toFloat()
            val offsetX = (deviceRadius * cos(angle)).dp
            val offsetY = (deviceRadius * sin(angle)).dp
            val contact = contactByPeerId[peerId]
            val label = contact?.resolvedDisplayName()?.first()?.uppercaseChar()?.toString() ?: "?"
            FreepathAvatar(
                label = label,
                avatar = contactContents[peerId]?.avatar,
                size = AvatarSize.Small,
                modifier = Modifier.offset(x = offsetX, y = offsetY),
            )
        }

        // Center dot
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(onSurface.copy(alpha = 0.5f), CircleShape),
        )
    }
}

@Composable
private fun IdentifiedPeerCard(
    peerId: String,
    contact: ContactEntry?,
    content: ContentBody.Contact?,
    sources: Set<ConnectionSource>,
    onClick: (() -> Unit)? = null,
) {
    val displayName = contact?.resolvedDisplayName() ?: peerId.abbrev()
    val avatarLabel = displayName.first().uppercaseChar().toString()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = onClick,
                ) else Modifier
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FreepathAvatar(label = avatarLabel, avatar = content?.avatar, size = AvatarSize.Medium)
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 7.dp, end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ConnectionSource.LAN in sources) {
                Icon(
                    imageVector = WifiIcon,
                    contentDescription = "LAN",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ConnectionSource.BLE in sources) {
                Icon(
                    imageVector = BluetoothIcon,
                    contentDescription = "BLE",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LanUnknownPeerCard(peerId: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "?",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "#${peerId.abbrev()}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Icon(
            imageVector = WifiIcon,
            contentDescription = "LAN",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 7.dp, end = 10.dp)
                .size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BlePeerCard(peer: LibbleEvent.PeripheralDiscovered) {
    val scope = rememberCoroutineScope()
    // Use the identity token as the display suffix — it's the same on all platforms
    // since it's derived from the advertiser's shared secret.
    // Falls back to the OS-assigned peripheralId if no token is available (pre-exchange devices).
    val tokenHex = peer.identityTokenHex
    val displayName = if (tokenHex != null) {
        "#${tokenHex.abbrev()}"
    } else {
        "#${peer.peripheralId.replace(":", "").replace("-", "").abbrev().lowercase()}"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "?",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = BluetoothIcon,
                        contentDescription = "BLE",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${peer.rssi} dBm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FreepathButton(
                onClick = {
                    scope.launch {
                        AppResources.system.tell(AppProtocol.BleInitiateContactExchange(peer.peripheralId))
                    }
                },
                variant = ButtonVariant.Outline,
                size = ButtonSize.Small,
            ) {
                Text(
                    text = "Add",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun ContactEntry.resolvedDisplayName(): String? {
    val local = name?.takeIf { it.isNotBlank() }
    val c = contact.name?.takeIf { it.isNotBlank() && !it.startsWith("#") }
    return local ?: c
}

private val WifiIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = addPathNodes("M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3c-1.65-1.66-4.34-1.66-6 0zm-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z"),
            fill = SolidColor(androidx.compose.ui.graphics.Color.Black),
        )
    }.build()
}

private val BluetoothIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = addPathNodes("M17.71 7.71L12 2h-1v7.59L6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 11 14.41V22h1l5.71-5.71-4.3-4.29 4.3-4.29zM13 5.83l1.88 1.88L13 9.59V5.83zm1.88 10.46L13 18.17v-3.76l1.88 1.88z"),
            fill = SolidColor(androidx.compose.ui.graphics.Color.Black),
        )
    }.build()
}
