package io.github.smyrgeorge.freepath.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.options.QrBallShape
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.QrFrameShape
import io.github.alexzhirkevich.qrose.options.QrPixelShape
import io.github.alexzhirkevich.qrose.options.circle
import io.github.alexzhirkevich.qrose.options.roundCorners
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import io.github.smyrgeorge.composeapp.generated.resources.Res
import io.github.smyrgeorge.composeapp.generated.resources.dev_delete_content
import io.github.smyrgeorge.composeapp.generated.resources.dev_delete_content_subtitle
import io.github.smyrgeorge.composeapp.generated.resources.dev_generate_contact_content
import io.github.smyrgeorge.composeapp.generated.resources.dev_generate_contact_content_subtitle
import io.github.smyrgeorge.composeapp.generated.resources.dev_generate_self_content
import io.github.smyrgeorge.composeapp.generated.resources.dev_generate_self_content_subtitle
import io.github.smyrgeorge.composeapp.generated.resources.dev_libble_connectable
import io.github.smyrgeorge.composeapp.generated.resources.dev_libble_discovered_peripherals
import io.github.smyrgeorge.composeapp.generated.resources.dev_libble_identified_peers
import io.github.smyrgeorge.composeapp.generated.resources.dev_libble_metrics_title
import io.github.smyrgeorge.composeapp.generated.resources.dev_libble_none
import io.github.smyrgeorge.composeapp.generated.resources.dev_libble_tx_power
import io.github.smyrgeorge.composeapp.generated.resources.dev_libp2p_connected_peers
import io.github.smyrgeorge.composeapp.generated.resources.dev_libp2p_identified_peers
import io.github.smyrgeorge.composeapp.generated.resources.dev_libp2p_listen_addresses
import io.github.smyrgeorge.composeapp.generated.resources.dev_libp2p_mdns_peers
import io.github.smyrgeorge.composeapp.generated.resources.dev_libp2p_metrics_title
import io.github.smyrgeorge.composeapp.generated.resources.dev_libp2p_none
import io.github.smyrgeorge.composeapp.generated.resources.dev_reset_data
import io.github.smyrgeorge.composeapp.generated.resources.dev_reset_data_subtitle
import io.github.smyrgeorge.composeapp.generated.resources.dev_section_title
import io.github.smyrgeorge.composeapp.generated.resources.me_copied
import io.github.smyrgeorge.composeapp.generated.resources.me_copy_link
import io.github.smyrgeorge.composeapp.generated.resources.me_share_qr
import io.github.smyrgeorge.composeapp.generated.resources.me_title
import io.github.smyrgeorge.freepath.AppResources
import io.github.smyrgeorge.freepath.AppState
import io.github.smyrgeorge.freepath.AppViewState
import io.github.smyrgeorge.freepath.contact.ContactSignedCodec
import io.github.smyrgeorge.freepath.contact.exchange.QrCodeContactExchange
import io.github.smyrgeorge.freepath.libble.metrics.LibbleMetricsSnapshot
import io.github.smyrgeorge.freepath.libp2p.metrics.Libp2pMetricsSnapshot
import io.github.smyrgeorge.freepath.state.RandomAvatarGenerator
import io.github.smyrgeorge.freepath.state.abbrev
import io.github.smyrgeorge.freepath.ui.components.ButtonVariant
import io.github.smyrgeorge.freepath.ui.components.FreepathButton
import io.github.smyrgeorge.freepath.ui.components.FreepathDivider
import io.github.smyrgeorge.freepath.ui.components.FreepathFingerprint
import io.github.smyrgeorge.freepath.ui.components.FreepathTopBar
import io.github.smyrgeorge.freepath.ui.components.SectionTitle
import io.github.smyrgeorge.freepath.util.toImageBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun MeScreen(modifier: Modifier = Modifier) {
    val contact = AppState.contact
    val peerId = contact.peerId

    val displayName = remember(contact) {
        contact.name?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: "you"
    }
    val avatarLabel = remember(contact) {
        val name = contact.name?.takeIf { it.isNotBlank() && !it.startsWith("#") }
        (name?.firstOrNull()?.uppercaseChar() ?: peerId.first().uppercaseChar()).toString()
    }
    var avatarBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isRefreshingAvatar by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(peerId) {
        val stored = AppState.contactContentBody.avatar
        if (stored != null) {
            avatarBitmap = runCatching { Base64.decode(stored).toImageBitmap() }.getOrNull()
            return@LaunchedEffect
        }
        // No stored avatar yet — fetch from DiceBear
        val name = contact.name?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: return@LaunchedEffect
        RandomAvatarGenerator.randomAvatar(name)?.let { b64 ->
            avatarBitmap = runCatching { Base64.decode(b64).toImageBitmap() }.getOrNull()
        }
    }
    val onRefreshAvatar: () -> Unit = {
        if (!isRefreshingAvatar) {
            val name = contact.name?.takeIf { it.isNotBlank() && !it.startsWith("#") }
            if (name != null) {
                scope.launch {
                    isRefreshingAvatar = true
                    val b64 = RandomAvatarGenerator.randomAvatar(name)
                    if (b64 != null) {
                        AppState.updateAvatar(b64)
                        avatarBitmap = runCatching { Base64.decode(b64).toImageBitmap() }.getOrNull()
                    }
                    isRefreshingAvatar = false
                }
            }
        }
    }
    val qrData = remember(contact) {
        val signed = ContactSignedCodec.seal(contact, AppState.identity.sigKeyPrivate)
        QrCodeContactExchange.encode(signed)
    }

    Column(modifier = modifier.fillMaxSize()) {
        FreepathTopBar(title = stringResource(Res.string.me_title))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                IdentityCard(
                    avatarLabel = avatarLabel,
                    avatarBitmap = avatarBitmap,
                    isRefreshingAvatar = isRefreshingAvatar,
                    onRefreshAvatar = onRefreshAvatar,
                    displayName = displayName,
                    peerId = peerId,
                    qrData = qrData,
                )
            }
            item { DeveloperSection() }
        }
    }
}

@Composable
private fun DeveloperSection() {
    val libp2pMetrics by AppResources.libp2p.metrics.value.collectAsState()
    val libbleMetrics by AppResources.libble.metrics.value.collectAsState()
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(text = stringResource(Res.string.dev_section_title))
        Libp2pMetricsPanel(metrics = libp2pMetrics, selfPeerId = AppState.contact.peerId)
        LibbleMetricsPanel(metrics = libbleMetrics)
        FreepathButton(
            onClick = { scope.launch { AppState.generateRandomSelfContent() } },
            modifier = Modifier.fillMaxWidth(),
            variant = ButtonVariant.Outline,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.dev_generate_self_content),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.dev_generate_self_content_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FreepathButton(
            onClick = { scope.launch { AppState.generateRandomContactContent() } },
            modifier = Modifier.fillMaxWidth(),
            variant = ButtonVariant.Outline,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.dev_generate_contact_content),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.dev_generate_contact_content_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FreepathButton(
            onClick = { AppViewState.showDeleteContentConfirmation() },
            modifier = Modifier.fillMaxWidth(),
            variant = ButtonVariant.Destructive,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.dev_delete_content),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onError,
                )
                Text(
                    text = stringResource(Res.string.dev_delete_content_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError.copy(alpha = 0.7f),
                )
            }
        }
        FreepathButton(
            onClick = { AppViewState.showResetDataConfirmation() },
            modifier = Modifier.fillMaxWidth(),
            variant = ButtonVariant.Destructive,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.dev_reset_data),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onError,
                )
                Text(
                    text = stringResource(Res.string.dev_reset_data_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun LibbleMetricsPanel(metrics: LibbleMetricsSnapshot) {
    val none = stringResource(Res.string.dev_libble_none)
    val txPowerLabel = stringResource(Res.string.dev_libble_tx_power)
    val connectableLabel = stringResource(Res.string.dev_libble_connectable)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.dev_libble_metrics_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FreepathDivider()
        MetricRow(
            label = stringResource(Res.string.dev_libble_discovered_peripherals),
            value = metrics.discoveredPeripherals.takeIf { it.isNotEmpty() }
                ?.values?.joinToString("\n") { p ->
                    val label = p.peripheralName ?: p.name ?: p.peripheralId.abbrev()
                    val tx = p.txPower?.let { " | $txPowerLabel: ${it}dBm" } ?: ""
                    val connectable = p.isConnectable?.let { " | $connectableLabel: $it" } ?: ""
                    "$label ${p.peripheralId.abbrev()} (rssi: ${p.rssi}dBm$tx$connectable)"
                } ?: none,
        )
        MetricRow(
            label = stringResource(Res.string.dev_libble_identified_peers),
            value = metrics.identifiedPeers.takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { it.abbrev() } ?: none,
        )
    }
}

@Composable
private fun Libp2pMetricsPanel(metrics: Libp2pMetricsSnapshot, selfPeerId: String) {
    val none = stringResource(Res.string.dev_libp2p_none)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.dev_libp2p_metrics_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FreepathDivider()
        MetricRow(
            label = stringResource(Res.string.dev_libp2p_listen_addresses),
            value = metrics.listenAddresses.takeIf { it.isNotEmpty() }
                ?.joinToString("\n") ?: none,
        )
        MetricRow(
            label = stringResource(Res.string.dev_libp2p_connected_peers),
            value = metrics.connectedPeers.size.toString(),
        )
        MetricRow(
            label = stringResource(Res.string.dev_libp2p_identified_peers),
            value = metrics.identifiedPeers.takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { it.abbrev() } ?: none,
        )
        MetricRow(
            label = stringResource(Res.string.dev_libp2p_mdns_peers),
            value = metrics.mdnsPeers.takeIf { it.isNotEmpty() }
                ?.entries?.joinToString("\n") { (peerId, addr) ->
                    val suffix = if (peerId == selfPeerId) " (self)" else ""
                    "${peerId.abbrev()} @ $addr$suffix"
                } ?: none,
        )
    }
}


@Composable
private fun MetricRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun IdentityCard(
    avatarLabel: String,
    avatarBitmap: ImageBitmap?,
    isRefreshingAvatar: Boolean,
    onRefreshAvatar: () -> Unit,
    displayName: String,
    peerId: String,
    qrData: String,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(16.dp),
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(64.dp)) {
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
                        painter = BitmapPainter(avatarBitmap),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = avatarLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp)
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

        Text(
            text = displayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        FreepathFingerprint(text = peerId)

        Spacer(modifier = Modifier.height(4.dp))

        val darkColor = MaterialTheme.colorScheme.onSurface
        val lightColor = MaterialTheme.colorScheme.surface
        val qrPainter = rememberQrCodePainter(qrData) {
            shapes {
                ball = QrBallShape.circle()
                darkPixel = QrPixelShape.roundCorners()
                frame = QrFrameShape.roundCorners(.25f)
            }
            colors {
                dark = QrBrush.solid(darkColor)
                light = QrBrush.solid(lightColor)
            }
        }
        Box(
            modifier = Modifier
                .background(lightColor, RoundedCornerShape(12.dp))
                .border(2.dp, darkColor, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Image(
                painter = qrPainter,
                contentDescription = null,
                modifier = Modifier.size(170.dp),
            )
        }

        Text(
            text = stringResource(Res.string.me_share_qr),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        val clipboard = LocalClipboardManager.current
        var copied by remember { mutableStateOf(false) }
        LaunchedEffect(copied) {
            if (copied) {
                delay(2000.milliseconds)
                copied = false
            }
        }

        FreepathButton(
            onClick = {
                clipboard.setText(AnnotatedString(qrData))
                copied = true
            },
            modifier = Modifier.fillMaxWidth(),
            variant = ButtonVariant.Outline,
        ) {
            Text(
                text = if (copied) stringResource(Res.string.me_copied)
                else stringResource(Res.string.me_copy_link),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
