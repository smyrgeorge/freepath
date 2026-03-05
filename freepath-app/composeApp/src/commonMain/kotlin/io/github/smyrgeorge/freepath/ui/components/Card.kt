package io.github.smyrgeorge.freepath.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

enum class BorderStyle {
    Solid,
    Dashed
}

@Composable
fun FreepathCard(
    modifier: Modifier = Modifier,
    borderStyle: BorderStyle = BorderStyle.Solid,
    borderColor: Color? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val defaultBorder = borderColor ?: MaterialTheme.colorScheme.outline
    val borderWidth = if (borderStyle == BorderStyle.Dashed) 1.dp else 2.dp
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (borderStyle == BorderStyle.Dashed) {
                    Modifier.border(
                        width = borderWidth,
                        brush = SolidColor(defaultBorder),
                        shape = shape
                    )
                } else {
                    Modifier.border(borderWidth, defaultBorder, shape)
                }
            )
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        content = content
    )
}
