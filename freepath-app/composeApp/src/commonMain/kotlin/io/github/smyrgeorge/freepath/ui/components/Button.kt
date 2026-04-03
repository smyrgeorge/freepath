package io.github.smyrgeorge.freepath.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ButtonVariant {
    Primary,
    Outline,
    Destructive,
}

enum class ButtonSize {
    Small,
    Medium
}

@Composable
fun FreepathButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    size: ButtonSize = ButtonSize.Medium,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val backgroundColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        variant == ButtonVariant.Primary -> MaterialTheme.colorScheme.primary
        variant == ButtonVariant.Outline -> Color.Transparent
        variant == ButtonVariant.Destructive -> MaterialTheme.colorScheme.error
        else -> Color.Transparent
    }

    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        variant == ButtonVariant.Primary -> MaterialTheme.colorScheme.onPrimary
        variant == ButtonVariant.Outline -> MaterialTheme.colorScheme.onSurface
        variant == ButtonVariant.Destructive -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onSurface
    }

    val height = when (size) {
        ButtonSize.Small -> 36.dp
        ButtonSize.Medium -> 48.dp
    }

    val cornerRadius = when (size) {
        ButtonSize.Small -> 8.dp
        ButtonSize.Medium -> 12.dp
    }

    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .height(height)
            .widthIn(min = 48.dp)
            .clip(shape)
            .then(
                if (variant == ButtonVariant.Outline && enabled) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else if (variant == ButtonVariant.Outline && !enabled) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape)
                } else {
                    Modifier
                }
            )
            .background(backgroundColor)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
