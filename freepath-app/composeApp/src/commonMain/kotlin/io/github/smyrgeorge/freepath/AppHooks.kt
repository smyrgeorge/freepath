package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.freepath.core.actor.AppActor
import io.github.smyrgeorge.freepath.core.state.AbstractAppHooks

object AppHooks : AbstractAppHooks(
    actorKey = AppActor.DEFAULT_KEY,
    resources = AppResources,
    state = AppState,
    viewState = AppViewState,
)
