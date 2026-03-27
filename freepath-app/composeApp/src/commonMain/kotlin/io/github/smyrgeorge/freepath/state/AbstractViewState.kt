package io.github.smyrgeorge.freepath.state

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.exchange.QrCodeContactExchange
import io.github.smyrgeorge.freepath.state.model.ExchangeDrawerState
import io.github.smyrgeorge.freepath.state.model.ResetOverlayState
import io.github.smyrgeorge.freepath.state.model.StartupRoute
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

abstract class AbstractViewState {
    val log = Logger.of(this::class)

    private val _startupRoute = MutableStateFlow(StartupRoute.Loading)
    val startupRoute: StateFlow<StartupRoute> = _startupRoute.asStateFlow()

    private val _showAddContactDrawer = MutableStateFlow(false)
    val showAddContactDrawer: StateFlow<Boolean> = _showAddContactDrawer.asStateFlow()

    private val _pendingContactCard = MutableStateFlow<ContactCard?>(null)
    val pendingContactCard: StateFlow<ContactCard?> = _pendingContactCard.asStateFlow()

    private val _pendingDeepLink = MutableStateFlow<String?>(null)
    val pendingDeepLink: StateFlow<String?> = _pendingDeepLink.asStateFlow()

    private val _resetOverlay = MutableStateFlow<ResetOverlayState>(ResetOverlayState.Hidden)
    val resetOverlay: StateFlow<ResetOverlayState> = _resetOverlay.asStateFlow()

    private val _exchangeDrawer = MutableStateFlow<ExchangeDrawerState>(ExchangeDrawerState.Hidden)
    val exchangeDrawer: StateFlow<ExchangeDrawerState> = _exchangeDrawer.asStateFlow()

    private val _showResetDataConfirmation = MutableStateFlow(false)
    val showResetDataConfirmation: StateFlow<Boolean> = _showResetDataConfirmation.asStateFlow()

    private val _showDeleteContentConfirmation = MutableStateFlow(false)
    val showDeleteContentConfirmation: StateFlow<Boolean> = _showDeleteContentConfirmation.asStateFlow()

    fun setStartupRoute(route: StartupRoute) {
        _startupRoute.value = route
    }

    fun openAddContactDrawer() {
        _showAddContactDrawer.value = true
    }

    fun closeAddContactDrawer() {
        _showAddContactDrawer.value = false
    }

    fun showContactCard(card: ContactCard) {
        _pendingContactCard.value = card
    }

    fun clearPendingContactCard() {
        _pendingContactCard.value = null
    }

    fun clearPendingDeepLink() {
        _pendingDeepLink.value = null
    }

    fun handleDeepLink(url: String) {
        log.info { "Deep link received: $url" }
        _pendingDeepLink.value = url
        _pendingContactCard.value = QrCodeContactExchange.decode(url).getOrNull()
    }

    fun showRequestorEnterPin(peripheralId: String) {
        _exchangeDrawer.value = ExchangeDrawerState.RequestorEnterPin(peripheralId)
    }

    fun showResponderWaiting(pin: String) {
        _exchangeDrawer.value = ExchangeDrawerState.ResponderWaiting(pin)
    }

    fun exchangeFailed(reason: String) {
        _exchangeDrawer.value = ExchangeDrawerState.Failed(reason)
    }

    fun hideExchangeDrawer() {
        _exchangeDrawer.value = ExchangeDrawerState.Hidden
    }

    fun showResetDataConfirmation() {
        _showResetDataConfirmation.value = true
    }

    fun hideResetDataConfirmation() {
        _showResetDataConfirmation.value = false
    }

    fun showDeleteContentConfirmation() {
        _showDeleteContentConfirmation.value = true
    }

    fun hideDeleteContentConfirmation() {
        _showDeleteContentConfirmation.value = false
    }

    fun showResetClearing() {
        _resetOverlay.value = ResetOverlayState.Clearing
    }

    fun showResetCleared() {
        _resetOverlay.value = ResetOverlayState.Cleared
    }

    fun showResetFailed() {
        _resetOverlay.value = ResetOverlayState.Failed
    }
}
