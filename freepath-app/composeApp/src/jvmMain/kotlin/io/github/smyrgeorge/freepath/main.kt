package io.github.smyrgeorge.freepath

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
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