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
}
