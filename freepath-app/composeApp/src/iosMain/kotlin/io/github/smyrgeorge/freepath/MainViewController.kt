package io.github.smyrgeorge.freepath

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillTerminateNotification
import platform.UIKit.UIViewController

fun handleDeepLink(url: String) = AppUiState.handleDeepLink(url)

@Suppress("FunctionName")
fun MainViewController(): UIViewController {
    AppHooks.onCreate()
    return ComposeUIViewController { App() }.also {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationWillTerminateNotification, null, null) { AppHooks.onDestroy() }
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, null) { AppHooks.onStop() }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, null) { AppHooks.onStart() }
    }
}
