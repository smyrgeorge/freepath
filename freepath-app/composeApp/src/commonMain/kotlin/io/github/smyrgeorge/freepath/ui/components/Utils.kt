package io.github.smyrgeorge.freepath.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.smyrgeorge.freepath.ui.theme.DarkFingerprintBackground
import io.github.smyrgeorge.freepath.ui.theme.FingerprintBackground
import io.github.smyrgeorge.freepath.ui.theme.FingerprintGray
import io.github.smyrgeorge.freepath.ui.theme.FingerprintStyle
import io.github.smyrgeorge.freepath.ui.theme.SectionTitleStyle

@Composable
fun FreepathFingerprint(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val isLight = MaterialTheme.colorScheme.background == FingerprintBackground
    val backgroundColor = if (isLight) FingerprintBackground else DarkFingerprintBackground

    val formatted = text.chunked(11).joinToString(" ")

    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .padding(7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = formatted,
            style = FingerprintStyle,
            color = FingerprintGray,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun FreepathDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    thickness: Dp = 1.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color)
    )
}

@Composable
fun HopDots(
    hopCount: Int,
    maxDots: Int = 5,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (i in 0 until maxDots) {
            val isFilled = i < hopCount
            val backgroundColor = if (isFilled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline
            }

            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(backgroundColor)
            )
        }
    }
}

@Composable
fun SignalBars(
    strength: Int,
    maxBars: Int = 4,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 0 until maxBars) {
            val isActive = i < strength
            val height = when (i) {
                0 -> 5.dp
                1 -> 9.dp
                2 -> 13.dp
                3 -> 16.dp
                else -> 16.dp
            }

            val color = if (isActive) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline
            }

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height)
                    .background(color, RoundedCornerShape(1.dp))
            )
        }
    }
}

@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = SectionTitleStyle
    )
}
