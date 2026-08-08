package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.Actor
import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.actor4k.util.extentions.launch
import io.github.smyrgeorge.freepath.core.state.AbstractAppResources
import io.github.smyrgeorge.freepath.core.state.AbstractAppState
import io.github.smyrgeorge.freepath.core.state.AbstractViewState
import io.github.smyrgeorge.freepath.util.doEvery
import kotlinx.coroutines.Job
import kotlin.time.Duration.Companion.seconds

class ContactExchangeActor(
    key: String,
    private val state: AbstractAppState,
    private val viewState: AbstractViewState,
    private val resources: AbstractAppResources,
) : Actor<ContactExchangeProtocol, ContactExchangeProtocol.Response>(key) {
    /** Tracks the currently running exchange coroutine so it can be cancelled on dismiss. */
    private var exchangeJob: Job? = null

    private val timer: Job = doEvery(5.seconds) {
        // Keep the actor alive.
        tell(ContactExchangeProtocol.Ping).getOrElse { log.error("Failed to ping: ${it.message}") }
    }

    override suspend fun onShutdown() {
        log.info("[onShutdown] Shutting down...")
        timer.cancel()
        exchangeJob?.cancel()
    }

    override suspend fun onReceive(m: ContactExchangeProtocol): Behavior<ContactExchangeProtocol.Response> {
        when (m) {
            is ContactExchangeProtocol.Ping -> return Behavior.Reply(ContactExchangeProtocol.Pong)

            is ContactExchangeProtocol.Initiate -> {
                viewState.showRequestorEnterPin(m.peripheralId)
            }

            is ContactExchangeProtocol.InitiateResponder -> {
                val pin = (0 until 4).map { ('0'..'9').random() }.joinToString("")
                viewState.showResponderWaiting(pin)
                exchangeJob = launch {
                    resources.libble.beginResponderExchange(
                        pin = pin,
                        localContact = state.contact,
                        sigKeyPrivate = state.identity.sigKeyPrivate,
                    ).onSuccess { r ->
                        val cmd = ContactExchangeProtocol.Succeeded(r.contact, r.peripheralId, r.identitySecret)
                        tell(cmd).getOrThrow()
                    }.onFailure { e ->
                        tell(ContactExchangeProtocol.Failed(e.message ?: "BLE exchange failed")).getOrThrow()
                    }
                }
            }

            is ContactExchangeProtocol.BeginInitiator -> {
                viewState.hideExchangeDrawer()
                exchangeJob = launch {
                    resources.libble.beginInitiatorExchange(
                        peripheralId = m.peripheralId,
                        pin = m.pin,
                        localContact = state.contact,
                        sigKeyPrivate = state.identity.sigKeyPrivate,
                    ).onSuccess { r ->
                        val cmd = ContactExchangeProtocol.Succeeded(r.contact, r.peripheralId, r.identitySecret)
                        tell(cmd).getOrThrow()
                    }.onFailure { e ->
                        val cmd = ContactExchangeProtocol.Failed(e.message ?: "BLE exchange failed")
                        tell(cmd).getOrThrow()
                    }
                }
            }

            is ContactExchangeProtocol.Cancelled -> {
                exchangeJob?.cancel()
                exchangeJob = null
                viewState.hideExchangeDrawer()
            }

            is ContactExchangeProtocol.Succeeded -> {
                exchangeJob = null
                state.acceptBleContact(m.contact, m.identitySecret)
                viewState.hideExchangeDrawer()
            }

            is ContactExchangeProtocol.Failed -> {
                log.warn("Exchange failed: ${m.reason}")
                viewState.exchangeFailed(friendlyBleError(m.reason))
            }
        }
        return Behavior.Reply(ContactExchangeProtocol.Ok)
    }

    private fun friendlyBleError(reason: String): String = when {
        //@formatter:off
        reason.contains("disconnect", ignoreCase = true) -> "Connection lost. Make sure both devices are nearby and try again."
        reason.contains("timeout", ignoreCase = true) -> "Exchange timed out. Make sure both devices are ready and try again."
        reason.contains("PIN", ignoreCase = true) -> "PIN confirmation failed. Check that both devices entered the same PIN."
        reason.contains("mismatch", ignoreCase = true) -> "PIN confirmation failed. Check that both devices entered the same PIN."
        else -> "Exchange failed. Please try again."
        //@formatter:on
    }

    companion object {
        const val DEFAULT_KEY = "system"
    }
}
