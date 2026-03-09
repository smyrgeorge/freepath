package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.exchange.QrCodeContactExchange
import io.github.smyrgeorge.log4k.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppUiState {
    private val log = Logger.of(this::class)

    enum class StartupRoute { Loading, Onboarding, Nearby, Network }

    private val _startupRoute = MutableStateFlow(StartupRoute.Loading)
    val startupRoute: StateFlow<StartupRoute> = _startupRoute.asStateFlow()

    private val _showAddContactDrawer = MutableStateFlow(false)
    val showAddContactDrawer: StateFlow<Boolean> = _showAddContactDrawer.asStateFlow()

    private val _pendingContactCard = MutableStateFlow<ContactCard?>(null)
    val pendingContactCard: StateFlow<ContactCard?> = _pendingContactCard.asStateFlow()

    private val _pendingDeepLink = MutableStateFlow<String?>(null)
    val pendingDeepLink: StateFlow<String?> = _pendingDeepLink.asStateFlow()

    // LAN exchange drawer state
    sealed class ExchangeDrawerState {
        data object Hidden : ExchangeDrawerState()
        /** Shown on requestor's device: display the generated PIN, waiting for peer response */
        data class RequestorWaiting(val pin: String, val peerNodeId: String) : ExchangeDrawerState()
        /** Shown on recipient's device: enter the PIN shown on the requestor's screen */
        data class RecipientEnterPin(val peerCardBytes: ByteArray) : ExchangeDrawerState()
        /** Exchange failed, show error */
        data class Failed(val reason: String) : ExchangeDrawerState()
    }

    private val _exchangeDrawer = MutableStateFlow<ExchangeDrawerState>(ExchangeDrawerState.Hidden)
    val exchangeDrawer: StateFlow<ExchangeDrawerState> = _exchangeDrawer.asStateFlow()

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

    fun showRequestorDrawer(pin: String, peerNodeId: String) {
        _exchangeDrawer.value = ExchangeDrawerState.RequestorWaiting(pin, peerNodeId)
    }

    fun showRecipientDrawer(peerCardBytes: ByteArray) {
        _exchangeDrawer.value = ExchangeDrawerState.RecipientEnterPin(peerCardBytes)
    }

    fun exchangeFailed(reason: String) {
        _exchangeDrawer.value = ExchangeDrawerState.Failed(reason)
    }

    fun hideExchangeDrawer() {
        _exchangeDrawer.value = ExchangeDrawerState.Hidden
    }
}
