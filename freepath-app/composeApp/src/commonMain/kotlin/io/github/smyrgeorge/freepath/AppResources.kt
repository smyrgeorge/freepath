package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.freepath.core.state.AbstractAppResources
import io.github.smyrgeorge.freepath.libble.LibbleModuleImplDisabled
import io.github.smyrgeorge.freepath.libp2p.Libp2pModule

object AppResources : AbstractAppResources(
    database = "freepath.db",
    libp2pModule = Libp2pModule(),
    libbleModule = LibbleModuleImplDisabled(),
)
