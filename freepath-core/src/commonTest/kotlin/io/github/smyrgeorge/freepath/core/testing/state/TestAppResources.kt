package io.github.smyrgeorge.freepath.core.testing.state

import io.github.smyrgeorge.freepath.core.state.AbstractAppResources
import io.github.smyrgeorge.freepath.core.testing.fake.FakeLibbleModule
import io.github.smyrgeorge.freepath.core.testing.fake.FakeLibp2pModule
import io.github.smyrgeorge.freepath.core.testing.fake.FakeNetwork

class TestAppResources(
    network: FakeNetwork,
) : AbstractAppResources(
    database = ":memory:",
    libp2pModule = FakeLibp2pModule(network),
    libbleModule = FakeLibbleModule(),
)
