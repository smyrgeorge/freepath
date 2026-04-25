package io.github.smyrgeorge.freepath.core.state

import io.github.smyrgeorge.freepath.core.actor.AppProtocol
import io.github.smyrgeorge.freepath.core.state.model.ConnectionSource
import io.github.smyrgeorge.freepath.core.state.service.ContactService
import io.github.smyrgeorge.freepath.core.state.service.ContentService
import io.github.smyrgeorge.freepath.core.state.service.IdentityService
import io.github.smyrgeorge.freepath.core.state.service.MessageService
import io.github.smyrgeorge.freepath.core.state.service.RelayService
import io.github.smyrgeorge.freepath.database.ContactEntry
import io.github.smyrgeorge.freepath.database.ContentEntry
import io.github.smyrgeorge.freepath.database.IdentityEntry
import io.github.smyrgeorge.freepath.database.MessageEntry
import io.github.smyrgeorge.freepath.database.MessageStatus
import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.model.contact.Identity
import io.github.smyrgeorge.freepath.model.contact.TrustLevel
import io.github.smyrgeorge.freepath.model.content.Content
import io.github.smyrgeorge.freepath.model.content.ContentBody
import io.github.smyrgeorge.freepath.model.content.Message
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

abstract class AbstractAppState(
    resources: AbstractAppResources,
    private val viewState: AbstractViewState,
) {
    val log: Logger = Logger.of(this::class)

    private val db: ISQLite by lazy { resources.db }

    private val contactService: ContactService by lazy { resources.contactService }
    private val contentService: ContentService by lazy { resources.contentService }
    private val identityService: IdentityService by lazy { resources.identityService }
    private val messageService: MessageService by lazy { resources.messageService }
    private val relayService: RelayService by lazy { resources.relayService }

    private val _contacts = MutableStateFlow<List<ContactEntry>>(emptyList())
    val contacts: StateFlow<List<ContactEntry>> = _contacts.asStateFlow()

    private val _contactContents = MutableStateFlow<Map<String, ContentBody.Contact>>(emptyMap())
    val contactContents: StateFlow<Map<String, ContentBody.Contact>> = _contactContents.asStateFlow()

    private val _chats = MutableStateFlow<Map<String, List<MessageEntry>>>(emptyMap())
    val chats: StateFlow<Map<String, List<MessageEntry>>> = _chats.asStateFlow()

    private val _feedEntries = MutableStateFlow<List<ContentEntry>>(emptyList())
    val feedEntries: StateFlow<List<ContentEntry>> = _feedEntries.asStateFlow()

    /**
     * Peers physically nearby, keyed by peerId. Value is the set of local transports
     * ([ConnectionSource]) on which the peer is currently visible. A peer reachable on both
     * LAN and BLE appears once with both sources in the set. A LAN peer counts as nearby only
     * if it is also visible on mDNS, so peers reachable only via relay/internet are excluded.
     */
    val nearbyPeers: StateFlow<Map<String, Set<ConnectionSource>>> =
        combine(
            resources.libp2p.metrics.value,
            resources.libble.metrics.value,
        ) { lan, ble ->
            val selfPeerId = if (::identityEntry.isInitialized) identityEntry.peerId else null
            val result = mutableMapOf<String, MutableSet<ConnectionSource>>()
            lan.identifiedPeers
                .filter { it != selfPeerId && it in lan.mdnsPeers }
                .forEach { result.getOrPut(it) { mutableSetOf() }.add(ConnectionSource.LAN) }
            ble.identifiedPeers
                .filter { it != selfPeerId }
                .forEach { result.getOrPut(it) { mutableSetOf() }.add(ConnectionSource.BLE) }
            result.mapValues { it.value.toSet() }
        }.stateIn(emptyMap())

    /**
     * Peers reachable on any transport, including internet/relay. Used for online indicators
     * that should light up regardless of whether the peer is physically nearby.
     */
    val onlinePeers: StateFlow<Set<String>> =
        combine(
            resources.libp2p.metrics.value,
            resources.libble.metrics.value,
        ) { lan, ble ->
            val all = lan.identifiedPeers + ble.identifiedPeers
            if (::identityEntry.isInitialized) all - identityEntry.peerId else all
        }.stateIn(emptySet())

    lateinit var identity: Identity
    lateinit var identityEntry: IdentityEntry
    lateinit var contact: Contact
    lateinit var contactEntry: ContactEntry
    lateinit var contactContentBody: ContentBody.Contact
    lateinit var contactContent: Content
    lateinit var contactContentEntry: ContentEntry

    suspend fun initialize() {
        loadOwnIdentity()
        loadOwnContact()
        loadContacts()
    }

    suspend fun acceptContact(contact: Contact) = acceptContact(db, contact)
    suspend fun acceptContact(db: QueryExecutor, contact: Contact) {
        contactService.save(db, contact)
        loadContacts(db)
    }

    suspend fun acceptContact(res: AppProtocol.BleContactExchangeSucceeded) {
        contactService.saveBleContactExchange(res.contact, res.identitySecret)
        loadContacts()
    }

    suspend fun setTrustLevel(entry: ContactEntry, level: TrustLevel) {
        contactService.setTrustLevel(entry, level)
        loadContacts()
    }

    suspend fun loadContacts() = loadContacts(db)
    suspend fun loadContacts(db: QueryExecutor) {
        val contacts = contactService.getContacts(db)
        _contacts.value = contacts
        _contactContents.value = contacts.associate { entry ->
            entry.peerId to contentService.getContactContentBody(entry.peerId)
        }
    }

    suspend fun loadFeed(limit: Int = 50, offset: Int = 0) {
        contentService.getFeed(limit, offset).also {
            val feed = it.filter { c -> c.authorId != identityEntry.peerId }
            _feedEntries.value = feed
        }
    }

    fun cancelContactExchange() {
        viewState.hideExchangeDrawer()
    }

    suspend fun completeOnboarding(name: String?, bio: String?, location: String?, avatar: String?) {
        db.transaction {
            contactService.completeOnboarding(this, name)
            contentService.completeOnboarding(this, bio, location, avatar)
        }
        loadOwnContact()
    }

    suspend fun loadChat(peerId: String, limit: Int = 50) {
        val messages = messageService.getChat(peerId, limit)
        _chats.update { current -> current + (peerId to messages) }
    }

    suspend fun saveMessage(message: Message, status: MessageStatus): MessageEntry {
        val saved = messageService.save(message, status)
        upsertMessage(saved)
        return saved
    }

    suspend fun updateMessageStatus(entry: MessageEntry, status: MessageStatus) {
        val updated = messageService.updateStatus(entry, status)
        upsertMessage(updated)
    }

    // The chat map is keyed by the remote peer's node ID so the UI can look up
    // messages with chats[contact.peerId]. Use whichever side is not us.
    private fun upsertMessage(entry: MessageEntry) {
        val conversationKey =
            if (entry.senderId == contact.peerId) entry.recipientId ?: entry.senderId
            else entry.senderId
        _chats.update { current ->
            val list = current[conversationKey] ?: emptyList()
            val index = list.indexOfFirst { it.id == entry.id }
            val updated = if (index >= 0) list.toMutableList().apply { set(index, entry) }
            else list + entry
            current + (conversationKey to updated)
        }
    }

    fun contactLookup(peerId: String): Contact? =
        contacts.value.firstOrNull { it.peerId == peerId }?.contact

    suspend fun resetData(): Boolean {
        viewState.showResetClearing()
        return runCatching {
            db.transaction {
                contactService.deleteAll(this)
                contentService.deleteAll(this)
                identityService.deleteAll(this)
                messageService.deleteAll(this)
                relayService.deleteAll(this)
            }
        }.onSuccess {
            log.info("[dev] All data deleted.")
            viewState.showResetCleared()
        }.onFailure {
            log.error("[dev] Failed to delete data: $it")
            viewState.showResetFailed()
        }.isSuccess
    }

    suspend fun deleteAllContent() {
        contentService.deleteAll()
        loadFeed()
    }

    suspend fun generateRandomSelfContent() {
        contentService.generateRandomSelfContent()
        loadFeed()
    }

    suspend fun generateRandomContactContent() {
        contentService.generateRandomContactContent()
        loadFeed()
    }

    suspend fun receiveContent(content: Content) {
        val saved = contentService.save(content)
        if (saved.content.isContact) loadContacts() else loadFeed()
    }

    suspend fun publishContent(body: ContentBody) {
        contentService.save(body)
    }

    private suspend fun loadOwnIdentity() {
        val entry = identityService.geOwnIdentity()
        identity = entry.identity
        identityEntry = entry
    }

    suspend fun updateOwnAvatar(avatar: String) {
        contentService.updateAvatar(avatar)
        loadOwnContact()
    }

    private suspend fun loadOwnContact() {
        contactEntry = contactService.getOwnContact()
        contact = contactEntry.contact
        val (entry, body) = contentService.getOwnContactContent()
        contactContentEntry = entry
        contactContent = entry.content
        contactContentBody = body
    }
}
