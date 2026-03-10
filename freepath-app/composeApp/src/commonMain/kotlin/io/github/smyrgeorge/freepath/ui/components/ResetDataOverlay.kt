package io.github.smyrgeorge.freepath.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.smyrgeorge.composeapp.generated.resources.Res
import io.github.smyrgeorge.composeapp.generated.resources.dev_reset_overlay_cleared
import io.github.smyrgeorge.composeapp.generated.resources.dev_reset_overlay_clearing
import io.github.smyrgeorge.composeapp.generated.resources.dev_reset_overlay_failed
import io.github.smyrgeorge.freepath.AppUiState
import io.github.smyrgeorge.freepath.ui.theme.DarkOnSuccessContainer
import io.github.smyrgeorge.freepath.ui.theme.DarkSuccessContainer
import io.github.smyrgeorge.freepath.ui.theme.LightOnSuccessContainer
import io.github.smyrgeorge.freepath.ui.theme.LightSuccessContainer
import org.jetbrains.compose.resources.stringResource

@Composable
fun ResetDataOverlay() {
    val state by AppUiState.resetOverlay.collectAsState()

    if (state == AppUiState.ResetOverlayState.Hidden) return

    val darkTheme = isSystemInDarkTheme()

    val backgroundColor = when (state) {
        AppUiState.ResetOverlayState.Clearing -> MaterialTheme.colorScheme.background
        AppUiState.ResetOverlayState.Cleared ->
            if (darkTheme) DarkSuccessContainer else LightSuccessContainer

        AppUiState.ResetOverlayState.Failed -> MaterialTheme.colorScheme.errorContainer
        AppUiState.ResetOverlayState.Hidden -> MaterialTheme.colorScheme.background
    }

    val contentColor = when (state) {
        AppUiState.ResetOverlayState.Clearing -> MaterialTheme.colorScheme.onBackground
        AppUiState.ResetOverlayState.Cleared ->
            if (darkTheme) DarkOnSuccessContainer else LightOnSuccessContainer

        AppUiState.ResetOverlayState.Failed -> MaterialTheme.colorScheme.onErrorContainer
        AppUiState.ResetOverlayState.Hidden -> MaterialTheme.colorScheme.onBackground
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state == AppUiState.ResetOverlayState.Clearing) {
            CircularProgressIndicator(color = contentColor)
            Spacer(modifier = Modifier.height(24.dp))
        }

        Text(
            text = when (state) {
                AppUiState.ResetOverlayState.Clearing -> stringResource(Res.string.dev_reset_overlay_clearing)
                AppUiState.ResetOverlayState.Cleared -> stringResource(Res.string.dev_reset_overlay_cleared)
                AppUiState.ResetOverlayState.Failed -> stringResource(Res.string.dev_reset_overlay_failed)
                AppUiState.ResetOverlayState.Hidden -> ""
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}
