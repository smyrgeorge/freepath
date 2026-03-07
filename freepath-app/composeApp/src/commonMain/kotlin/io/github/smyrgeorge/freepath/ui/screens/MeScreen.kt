package io.github.smyrgeorge.freepath.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import io.github.smyrgeorge.composeapp.generated.resources.me_copied
import io.github.smyrgeorge.composeapp.generated.resources.me_copy_link
import io.github.smyrgeorge.composeapp.generated.resources.me_share_qr
import io.github.smyrgeorge.composeapp.generated.resources.me_title
import io.github.smyrgeorge.freepath.AppState
import io.github.smyrgeorge.freepath.contact.ContactCardCodec
import io.github.smyrgeorge.freepath.contact.exchange.QrCodeContactExchange
import io.github.smyrgeorge.freepath.ui.components.ButtonVariant
import io.github.smyrgeorge.freepath.ui.components.FreepathButton
import io.github.smyrgeorge.freepath.ui.components.FreepathFingerprint
import io.github.smyrgeorge.freepath.ui.components.FreepathTopBar
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun MeScreen(modifier: Modifier = Modifier) {
    val card = AppState.contactCard
    val nodeId = card.nodeId

    val displayName = remember(card) {
        card.name?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: "you"
    }
    val avatarLabel = remember(card) {
        val name = card.name?.takeIf { it.isNotBlank() && !it.startsWith("#") }
        (name?.firstOrNull()?.uppercaseChar() ?: nodeId.first().uppercaseChar()).toString()
    }
    val qrData = remember(card) {
        val signed = ContactCardCodec.seal(card, AppState.identity.sigKeyPrivate)
        QrCodeContactExchange.encode(signed)
    }

    Column(modifier = modifier.fillMaxSize()) {
        FreepathTopBar(title = stringResource(Res.string.me_title))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                IdentityCard(
                    avatarLabel = avatarLabel,
                    displayName = displayName,
                    nodeId = nodeId,
                    qrData = qrData,
                )
            }
        }
    }
}

@Composable
private fun IdentityCard(
    avatarLabel: String,
    displayName: String,
    nodeId: String,
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
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                .border(2.dp, onSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = avatarLabel,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            text = displayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        FreepathFingerprint(text = nodeId)

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
