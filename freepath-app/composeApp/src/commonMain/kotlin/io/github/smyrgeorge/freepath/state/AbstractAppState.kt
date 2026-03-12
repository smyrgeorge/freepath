package io.github.smyrgeorge.freepath.state

import io.github.smyrgeorge.freepath.client.model.ContactInfo
import io.github.smyrgeorge.freepath.client.model.ChatMessage
import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.ContactCardCodec
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.crypto.KeyPair
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import io.github.smyrgeorge.freepath.database.ContactCardEntryRepository
import io.github.smyrgeorge.freepath.database.IdentityEntry
import io.github.smyrgeorge.freepath.database.IdentityEntryRepository
import io.github.smyrgeorge.freepath.state.model.ConnectionSource
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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

    private val _contacts = MutableStateFlow<List<ContactCardEntry>>(emptyList())
    val contacts: StateFlow<List<ContactCardEntry>> = _contacts.asStateFlow()

    private val _chats = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val chats: StateFlow<Map<String, List<ChatMessage>>> = _chats.asStateFlow()

    val nearbyPeers: StateFlow<Map<String, ConnectionSource>> =
        resources.libp2p.metrics.value.map {
            it.mdnsPeers
                .filterKeys { peerId -> peerId != identityEntry.nodeId }
                .mapValues { ConnectionSource.LAN }
        }.stateIn(emptyMap())

    val connectedKnownPeers: StateFlow<Map<String, ConnectionSource>> =
        resources.libp2p.metrics.value.map {
            it.identifiedPeers
                .filter { peerId -> peerId != identityEntry.nodeId }
                .associateWith { ConnectionSource.LAN }
        }.stateIn(emptyMap())

    lateinit var identity: Identity
    lateinit var identityEntry: IdentityEntry
    lateinit var contactCard: ContactCard
    lateinit var contactCardEntry: ContactCardEntry

    // Contact exchange state — only valid during an active exchange
    var contactExchangeIncomingPin: String? = null
    var contactExchangeIncomingPeerId: String? = null
    var contactExchangeIncomingPeerCard: ContactCard? = null

    suspend fun initialize() {
        loadIdentity()
        loadOwnContactCard()
        loadContacts()
    }

    suspend fun acceptContact(card: ContactCard) {
        val existing = contactCardRepository.findOneByNodeId(db, card.nodeId).getOrThrow()
        if (existing == null) {
            val entry = ContactCardEntry(nodeId = card.nodeId, card = card)
            contactCardRepository.insert(db, entry).getOrThrow()
        } else {
            val entry = existing.merge(ContactCardEntry(nodeId = card.nodeId, card = card))
            contactCardRepository.update(db, entry).getOrThrow()
        }
        loadContacts()
    }

    suspend fun setTrustLevel(entry: ContactCardEntry, level: TrustLevel) {
        val updated = entry.copy(trustLevel = level, updatedAt = Clock.System.now())
        contactCardRepository.update(db, updated).getOrThrow()
        loadContacts()
    }

    suspend fun loadContacts() {
        val ownNodeId = contactCardEntry.nodeId
        _contacts.value = contactCardRepository.findAll(db).getOrThrow().filter { it.nodeId != ownNodeId }
    }

    fun initiateContactExchange(peerId: String): String {
        val pin = (0 until 4).map { ('0'..'9').random() }.joinToString("")
        viewState.showRequestorDrawer(pin, peerId)
        log.info("[exchange] Exchange initiated with $peerId, PIN $pin")
        return pin
    }

    fun resetContactExchange() {
        contactExchangeIncomingPin = null
        contactExchangeIncomingPeerId = null
        contactExchangeIncomingPeerCard = null
    }

    fun cancelContactExchange() {
        resetContactExchange()
        viewState.hideExchangeDrawer()
    }

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

    fun appendMessage(message: ChatMessage) {
        // The chat map is keyed by the remote peer's node ID so the UI can look up
        // messages with chats[contact.nodeId]. Use whichever side is not us.
        val conversationKey =
            if (message.senderId == contactCard.nodeId) message.receiverId
            else message.senderId
        _chats.update { current ->
            current + (conversationKey to (current[conversationKey] ?: emptyList()) + message)
        }
    }

    fun contactLookup(peerIdRaw: ByteArray): ContactInfo? {
        return contacts.value.firstOrNull {
            val peerId = CryptoProvider.sha256(Base64.decode(it.card.sigKey))
            peerId.contentEquals(peerIdRaw)
        }?.let { entry ->
            ContactInfo(
                sigKeyPublic = Base64.decode(entry.card.sigKey),
                encKeyPublic = Base64.decode(entry.card.encKey),
            )
        }
    }

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
        val nodeIdRaw = CryptoProvider.sha256(sigKeyPair.publicKey)
        val nodeId = ContactCardCodec.deriveNodeId(sigKeyPair.publicKey)

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
