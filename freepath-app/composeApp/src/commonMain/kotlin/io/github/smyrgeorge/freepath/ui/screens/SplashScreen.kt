package io.github.smyrgeorge.freepath.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.smyrgeorge.composeapp.generated.resources.Res
import io.github.smyrgeorge.composeapp.generated.resources.app_name
import io.github.smyrgeorge.freepath.AppState
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

@Composable
fun SplashScreen(
    modifier: Modifier = Modifier,
    onReady: () -> Unit,
) {
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        val time = measureTime {
            AppState.initialize()
        }

        AppState.log.info { "App initialization took $time" }
        if (time < 1.seconds) delay(1.seconds - time)

        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 400),
        )

        onReady()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(alpha.value)
            .background(MaterialTheme.colorScheme.background),
    ) {
        // App icon — centered
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    color = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(20.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⬡",
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.background,
            )
        }

        // App name — bottom center
        Text(
            text = stringResource(Res.string.app_name),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
        )
    }
}
