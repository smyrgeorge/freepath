package io.github.smyrgeorge.freepath

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import java.awt.Desktop.Action.APP_OPEN_URI
import kotlin.concurrent.thread

fun main() {
    AppHooks.onCreate()
    Runtime.getRuntime().addShutdownHook(thread(start = false) { AppHooks.onDestroy() })

    if (Desktop.isDesktopSupported()
        && Desktop.getDesktop().isSupported(APP_OPEN_URI)
    ) {
        Desktop.getDesktop().setOpenURIHandler { e ->
            AppUiState.handleDeepLink(e.uri.toString())
        }
    }

    application {
        val windowState = rememberWindowState(
            size = DpSize(390.dp, 720.dp)
        )

        Window(
            onCloseRequest = ::exitApplication,
            title = "freepath",
            state = windowState,
        ) {
            App()
        }
    }
}