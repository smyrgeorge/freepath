package io.github.smyrgeorge.freepath.state

import io.github.smyrgeorge.freepath.Protocol
import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.contact.ContactCodec
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.content.Content
import io.github.smyrgeorge.freepath.content.ContentBody
import io.github.smyrgeorge.freepath.content.ContentCodec
import io.github.smyrgeorge.freepath.content.ContentType
import io.github.smyrgeorge.freepath.content.Message
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.crypto.KeyPair
import io.github.smyrgeorge.freepath.database.ContactEntry
import io.github.smyrgeorge.freepath.database.ContactEntryRepository
import io.github.smyrgeorge.freepath.database.ContactRoutingEntry
import io.github.smyrgeorge.freepath.database.ContactRoutingEntryRepository
import io.github.smyrgeorge.freepath.database.ContentEntry
import io.github.smyrgeorge.freepath.database.ContentEntryRepository
import io.github.smyrgeorge.freepath.database.ContentSyncEntryRepository
import io.github.smyrgeorge.freepath.database.ContentTrust
import io.github.smyrgeorge.freepath.database.IdentityEntry
import io.github.smyrgeorge.freepath.database.IdentityEntryRepository
import io.github.smyrgeorge.freepath.database.MessageEntry
import io.github.smyrgeorge.freepath.database.MessageEntryRepository
import io.github.smyrgeorge.freepath.database.MessageStatus
import io.github.smyrgeorge.freepath.state.model.ConnectionSource
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi

abstract class AbstractAppState(
    resources: AbstractAppResources,
    private val viewState: AbstractViewState,
) {
    val log: Logger = Logger.of(this::class)

    private val db: ISQLite by lazy { resources.db }
    private val identityRepository: IdentityEntryRepository = resources.identityRepository
    private val contactRepository: ContactEntryRepository = resources.contactRepository
    private val contentRepository: ContentEntryRepository = resources.contentRepository
    private val contentSyncRepository: ContentSyncEntryRepository = resources.contentSyncRepository
    private val contactRoutingRepository: ContactRoutingEntryRepository = resources.contactRoutingRepository
    private val messageRepository: MessageEntryRepository = resources.messageRepository

    private val _contacts = MutableStateFlow<List<ContactEntry>>(emptyList())
    val contacts: StateFlow<List<ContactEntry>> = _contacts.asStateFlow()

    private val _contactContents = MutableStateFlow<Map<String, ContentBody.Contact>>(emptyMap())
    val contactContents: StateFlow<Map<String, ContentBody.Contact>> = _contactContents.asStateFlow()

    private val _chats = MutableStateFlow<Map<String, List<MessageEntry>>>(emptyMap())
    val chats: StateFlow<Map<String, List<MessageEntry>>> = _chats.asStateFlow()

    private val _feedEntries = MutableStateFlow<List<ContentEntry>>(emptyList())
    val feedEntries: StateFlow<List<ContentEntry>> = _feedEntries.asStateFlow()

    /**
     * Unified view of contacts reachable via any transport, keyed by peerId.
     * Value is the set of transports ([ConnectionSource]) on which the peer is currently visible.
     * A peer reachable on both LAN and BLE appears once with both sources in the set.
     */
    val nearbyIdentifiedContacts: StateFlow<Map<String, Set<ConnectionSource>>> =
        combine(
            resources.libp2p.metrics.value,
            resources.libble.metrics.value,
        ) { lan, ble ->
            val result = mutableMapOf<String, MutableSet<ConnectionSource>>()
            lan.identifiedPeers
                .filter { it != identityEntry.peerId }
                .forEach { result.getOrPut(it) { mutableSetOf() }.add(ConnectionSource.LAN) }
            ble.identifiedPeers
                .filter { it != identityEntry.peerId }
                .forEach { result.getOrPut(it) { mutableSetOf() }.add(ConnectionSource.BLE) }
            result.mapValues { it.value.toSet() }
        }.stateIn(emptyMap())

    lateinit var identity: Identity
    lateinit var identityEntry: IdentityEntry
    lateinit var contact: Contact
    lateinit var contactEntry: ContactEntry
    lateinit var contactContentBody: ContentBody.Contact
    lateinit var contactContent: Content
    lateinit var contactContentEntry: ContentEntry

    suspend fun initialize() {
        loadIdentity()
        loadOwnContact()
        loadOwnContactContent()
        loadContacts()
    }

    suspend fun acceptContact(contact: Contact) = acceptContact(db, contact)
    suspend fun acceptContact(db: QueryExecutor, contact: Contact) {
        val existing = contactRepository.findOneByPeerId(db, contact.peerId).getOrThrow()
        if (existing == null) {
            val entry = ContactEntry(peerId = contact.peerId, contact = contact)
            contactRepository.insert(db, entry).getOrThrow()
        } else if (contact.updatedAt > existing.contact.updatedAt) {
            val entry = existing.merge(ContactEntry(peerId = contact.peerId, contact = contact))
            contactRepository.update(db, entry).getOrThrow()
        }
        // else: stored card is already up to date — no-op
        loadContacts(db)
    }

    suspend fun acceptContact(res: Protocol.BleContactExchangeSucceeded) {
        val contact = res.contact
        val identitySecretB64 = Base64.encode(res.identitySecret)
        db.transaction {
            acceptContact(this, contact)
            val peerId = contact.peerId
            val now = Clock.System.now()
            val existing = contactRoutingRepository.findOneByPeerId(this, peerId).getOrNull()
            val entry = existing?.copy(
                bleUpdatedAt = now,
                bleIdentitySecret = identitySecretB64,
            ) ?: ContactRoutingEntry(
                peerId = peerId,
                bleUpdatedAt = now,
                bleIdentitySecret = identitySecretB64,
            )
            contactRoutingRepository.save(this, entry).getOrThrow()
        }
    }

    suspend fun setTrustLevel(entry: ContactEntry, level: TrustLevel) {
        val updated = entry.copy(trustLevel = level)
        contactRepository.update(db, updated).getOrThrow()
        loadContacts()
    }

    suspend fun loadContacts() = loadContacts(db)
    suspend fun loadContacts(db: QueryExecutor) {
        val ownPeerId = contactEntry.peerId
        val list = contactRepository.findAll(db).getOrThrow().filter { it.peerId != ownPeerId }
        _contacts.value = list
        _contactContents.value = list.mapNotNull { entry ->
            val body = contentRepository.findOneByAuthorIdAndTypeContact(db, entry.peerId).getOrNull()
                ?.content?.body as? ContentBody.Contact ?: return@mapNotNull null
            entry.peerId to body
        }.toMap()
    }

    suspend fun loadFeed(limit: Int = 50) {
        contentRepository
            .findAllByLimitAndOffset(db, limit, 0)
            .onSuccess {
                val feed = it.filter { c -> c.authorId != identityEntry.peerId }
                _feedEntries.value = feed
            }
    }

    fun cancelContactExchange() {
        viewState.hideExchangeDrawer()
    }

    suspend fun completeOnboarding(name: String?, bio: String?, location: String?, avatar: String?) {
        val updatedEntry = contactEntry.copy(
            contact = contact.copy(name = name?.takeIf { it.isNotBlank() }),
            tags = contactEntry.tags - ContactEntry.TAG_ONBOARDING,
        )
        contactEntry = contactRepository.update(db, updatedEntry).getOrThrow()
        contact = contactEntry.contact

        val peerId = identityEntry.peerId
        val body = ContentBody.Contact(
            bio = bio?.takeIf { it.isNotBlank() },
            avatar = avatar?.takeIf { it.length <= ContentBody.Contact.MAX_AVATAR_SIZE },
            location = location?.takeIf { it.isNotBlank() },
        )
        val envelope = ContentCodec.seal(
            body = body,
            authorId = peerId,
            sigKeyPrivate = identity.sigKeyPrivate,
        )
        val entry = ContentEntry.from(envelope, trust = ContentTrust.VERIFIED)
        contactContentEntry = contentRepository.insert(db, entry).getOrThrow()
        contactContent = envelope
        contactContentBody = body
    }

    suspend fun saveMessage(message: Message, status: MessageStatus): MessageEntry {
        val entry = MessageEntry.from(message = message, status = status)
        val saved = messageRepository.insert(db, entry).getOrThrow()

        // The chat map is keyed by the remote peer's node ID so the UI can look up
        // messages with chats[contact.peerId]. Use whichever side is not us.
        val conversationKey =
            if (message.senderId == contact.peerId) message.recipientId ?: message.senderId
            else message.senderId
        _chats.update { current ->
            current + (conversationKey to (current[conversationKey] ?: emptyList()) + saved)
        }

        return saved
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun loadChat(peerId: String, limit: Int = 50) {
        val conversationId = Message.conversationId(identityEntry.peerId, peerId)
        val messages = messageRepository.findAllByConversationId(db, conversationId, limit, 0).getOrThrow()
        _chats.update { current -> current + (peerId to messages) }
    }

    suspend fun updateMessageStatus(entry: MessageEntry, status: MessageStatus) {
        messageRepository.update(db, entry.copy(status = status)).getOrThrow()
    }

    fun contactLookup(peerId: String): Contact? =
        contacts.value.firstOrNull { it.peerId == peerId }?.contact

    suspend fun resetData(): Boolean {
        viewState.showResetClearing()
        return runCatching {
            db.transaction {
                contactRepository.deleteAll(this).getOrThrow()
                identityRepository.deleteAll(this).getOrThrow()
                contentRepository.deleteAll(this).getOrThrow()
                contentSyncRepository.deleteAll(this).getOrThrow()
                contactRoutingRepository.deleteAll(this).getOrThrow()
                messageRepository.deleteAll(this).getOrThrow()
            }
        }.onSuccess {
            log.info("[dev] All data deleted.")
            viewState.showResetCleared()
        }.onFailure {
            log.error("[dev] Failed to delete data: $it")
            viewState.showResetFailed()
        }.isSuccess
    }

    suspend fun deleteAllContent(): Boolean =
        runCatching {
            contentRepository.deleteAll(db).getOrThrow()
            _feedEntries.value = emptyList()
            log.info("[dev] All content deleted.")
        }.onFailure {
            log.error("[dev] Failed to delete content: $it")
        }.isSuccess

    suspend fun generateRandomSelfContent() {
        runCatching {
            val entries = RandomContentGenerator.generateSelfContent(
                selfPeerId = identityEntry.peerId,
                selfSigKeyPrivate = identity.sigKeyPrivate,
            )
            entries.forEach { contentRepository.insert(db, it).getOrThrow() }
            log.info("[dev] Random self content generated: ${entries.size} entries.")
            loadFeed()
        }.onFailure {
            log.error("[dev] Failed to generate self content: $it")
        }
    }

    suspend fun generateRandomContactContent() {
        runCatching {
            val entries = RandomContentGenerator.generateContactContent(
                contacts = _contacts.value,
            )
            entries.forEach { contentRepository.insert(db, it).getOrThrow() }
            log.info("[dev] Random contact content generated: ${entries.size} entries.")
            loadFeed()
        }.onFailure {
            log.error("[dev] Failed to generate contact content: $it")
        }
    }

    suspend fun updateAvatar(avatar: String?) {
        val updatedBody = contactContentBody.copy(avatar = avatar)
        val updatedEnvelope = contactContentEntry.content.copy(body = updatedBody)
        val updatedEntry = contactContentEntry.copy(content = updatedEnvelope)
        contactContentEntry = contentRepository.update(db, updatedEntry).getOrThrow()
        contactContent = updatedEnvelope
        contactContentBody = updatedBody
    }

    suspend fun receiveContent(content: Content) {
        // Contact content is keyed by authorId (peerId) in the DB, all other content by envelope.id.
        val isContact = content.type == ContentType.CONTACT
        val contentId = if (isContact) content.authorId else content.id

        val existing = contentRepository.findOneByContentId(db, contentId).getOrNull()
        // For non-contact content, skip if we already have this version or newer.
        // For contact content, always accept — the peer is the authoritative source for their own profile.
        if (!isContact && existing != null && existing.version >= content.version) return

        ContentEntry.from(content, existing?.id ?: 0, content.trust()).also {
            contentRepository.save(db, it).getOrThrow()
        }

        if (isContact) loadContacts() else loadFeed()
    }

    suspend fun publishContent(body: ContentBody) {
        val envelope = ContentCodec.seal(
            body = body,
            authorId = identityEntry.peerId,
            sigKeyPrivate = identity.sigKeyPrivate,
        )
        val entry = ContentEntry.from(envelope, trust = ContentTrust.VERIFIED)
        contentRepository.insert(db, entry).getOrThrow()
        log.info("[publishContent] Published content: ${envelope.id}")
        loadFeed()
    }

    private suspend fun loadIdentity() {
        val existing = identityRepository.findAll(db).getOrThrow()
        require(existing.size <= 1) { "Expected at most one identity entry, got $existing" }
        val entry = existing.firstOrNull() ?: createAndSaveIdentity()
        identity = entry.identity
        identityEntry = entry
    }

    private suspend fun Content.trust(): ContentTrust {
        val contact = contactRepository.findOneByPeerId(db, authorId).getOrThrow()
        return when {
            contact == null -> ContentTrust.UNKNOWN
            ContentCodec.verify(this, contact.contact.sigKey) -> ContentTrust.VERIFIED
            else -> ContentTrust.FAILED
        }
    }

    private suspend fun loadOwnContactContent() {
        val entry = contentRepository
            .findOneByAuthorIdAndTypeContact(db, identityEntry.peerId)
            .getOrNull() ?: return

        val body = entry.content.body as? ContentBody.Contact ?: return
        contactContentBody = body
        contactContent = entry.content
        contactContentEntry = entry
    }

    private suspend fun loadOwnContact() {
        val peerId = identityEntry.peerId
        val existing = contactRepository.findOneByPeerId(db, peerId).getOrThrow()
        if (existing != null) {
            contact = existing.contact
            contactEntry = existing
            return
        }

        val new = Contact(
            schema = Contact.SCHEMA,
            sigKey = Base64.encode(identity.sigKeyPublic),
            encKey = Base64.encode(identity.encKeyPublic),
            name = "#$peerId",
        )

        contact = new
        val entry = ContactEntry(
            peerId = peerId,
            contact = new,
            tags = listOf(ContactEntry.TAG_ONBOARDING)
        )
        contactEntry = contactRepository.insert(db, entry).getOrThrow()
    }

    private suspend fun createAndSaveIdentity(): IdentityEntry {
        val sigKeyPair: KeyPair = CryptoProvider.generateEd25519KeyPair()
        val encKeyPair: KeyPair = CryptoProvider.generateX25519KeyPair()
        val peerIdRaw = CryptoProvider.sha256(sigKeyPair.publicKey)
        val peerId = ContactCodec.derivePeerId(sigKeyPair.publicKey)

        val identity = Identity(
            peerIdRaw = peerIdRaw,
            sigKeyPublic = sigKeyPair.publicKey,
            sigKeyPrivate = sigKeyPair.privateKey,
            encKeyPublic = encKeyPair.publicKey,
            encKeyPrivate = encKeyPair.privateKey,
        )

        val entry = IdentityEntry(peerId = peerId, identity = identity)
        return identityRepository.insert(db, entry).getOrThrow()
    }
}
