package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.freepath.core.state.model.ExchangeDrawerState
import io.github.smyrgeorge.freepath.core.testing.util.awaitUntil
import io.github.smyrgeorge.freepath.core.testing.util.clusterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactExchangeActorTest {

    @Test
    fun `responds to Ping with Pong`() = clusterTest { cluster ->
        val node = cluster.nodes.first()
        assertEquals(
            ContactExchangeProtocol.Pong,
            node.contactExchangeRef.ask(ContactExchangeProtocol.Ping).getOrThrow(),
        )
    }

    @Test
    fun `Initiate shows the requestor enter-pin drawer`() = clusterTest { cluster ->
        val node = cluster.nodes.first()
        node.contactExchangeRef.ask(ContactExchangeProtocol.Initiate("peripheral-1")).getOrThrow()
        awaitUntil {
            val s = node.viewState.exchangeDrawer.value
            s is ExchangeDrawerState.RequestorEnterPin && s.peripheralId == "peripheral-1"
        }
    }

    @Test
    fun `Cancelled hides the exchange drawer`() = clusterTest { cluster ->
        val node = cluster.nodes.first()
        node.contactExchangeRef.ask(ContactExchangeProtocol.Initiate("peripheral-1")).getOrThrow()
        awaitUntil { node.viewState.exchangeDrawer.value is ExchangeDrawerState.RequestorEnterPin }

        node.contactExchangeRef.ask(ContactExchangeProtocol.Cancelled).getOrThrow()
        awaitUntil { node.viewState.exchangeDrawer.value == ExchangeDrawerState.Hidden }
    }

    @Test
    fun `Failed maps the reason to a friendly drawer message`() = clusterTest { cluster ->
        val node = cluster.nodes.first()
        // "disconnect" → the connection-lost copy (see ContactExchangeActor.friendlyBleError).
        node.contactExchangeRef.ask(ContactExchangeProtocol.Failed("peer disconnected")).getOrThrow()
        awaitUntil {
            val s = node.viewState.exchangeDrawer.value
            s is ExchangeDrawerState.Failed && s.reason.contains("Connection lost")
        }
    }
}
