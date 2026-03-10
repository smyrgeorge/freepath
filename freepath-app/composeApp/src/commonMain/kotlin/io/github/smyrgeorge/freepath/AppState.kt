package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.freepath.state.AbstractAppState

object AppState : AbstractAppState(resources = AppResources, viewState = AppViewState)
