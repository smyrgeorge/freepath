package io.github.smyrgeorge.freepath

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import io.github.smyrgeorge.freepath.ui.components.FreepathTabBar
import io.github.smyrgeorge.freepath.ui.components.TabItem
import io.github.smyrgeorge.freepath.ui.screens.MeScreen
import io.github.smyrgeorge.freepath.ui.screens.NearbyScreen
import io.github.smyrgeorge.freepath.ui.screens.OnboardingScreen
import io.github.smyrgeorge.freepath.ui.screens.SplashScreen
import io.github.smyrgeorge.freepath.ui.theme.FreepathTheme

private enum class Screen { Splash, Onboarding, Nearby, Me }

private val APP_TABS = listOf(
    TabItem(icon = "◎", label = "Nearby", isCircle = true),
    // TabItem(icon = "☰", label = "Feed"),
    // TabItem(icon = "◈", label = "Network"),
    // TabItem(icon = "▤", label = "Library"),
    TabItem(icon = "◉", label = "Me", isCircle = true),
)

private val APP_SCREENS = setOf(Screen.Nearby, Screen.Me)

@Preview
@Composable
fun App() {
    var screen by remember { mutableStateOf(Screen.Splash) }

    FreepathTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                // Screen content
                when (screen) {
                    Screen.Splash -> SplashScreen {
                        AppState.log.info { "Identity: ${AppState.identityEntry}" }
                        AppState.log.info { "ContactCard: ${AppState.contactCardEntry}" }
                        screen = if (ContactCardEntry.TAG_ONBOARDING in AppState.contactCardEntry.tags) {
                            Screen.Onboarding
                        } else {
                            Screen.Nearby
                        }
                    }

                    Screen.Onboarding -> OnboardingScreen {
                        screen = Screen.Nearby
                    }

                    Screen.Nearby -> NearbyScreen(modifier = Modifier.weight(1f))
                    Screen.Me -> MeScreen(modifier = Modifier.weight(1f))
                }

                // Tab bar — visible only for app screens
                if (screen in APP_SCREENS) {
                    FreepathTabBar(
                        tabs = APP_TABS,
                        activeTab = when (screen) {
                            Screen.Nearby -> 0
                            else -> 1
                        },
                        onTabSelected = { index ->
                            screen = when (index) {
                                0 -> Screen.Nearby
                                else -> Screen.Me
                            }
                        },
                    )
                }
            }
        }
    }
}
