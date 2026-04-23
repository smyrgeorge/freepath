package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.freepath.core.state.AbstractAppState

object AppState : AbstractAppState(resources = AppResources, viewState = AppViewState)
