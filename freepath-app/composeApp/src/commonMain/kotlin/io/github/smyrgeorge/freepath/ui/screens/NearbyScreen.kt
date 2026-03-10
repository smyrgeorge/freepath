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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.smyrgeorge.composeapp.generated.resources.Res
import io.github.smyrgeorge.composeapp.generated.resources.nearby_n_devices
import io.github.smyrgeorge.composeapp.generated.resources.nearby_no_devices
import io.github.smyrgeorge.composeapp.generated.resources.nearby_one_device
import io.github.smyrgeorge.composeapp.generated.resources.nearby_status_connected
import io.github.smyrgeorge.composeapp.generated.resources.nearby_status_stranger
import io.github.smyrgeorge.composeapp.generated.resources.nearby_title
import io.github.smyrgeorge.freepath.AppResources
import io.github.smyrgeorge.freepath.AppState
import io.github.smyrgeorge.freepath.Protocol
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import io.github.smyrgeorge.freepath.state.model.DiscoveredPeer
import io.github.smyrgeorge.freepath.ui.components.ButtonSize
import io.github.smyrgeorge.freepath.ui.components.ButtonVariant
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
) {
    val peers by AppState.discoveredPeers.collectAsState()
    val contacts by AppState.contacts.collectAsState()
    val contactByNodeId = contacts.associateBy { it.nodeId }
    val allPeers = peers.values.toList()
    val knownPeers = allPeers.filter { it.isKnown }
    val unknownPeers = allPeers.filter { !it.isKnown }

    Column(modifier = modifier.fillMaxSize()) {
        FreepathTopBar(
            title = stringResource(Res.string.nearby_title),
            rightAction = { ScanningIndicator() },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            RadarView(peers = allPeers, contactByNodeId = contactByNodeId)
        }

        Text(
            text = when {
                allPeers.isEmpty() -> stringResource(Res.string.nearby_no_devices)
                allPeers.size == 1 -> stringResource(Res.string.nearby_one_device)
                else -> stringResource(Res.string.nearby_n_devices, allPeers.size)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 80.dp),
        ) {
            if (knownPeers.isNotEmpty()) {
                items(knownPeers, key = { it.nodeId }) { peer ->
                    PeerCard(peer, contactByNodeId[peer.nodeId])
                }
            }

            if (unknownPeers.isNotEmpty()) {
                if (knownPeers.isNotEmpty()) {
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                }
                items(unknownPeers, key = { it.nodeId }) { peer ->
                    PeerCard(peer, null)
                }
            }
        }
    }
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
    peers: List<DiscoveredPeer>,
    contactByNodeId: Map<String, ContactCardEntry>,
    modifier: Modifier = Modifier,
) {
    val radarSize = 200.dp
    val ringColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

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
        // Dashed static rings drawn on Canvas
        Canvas(modifier = Modifier.size(radarSize)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val strokeWidth = 3.5.dp.toPx()
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f)
            val stroke = Stroke(width = strokeWidth, pathEffect = dashEffect)

            drawCircle(color = ringColor, radius = size.width / 2f - strokeWidth / 2f, center = center, style = stroke)
            drawCircle(color = ringColor, radius = size.width * 0.335f, center = center, style = stroke)
            drawCircle(color = ringColor, radius = size.width * 0.17f, center = center, style = stroke)
        }

        // Pulse ring (solid, animated)
        Box(
            Modifier
                .size(radarSize * pulseScale)
                .border(1.dp, onSurface.copy(alpha = pulseAlpha), CircleShape)
        )

        // Device avatars positioned around the radar
        val deviceRadius = radarSize.value * 0.35f
        peers.forEachIndexed { index, peer ->
            val angle = (2.0 * PI * index / peers.size - PI / 2).toFloat()
            val offsetX = (deviceRadius * cos(angle)).dp
            val offsetY = (deviceRadius * sin(angle)).dp

            val bgColor = if (peer.isKnown) secondaryContainer else surfaceVariant
            val contactName = contactByNodeId[peer.nodeId]?.resolvedDisplayName()
            val avatarLabel = if (peer.isKnown) (contactName ?: peer.nodeId).first().uppercaseChar().toString() else "?"

            Box(
                modifier = Modifier
                    .offset(x = offsetX, y = offsetY)
                    .size(28.dp)
                    .background(bgColor, CircleShape)
                    .border(2.dp, onSurface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = avatarLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }

        // Center "LAN" mode label
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(onSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "LAN",
                style = MaterialTheme.typography.labelSmall,
                color = surface,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PeerCard(peer: DiscoveredPeer, contact: ContactCardEntry?) {
    val scope = rememberCoroutineScope()
    val avatarBg = if (peer.isKnown) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val displayName =
        if (peer.isKnown) contact?.resolvedDisplayName() ?: peer.nodeId.take(12) else "#${peer.nodeId.take(8)}"
    val avatarLabel = if (peer.isKnown) displayName.first().uppercaseChar().toString() else "?"
    val statusText =
        if (peer.isKnown) stringResource(Res.string.nearby_status_connected)
        else stringResource(Res.string.nearby_status_stranger)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(avatarBg, CircleShape)
                .border(2.dp, onSurface, CircleShape)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = avatarLabel,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!peer.isKnown) {
            FreepathButton(
                onClick = {
                    scope.launch {
                        AppResources.system.tell(Protocol.InitiateContactExchange(peer.nodeId) { pin ->
                            AppResources.lanAdapter.contactExchangeFrame(
                                peer.nodeId, pin, AppState.contactCard, AppState.identity.sigKeyPrivate
                            )
                        })
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

private fun ContactCardEntry.resolvedDisplayName(): String? {
    val local = name?.takeIf { it.isNotBlank() }
    val card = card.name?.takeIf { it.isNotBlank() && !it.startsWith("#") }
    return local ?: card
}
