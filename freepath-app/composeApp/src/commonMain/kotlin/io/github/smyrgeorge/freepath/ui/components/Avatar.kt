package io.github.smyrgeorge.freepath.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class AvatarSize {
    Small,
    Medium,
    Large
}

@Composable
fun FreepathAvatar(
    label: String,
    modifier: Modifier = Modifier,
    size: AvatarSize = AvatarSize.Medium,
    isHub: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val dimension = when (size) {
        AvatarSize.Small -> 26.dp
        AvatarSize.Medium -> 38.dp
        AvatarSize.Large -> 48.dp
    }

    val textStyle = when (size) {
        AvatarSize.Small -> MaterialTheme.typography.labelSmall
        AvatarSize.Medium -> MaterialTheme.typography.labelMedium
        AvatarSize.Large -> MaterialTheme.typography.labelLarge
    }

    val shape = if (isHub) RoundedCornerShape(8.dp) else CircleShape
    val backgroundColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val borderColor = MaterialTheme.colorScheme.outline

    Box(
        modifier = modifier
            .size(dimension)
            .clip(shape)
            .background(backgroundColor)
            .border(2.dp, borderColor, shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = textStyle
        )
    }
}

@Composable
fun FreepathBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
    }
}

enum class PillVariant {
    Default,
    Dark,
    Outline
}

@Composable
fun FreepathPill(
    label: String,
    modifier: Modifier = Modifier,
    variant: PillVariant = PillVariant.Default
) {
    val backgroundColor = when (variant) {
        PillVariant.Default -> MaterialTheme.colorScheme.primaryContainer
        PillVariant.Dark -> MaterialTheme.colorScheme.primary
        PillVariant.Outline -> Color.Transparent
    }

    val contentColor = when (variant) {
        PillVariant.Default -> MaterialTheme.colorScheme.onPrimaryContainer
        PillVariant.Dark -> MaterialTheme.colorScheme.onPrimary
        PillVariant.Outline -> MaterialTheme.colorScheme.onSurface
    }

    val borderModifier = if (variant == PillVariant.Outline) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .then(borderModifier)
            .background(backgroundColor)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.uppercase(),
            color = contentColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
