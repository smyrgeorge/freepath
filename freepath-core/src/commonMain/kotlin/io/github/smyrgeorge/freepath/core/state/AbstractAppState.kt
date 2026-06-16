package io.github.smyrgeorge.freepath.core.state

import io.github.smyrgeorge.actor4k.system.ActorSystem
import io.github.smyrgeorge.freepath.core.actor.RelayActor
import io.github.smyrgeorge.freepath.core.actor.RelayProtocol
import io.github.smyrgeorge.freepath.core.actor.PeerActor
import io.github.smyrgeorge.freepath.core.actor.PeerProtocol
import io.github.smyrgeorge.freepath.core.state.model.ConnectionSource
import io.github.smyrgeorge.freepath.core.state.service.ContactEncounterService
import io.github.smyrgeorge.freepath.core.state.service.ContactService
import io.github.smyrgeorge.freepath.core.state.service.ContentService
import io.github.smyrgeorge.freepath.core.state.service.IdentityService
import io.github.smyrgeorge.freepath.core.state.service.MessageService
import io.github.smyrgeorge.freepath.core.state.service.RelayService
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.db
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.tx
import io.github.smyrgeorge.freepath.database.ContactEntry
import io.github.smyrgeorge.freepath.database.ContentEntry
import io.github.smyrgeorge.freepath.database.IdentityEntry
import io.github.smyrgeorge.freepath.database.MessageEntry
import io.github.smyrgeorge.freepath.database.MessageStatus
import io.github.smyrgeorge.freepath.database.RelayEntry.Companion.toRelayEntry
import io.github.smyrgeorge.freepath.libnet.LibnetModule
import io.github.smyrgeorge.freepath.libnet.client.LibnetClient
import io.github.smyrgeorge.freepath.libnet.client.codec.LibnetClientCodec
import io.github.smyrgeorge.freepath.libnet.client.model.RelayOptions
import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.model.contact.Identity
import io.github.smyrgeorge.freepath.model.contact.TrustLevel
import io.github.smyrgeorge.freepath.model.content.Content
import io.github.smyrgeorge.freepath.model.content.ContentBody
import io.github.smyrgeorge.freepath.model.content.Message
import io.github.smyrgeorge.freepath.model.content.MessageCodec
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.classic.error
import io.github.smyrgeorge.log4k.classic.info
import io.github.smyrgeorge.log4k.classic.warn
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

    private val contactEncounterService: ContactEncounterService by lazy { resources.contactEncounterService }
    private val contactService: ContactService by lazy { resources.contactService }
    private val contentService: ContentService by lazy { resources.contentService }
    private val identityService: IdentityService by lazy { resources.identityService }
    private val messageService: MessageService by lazy { resources.messageService }
    private val relayService: RelayService by lazy { resources.relayService }
    private val client: LibnetClient by lazy { resources.client }
    private val libnet: LibnetModule by lazy { resources.libnet }

    private val _contacts = MutableStateFlow<List<ContactEntry>>(emptyList())
    val contacts: StateFlow<List<ContactEntry>> = _contacts.asStateFlow()

    private val _contactContents = MutableStateFlow<Map<String, ContentBody.Contact>>(emptyMap())
    val contactContents: StateFlow<Map<String, ContentBody.Contact>> = _contactContents.asStateFlow()

    private val _chats = MutableStateFlow<Map<String, List<MessageEntry>>>(emptyMap())
    val chats: StateFlow<Map<String, List<MessageEntry>>> = _chats.asStateFlow()

    private val _feedEntries = MutableStateFlow<List<ContentEntry>>(emptyList())
    val feedEntries: StateFlow<List<ContentEntry>> = _feedEntries.asStateFlow()

    private val _profileEntries = MutableStateFlow<List<ContentEntry>>(emptyList())
    val profileEntries: StateFlow<List<ContentEntry>> = _profileEntries.asStateFlow()

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

    suspend fun acceptContact(contact: Contact) {
        contactService.tx { save(contact) }
        loadContacts()
    }

    suspend fun acceptBleContact(contact: Contact, identitySecret: ByteArray) {
        contactService.tx { saveBleContactExchange(contact, identitySecret) }
        loadContacts()
    }

    suspend fun setTrustLevel(entry: ContactEntry, level: TrustLevel) {
        contactService.tx { setTrustLevel(entry, level) }
        loadContacts()
    }

    suspend fun loadContacts() {
        val contacts = contactService.db { getContacts() }
        _contacts.value = contacts
        _contactContents.value = contacts.associate { entry ->
            entry.peerId to contentService.db { getContactContent(entry.peerId).contact() }
        }
    }

    suspend fun loadFeed(limit: Int = 50, offset: Int = 0) {
        contentService.db { getFeed(limit, offset) }.also {
            val feed = it.filter { c -> c.authorId != identityEntry.peerId }
            _feedEntries.value = feed
        }
    }

    suspend fun loadProfile(authorId: String, limit: Int = 50, offset: Int = 0) {
        _profileEntries.value = contentService.db { getByAuthor(authorId, limit, offset) }
    }

    suspend fun completeOnboarding(name: String?, bio: String?, location: String?, avatar: String?) {
        db.transaction {
            contactService.completeOnboarding(name)
            contentService.completeOnboarding(bio, location, avatar)
        }
        loadOwnContact()
    }

    suspend fun loadChat(peerId: String, limit: Int = 50) {
        val messages = messageService.db { getConversation(peerId, limit) }
        _chats.update { current -> current + (peerId to messages) }
    }

    suspend fun sendMessage(peerId: String, text: String) {
        val message = MessageCodec.seal(
            sigKeyPrivate = identity.sigKeyPrivate,
            conversationId = Message.conversationId(identity.peerId, peerId),
            senderId = identity.peerId,
            recipientId = peerId,
            body = text,
        )
        val entry = saveMessage(message, MessageStatus.SENDING)
        client.send(message, peerId)
            .onSuccess { updateMessageStatus(entry, MessageStatus.SENT) }
            .onFailure { error ->
                val status = relayMessage(message, peerId)
                updateMessageStatus(entry, status)
                when (status) {
                    MessageStatus.RELAYED -> log.info("[send] Direct send to $peerId failed (${error.message}); relayed to the mesh")
                    MessageStatus.QUEUED -> log.info("[send] Direct send to $peerId failed (${error.message}); queued for relay")
                    else -> log.error("[send] Failed to send or queue message to $peerId: ${error.message}")
                }
            }
    }

    suspend fun saveMessage(message: Message, status: MessageStatus): MessageEntry {
        val saved = messageService.db { save(message, status) }
        upsertMessage(saved)
        return saved
    }

    suspend fun updateMessageStatus(entry: MessageEntry, status: MessageStatus) {
        val updated = messageService.db { save(entry, status) }
        upsertMessage(updated)
    }

    suspend fun relayMessage(message: Message, peerId: String): MessageStatus {
        val receiver = contactLookup(peerId) ?: run {
            log.error("[relay] No contact card for $peerId; cannot seal a relay copy")
            return MessageStatus.FAILED
        }
        val envelope = LibnetClientCodec.seal(
            identity = identity,
            receiverContact = receiver,
            type = LibnetClientCodec.TYPE_CHAT,
            plaintext = MessageCodec.encode(message),
            relay = RelayOptions(),
        )

        // Persist the master replica first (copies = L) via the relay-queue owner. This is what keeps
        // store-and-forward working when no peer is online now: PeerActor sprays it whenever a
        // peer (re)connects. We await the enqueue so the nudge below can't outrun the committed row.
        val stored = ActorSystem.get(RelayActor::class, RelayActor.key(identity.peerId))
            .ask(RelayProtocol.Enqueue(envelope.toRelayEntry()))
            .getOrElse {
                log.error("[relay] Failed to enqueue relay copy for $peerId: ${it.message}")
                return MessageStatus.FAILED
            }
            .entry

        // Nudge peers reachable right now to spray immediately — otherwise the freshly-queued
        // replica would sit until the next connection event. PeerActor drives the spray pass and
        // per-peer dedup, while the copy-budget accounting lives in RelayActor; we only trigger
        // it here, so this is best-effort ("handed to the mesh"), hence RELAYED rather than SENT.
        val online = libnet.onlinePeerIds()
        if (online.isEmpty()) {
            log.info("[relay] No peers online; queued relay copy for $peerId (entry ${stored.id})")
            return MessageStatus.QUEUED
        }
        // Shuffle so copies fan out to a random subset of carriers. Binary Spray-and-Wait halves the
        // budget per peer, so only the first ~log2(copies) peers to reserve get a copy at all; with a
        // stable peer order that would always be the same few carriers. Randomizing spreads the carrier
        // load across the reachable population and improves delivery diversity. (When the encounter
        // heuristic is wired in, this becomes a ranked order — best carriers first — instead of random.)
        online.shuffled().forEach { peer ->
            ActorSystem.get(PeerActor::class, PeerActor.key(identity.peerId, peer))
                .tell(PeerProtocol.Relay)
                .onFailure { log.warn("[relay] Failed to trigger spray to $peer: ${it.message}") }
        }
        log.info("[relay] Queued relay copy for $peerId (entry ${stored.id}); spraying to ${online.size} online peer(s)")
        return MessageStatus.RELAYED
    }

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
                contactEncounterService.deleteAll()
                contactService.deleteAll()
                contentService.deleteAll()
                identityService.deleteAll()
                messageService.deleteAll()
                relayService.deleteAll()
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
        contentService.tx { deleteAll() }
        loadFeed()
    }

    suspend fun generateRandomSelfContent() {
        val entries = contentService.tx { generateRandomSelfContent() }
        log.info("[dev] Generated ${entries.size} random self content entries.")
        loadFeed()
    }

    suspend fun generateRandomContactContent() {
        val entries = contentService.tx { generateRandomContactContent() }
        log.info("[dev] Generated ${entries.size} random contact content entries.")
        loadFeed()
    }

    suspend fun receiveContent(content: Content) {
        val saved = contentService.tx { save(content) }
        if (saved.content.isContact) loadContacts() else loadFeed()
    }

    suspend fun publishContent(body: ContentBody) {
        contentService.tx { save(body) }
    }

    private suspend fun loadOwnIdentity() {
        val entry = identityService.tx { geOwnIdentity() }
        identity = entry.identity
        identityEntry = entry
    }

    suspend fun updateOwnAvatar(avatar: String) {
        contentService.tx { updateAvatar(avatar) }
        loadOwnContact()
    }

    private suspend fun loadOwnContact() {
        contactService.tx {
            contactEntry = getOwnContact()
            contact = contactEntry.contact

            val (entry, body) = contentService.getOwnContactContent()
            contactContentEntry = entry
            contactContent = entry.content
            contactContentBody = body
        }
    }
}
