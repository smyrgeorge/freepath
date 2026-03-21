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
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import io.github.smyrgeorge.freepath.database.ContentEntry
import io.github.smyrgeorge.freepath.state.model.StartupRoute
import io.github.smyrgeorge.freepath.ui.components.DeleteContentDrawer
import io.github.smyrgeorge.freepath.ui.components.FreepathTabBar
import io.github.smyrgeorge.freepath.ui.components.LanExchangeDrawer
import io.github.smyrgeorge.freepath.ui.components.ResetDataDrawer
import io.github.smyrgeorge.freepath.ui.components.ResetDataOverlay
import io.github.smyrgeorge.freepath.ui.components.TabItem
import io.github.smyrgeorge.freepath.ui.screens.AddContactDrawerOverlay
import io.github.smyrgeorge.freepath.ui.screens.ChatScreen
import io.github.smyrgeorge.freepath.ui.screens.ContactDrawerOverlay
import io.github.smyrgeorge.freepath.ui.screens.FeedScreen
import io.github.smyrgeorge.freepath.ui.screens.MeScreen
import io.github.smyrgeorge.freepath.ui.screens.NearbyScreen
import io.github.smyrgeorge.freepath.ui.screens.NetworkScreen
import io.github.smyrgeorge.freepath.ui.screens.OnboardingScreen
import io.github.smyrgeorge.freepath.ui.screens.PostDetailScreen
import io.github.smyrgeorge.freepath.ui.screens.SplashScreen
import io.github.smyrgeorge.freepath.ui.theme.FreepathTheme

private enum class Screen { Splash, Onboarding, Nearby, Network, Feed, Me, Chat, PostDetail }

private val APP_TABS = listOf(
    TabItem(icon = "☰", label = "Feed"),
    TabItem(icon = "◎", label = "Nearby", isCircle = true),
    TabItem(icon = "◈", label = "Network"),
    // TabItem(icon = "▤", label = "Library"),
    TabItem(icon = "◉", label = "Me", isCircle = true),
)

private val APP_SCREENS = setOf(Screen.Nearby, Screen.Network, Screen.Feed, Screen.Me)

@Preview
@Composable
fun App() {
    var screen by remember { mutableStateOf(Screen.Splash) }
    var chatContact by remember { mutableStateOf<ContactCardEntry?>(null) }
    var selectedFeedEntry by remember { mutableStateOf<ContentEntry?>(null) }
    val startupRoute by AppViewState.startupRoute.collectAsState()
    val pendingDeepLink by AppViewState.pendingDeepLink.collectAsState()

    // Handle deep links when the app is already on a main screen (opened while running).
    LaunchedEffect(pendingDeepLink) {
        if (pendingDeepLink != null && screen in APP_SCREENS) {
            screen = Screen.Network
            AppViewState.clearPendingDeepLink()
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
                                StartupRoute.Onboarding -> Screen.Onboarding
                                StartupRoute.Network -> Screen.Network
                                else -> Screen.Feed
                            }
                        }

                        Screen.Onboarding -> OnboardingScreen {
                            screen = Screen.Feed
                        }

                        Screen.Nearby -> NearbyScreen()
                        Screen.Network -> NetworkScreen(
                            onContactClick = { entry ->
                                chatContact = entry
                                screen = Screen.Chat
                            }
                        )

                        Screen.Me -> MeScreen()
                        Screen.Feed -> FeedScreen(
                            onPostClick = { entry ->
                                selectedFeedEntry = entry
                                screen = Screen.PostDetail
                            }
                        )

                        Screen.PostDetail -> selectedFeedEntry?.let { entry ->
                            PostDetailScreen(
                                entry = entry,
                                onBack = {
                                    selectedFeedEntry = null
                                    screen = Screen.Feed
                                },
                            )
                        }

                        Screen.Chat -> chatContact?.let { entry ->
                            ChatScreen(
                                contact = entry,
                                onBack = { screen = Screen.Network },
                            )
                        }
                    }

                    // Tab bar — overlays content at the bottom
                    if (screen in APP_SCREENS) {
                        FreepathTabBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            tabs = APP_TABS,
                            activeTab = when (screen) {
                                Screen.Feed -> 0
                                Screen.Nearby -> 1
                                Screen.Network -> 2
                                else -> 3  // Me (PostDetail is not in APP_SCREENS so tab bar hidden)
                            },
                            onTabSelected = { index ->
                                screen = when (index) {
                                    0 -> Screen.Feed
                                    1 -> Screen.Nearby
                                    2 -> Screen.Network
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
                DeleteContentDrawer()
                ResetDataDrawer()
                ResetDataOverlay()
            }
        }
    }
}
