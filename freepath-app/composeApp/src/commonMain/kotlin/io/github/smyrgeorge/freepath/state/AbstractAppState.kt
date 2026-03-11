package io.github.smyrgeorge.freepath.state

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.contact.exchange.ContactExchange
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.crypto.KeyPair
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import io.github.smyrgeorge.freepath.database.ContactCardEntryRepository
import io.github.smyrgeorge.freepath.database.IdentityEntry
import io.github.smyrgeorge.freepath.database.IdentityEntryRepository
import io.github.smyrgeorge.freepath.state.model.DiscoveredPeer
import io.github.smyrgeorge.freepath.util.codec.Base58
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.impl.extensions.launch
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.io.encoding.Base64
import kotlin.time.Clock

abstract class AbstractAppState(
    resources: AbstractAppResources,
    private val viewState: AbstractViewState,
) {
    val log: Logger = Logger.of(this::class)

    private val db: ISQLite by lazy { resources.db }
    private val identityRepository: IdentityEntryRepository = resources.identityRepository
    private val contactCardRepository: ContactCardEntryRepository = resources.contactCardRepository

    private val _discoveredPeers = MutableStateFlow<Map<String, DiscoveredPeer>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _contacts = MutableStateFlow<List<ContactCardEntry>>(emptyList())
    val contacts: StateFlow<List<ContactCardEntry>> = _contacts.asStateFlow()

    lateinit var identity: Identity
    lateinit var identityEntry: IdentityEntry
    lateinit var contactCard: ContactCard
    lateinit var contactCardEntry: ContactCardEntry

    // Contact exchange state — only valid during an active exchange
    var contactExchangeJob: Job? = null
    var contactExchangeIncomingDeferred: CompletableDeferred<ByteArray?>? = null
    var contactExchangeIncomingPin: String? = null
    var contactExchangeIncomingCodec: ContactExchange? = null
    var contactExchangeIncomingPeerCard: ContactCard? = null

    // Called only by AppActor
    suspend fun initialize() {
        loadIdentity()
        loadOwnContactCard()
        loadContacts()
    }

    // Called only by AppActor
    suspend fun acceptContact(card: ContactCard) {
        val existing = contactCardRepository.findOneByNodeId(db, card.nodeId).getOrThrow()
        if (existing == null) {
            val entry = ContactCardEntry(nodeId = card.nodeId, card = card)
            contactCardRepository.insert(db, entry).getOrThrow()
        } else {
            val entry = existing.merge(ContactCardEntry(nodeId = card.nodeId, card = card))
            contactCardRepository.update(db, entry).getOrThrow()
        }
        markPeerKnown(card.nodeId)
        loadContacts()
    }

    // Called only by AppActor
    suspend fun setTrustLevel(entry: ContactCardEntry, level: TrustLevel) {
        val updated = entry.copy(trustLevel = level, updatedAt = Clock.System.now())
        contactCardRepository.update(db, updated).getOrThrow()
        loadContacts()
    }

    // Called only by AppActor
    suspend fun loadContacts() {
        val ownNodeId = contactCardEntry.nodeId
        _contacts.value = contactCardRepository.findAll(db).getOrThrow().filter { it.nodeId != ownNodeId }
    }

    // Called only by AppActor
    fun peerDiscovered(nodeId: String) {
        _discoveredPeers.update { peers ->
            if (nodeId !in peers) peers + (nodeId to DiscoveredPeer(nodeId = nodeId, isKnown = false))
            else peers
        }
    }

    // Called only by AppActor
    fun peerConnected(nodeId: String) {
        _discoveredPeers.update { peers -> peers + (nodeId to DiscoveredPeer(nodeId = nodeId, isKnown = true)) }
    }

    // Called only by AppActor
    fun peerDisconnected(nodeId: String) {
        _discoveredPeers.update { peers -> peers - nodeId }
    }

    // Called only by AppActor — marks accepted contact as known in the live peer map
    fun markPeerKnown(nodeId: String) {
        _discoveredPeers.update { peers ->
            peers[nodeId]?.let { peer -> peers + (nodeId to peer.copy(isKnown = true)) } ?: peers
        }
    }

    // Called only by AppActor
    fun initiateContactExchange(
        nodeId: String,
        exchanger: suspend (pin: String) -> Result<ContactCard>,
        onResult: suspend (Result<ContactCard>) -> Unit,
    ) {
        val pin = (0 until 4).map { ('0'..'9').random() }.joinToString("")
        viewState.showRequestorDrawer(pin, nodeId)
        log.info("[exchange] Exchange initiated with $nodeId, PIN $pin")
        contactExchangeJob = launch {
            val result = exchanger(pin)
            contactExchangeJob = null
            onResult(result)
        }
    }

    // Called only by AppActor
    fun resetContactExchange() {
        val job = contactExchangeJob
        val deferred = contactExchangeIncomingDeferred
        contactExchangeJob = null
        contactExchangeIncomingDeferred = null
        contactExchangeIncomingPin = null
        contactExchangeIncomingCodec = null
        contactExchangeIncomingPeerCard = null
        job?.cancel()
        deferred?.complete(null)
    }

    // Called only by AppActor
    fun cancelContactExchange() {
        resetContactExchange()
        viewState.hideExchangeDrawer()
    }

    // Called only by AppActor
    fun handleContactExchangeSuccess(peerCard: ContactCard) {
        viewState.hideExchangeDrawer()
        viewState.showContactCard(peerCard)
    }

    // Called only by AppActor
    fun handleContactExchangeFailure(reason: String) {
        log.info("[exchange] Exchange failed: $reason")
        viewState.exchangeFailed(reason)
    }

    suspend fun completeOnboarding(name: String?, bio: String?, location: String?) {
        val updatedCard = contactCard.copy(
            name = name?.takeIf { it.isNotBlank() },
            bio = bio?.takeIf { it.isNotBlank() },
            location = location?.takeIf { it.isNotBlank() },
            updatedAt = Clock.System.now(),
        )
        val updatedEntry = contactCardEntry.copy(
            card = updatedCard,
            tags = contactCardEntry.tags - ContactCardEntry.TAG_ONBOARDING,
        )
        contactCardEntry = contactCardRepository.update(db, updatedEntry).getOrThrow()
        contactCard = contactCardEntry.card
    }

    // Called only by AppActor — dev/testing only
    suspend fun resetData(): Boolean {
        viewState.showResetClearing()
        return runCatching {
            db.transaction {
                contactCardRepository.deleteAll(this).getOrThrow()
                identityRepository.deleteAll(this).getOrThrow()
            }
        }.onSuccess {
            log.info("[dev] All data deleted.")
            viewState.showResetCleared()
        }.onFailure {
            log.error("[dev] Failed to delete data: $it")
            viewState.showResetFailed()
        }.isSuccess
    }

    private suspend fun loadIdentity() {
        val existing = identityRepository.findAll(db).getOrThrow()
        require(existing.size <= 1) { "Expected at most one identity entry, got $existing" }
        val entry = existing.firstOrNull() ?: createAndSaveIdentity()
        identity = entry.data
        identityEntry = entry
    }

    private suspend fun loadOwnContactCard() {
        val nodeId = identityEntry.nodeId
        val existing = contactCardRepository.findOneByNodeId(db, nodeId).getOrThrow()
        if (existing != null) {
            contactCard = existing.card
            contactCardEntry = existing
            return
        }

        val card = ContactCard(
            schema = ContactCard.SCHEMA,
            nodeId = nodeId,
            sigKey = Base64.encode(identity.sigKeyPublic),
            encKey = Base64.encode(identity.encKeyPublic),
            updatedAt = Clock.System.now(),
            name = "#$nodeId",
        )

        contactCard = card
        val entry = ContactCardEntry(nodeId = nodeId, card = card, tags = listOf(ContactCardEntry.TAG_ONBOARDING))
        contactCardEntry = contactCardRepository.insert(db, entry).getOrThrow()
    }

    private suspend fun createAndSaveIdentity(): IdentityEntry {
        val sigKeyPair: KeyPair = CryptoProvider.generateEd25519KeyPair()
        val encKeyPair: KeyPair = CryptoProvider.generateX25519KeyPair()
        val nodeIdRaw = CryptoProvider.sha256(sigKeyPair.publicKey).copyOf(16)
        val nodeId = Base58.encode(nodeIdRaw)

        val identity = Identity(
            nodeIdRaw = nodeIdRaw,
            sigKeyPublic = sigKeyPair.publicKey,
            sigKeyPrivate = sigKeyPair.privateKey,
            encKeyPublic = encKeyPair.publicKey,
            encKeyPrivate = encKeyPair.privateKey,
        )

        val entry = IdentityEntry(nodeId = nodeId, data = identity)
        return identityRepository.insert(db, entry).getOrThrow()
    }
}
