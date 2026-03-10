package io.github.smyrgeorge.freepath

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.smyrgeorge.freepath.ui.components.FreepathTabBar
import io.github.smyrgeorge.freepath.ui.components.LanExchangeDrawer
import io.github.smyrgeorge.freepath.ui.components.ResetDataDrawer
import io.github.smyrgeorge.freepath.ui.components.ResetDataOverlay
import io.github.smyrgeorge.freepath.ui.components.TabItem
import io.github.smyrgeorge.freepath.ui.screens.AddContactDrawerOverlay
import io.github.smyrgeorge.freepath.ui.screens.ContactDrawerOverlay
import io.github.smyrgeorge.freepath.ui.screens.MeScreen
import io.github.smyrgeorge.freepath.ui.screens.NearbyScreen
import io.github.smyrgeorge.freepath.ui.screens.NetworkScreen
import io.github.smyrgeorge.freepath.ui.screens.OnboardingScreen
import io.github.smyrgeorge.freepath.ui.screens.SplashScreen
import io.github.smyrgeorge.freepath.ui.theme.FreepathTheme

private enum class Screen { Splash, Onboarding, Nearby, Network, Me }

private val APP_TABS = listOf(
    TabItem(icon = "◎", label = "Nearby", isCircle = true),
    TabItem(icon = "◈", label = "Network"),
    // TabItem(icon = "☰", label = "Feed"),
    // TabItem(icon = "▤", label = "Library"),
    TabItem(icon = "◉", label = "Me", isCircle = true),
)

private val APP_SCREENS = setOf(Screen.Nearby, Screen.Network, Screen.Me)

@Preview
@Composable
fun App() {
    var screen by remember { mutableStateOf(Screen.Splash) }
    val startupRoute by AppUiState.startupRoute.collectAsState()
    val pendingDeepLink by AppUiState.pendingDeepLink.collectAsState()

    // Handle deep links when the app is already on a main screen (opened while running).
    LaunchedEffect(pendingDeepLink) {
        if (pendingDeepLink != null && screen in APP_SCREENS) {
            screen = Screen.Network
            AppUiState.clearPendingDeepLink()
        }
    }

    FreepathTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // App content — respects safe area at the top
                Box(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    )
                ) {
                    // Screen content — fills full area (tab bar overlays it)
                    when (screen) {
                        Screen.Splash -> SplashScreen {
                            screen = when (startupRoute) {
                                AppUiState.StartupRoute.Onboarding -> Screen.Onboarding
                                AppUiState.StartupRoute.Network -> Screen.Network
                                else -> Screen.Nearby
                            }
                        }

                        Screen.Onboarding -> OnboardingScreen {
                            screen = Screen.Nearby
                        }

                        Screen.Nearby -> NearbyScreen()
                        Screen.Network -> NetworkScreen()
                        Screen.Me -> MeScreen()
                    }

                    // Tab bar — overlays content at the bottom
                    if (screen in APP_SCREENS) {
                        FreepathTabBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            tabs = APP_TABS,
                            activeTab = when (screen) {
                                Screen.Nearby -> 0
                                Screen.Network -> 1
                                else -> 2
                            },
                            onTabSelected = { index ->
                                screen = when (index) {
                                    0 -> Screen.Nearby
                                    1 -> Screen.Network
                                    else -> Screen.Me
                                }
                            },
                        )
                    }
                }

                // Drawers — outside the safe-area box so they cover the full screen
                ContactDrawerOverlay()
                AddContactDrawerOverlay()
                LanExchangeDrawer()
                ResetDataDrawer()
                ResetDataOverlay()
            }
        }
    }
}
