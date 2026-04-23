package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.freepath.actor.AppActor
import io.github.smyrgeorge.freepath.state.AbstractAppHooks

object AppHooks : AbstractAppHooks(
    actorKey = AppActor.DEFAULT_KEY,
    resources = AppResources,
    state = AppState,
    viewState = AppViewState,
)
