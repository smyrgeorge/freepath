package io.github.smyrgeorge.freepath.core.state.model

sealed class ResetOverlayState {
    data object Hidden : ResetOverlayState()
    data object Clearing : ResetOverlayState()
    data object Cleared : ResetOverlayState()
    data object Failed : ResetOverlayState()
}
