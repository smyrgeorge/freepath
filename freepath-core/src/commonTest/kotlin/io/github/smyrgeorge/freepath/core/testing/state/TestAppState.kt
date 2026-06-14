package io.github.smyrgeorge.freepath.core.testing.state

import io.github.smyrgeorge.freepath.core.state.AbstractAppResources
import io.github.smyrgeorge.freepath.core.state.AbstractAppState
import io.github.smyrgeorge.freepath.core.state.AbstractViewState

class TestAppState(
    resources: AbstractAppResources,
    viewState: AbstractViewState,
) : AbstractAppState(resources, viewState)
