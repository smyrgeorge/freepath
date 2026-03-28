package io.github.smyrgeorge.freepath.state

import io.github.smyrgeorge.freepath.client.model.ChatMessage
import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.ContactCardCodec
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.content.Content
import io.github.smyrgeorge.freepath.content.ContentBody
import io.github.smyrgeorge.freepath.content.ContentType
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.crypto.KeyPair
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import io.github.smyrgeorge.freepath.database.ContactCardEntryRepository
import io.github.smyrgeorge.freepath.database.ContactRoutingEntryRepository
import io.github.smyrgeorge.freepath.database.ContentEntry
import io.github.smyrgeorge.freepath.database.ContentEntryRepository
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
    private val contentEntryRepository: ContentEntryRepository = resources.contentEntryRepository
    private val contactRoutingEntryRepository: ContactRoutingEntryRepository = resources.contactRoutingEntryRepository

    private val _contacts = MutableStateFlow<List<ContactCardEntry>>(emptyList())
    val contacts: StateFlow<List<ContactCardEntry>> = _contacts.asStateFlow()

    private val _contactContents = MutableStateFlow<Map<String, ContentBody.Contact>>(emptyMap())
    val contactContents: StateFlow<Map<String, ContentBody.Contact>> = _contactContents.asStateFlow()

    private val _chats = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val chats: StateFlow<Map<String, List<ChatMessage>>> = _chats.asStateFlow()

    private val _feedEntries = MutableStateFlow<List<ContentEntry>>(emptyList())
    val feedEntries: StateFlow<List<ContentEntry>> = _feedEntries.asStateFlow()

    val nearbyLanPeers: StateFlow<Map<String, ConnectionSource>> =
        resources.libp2p.metrics.value.map {
            it.connectedPeers
                .filter { peerId -> peerId != identityEntry.peerId }
                .associateWith { ConnectionSource.LAN }
        }.stateIn(emptyMap())

    val identifiedLanPeers: StateFlow<Map<String, ConnectionSource>> =
        resources.libp2p.metrics.value.map {
            it.identifiedPeers
                .filter { peerId -> peerId != identityEntry.peerId }
                .associateWith { ConnectionSource.LAN }
        }.stateIn(emptyMap())

    lateinit var identity: Identity
    lateinit var identityEntry: IdentityEntry
    lateinit var contactCard: ContactCard
    lateinit var contactCardEntry: ContactCardEntry
    lateinit var contactCardContent: ContentBody.Contact
    lateinit var contactCardContentEnvelope: Content
    lateinit var contactCardContentEntry: ContentEntry

    suspend fun initialize() {
        loadIdentity()
        loadOwnContactCard()
        loadOwnContactContent()
        loadContacts()
    }

    suspend fun acceptContact(card: ContactCard) {
        val existing = contactCardRepository.findOneByPeerId(db, card.peerId).getOrThrow()
        if (existing == null) {
            val entry = ContactCardEntry(peerId = card.peerId, card = card)
            contactCardRepository.insert(db, entry).getOrThrow()
        } else if (card.updatedAt > existing.card.updatedAt) {
            val entry = existing.merge(ContactCardEntry(peerId = card.peerId, card = card))
            contactCardRepository.update(db, entry).getOrThrow()
        }
        // else: stored card is already up to date — no-op
        loadContacts()
    }

    suspend fun setTrustLevel(entry: ContactCardEntry, level: TrustLevel) {
        val updated = entry.copy(trustLevel = level)
        contactCardRepository.update(db, updated).getOrThrow()
        loadContacts()
    }

    suspend fun loadContacts() {
        val ownPeerId = contactCardEntry.peerId
        val list = contactCardRepository.findAll(db).getOrThrow().filter { it.peerId != ownPeerId }
        _contacts.value = list
        _contactContents.value = list.mapNotNull { entry ->
            val body = contentEntryRepository.findOneByContentId(db, entry.peerId).getOrNull()
                ?.content?.body as? ContentBody.Contact ?: return@mapNotNull null
            entry.peerId to body
        }.toMap()
    }

    suspend fun loadFeed(limit: Int = 50) {
        contentEntryRepository
            .findAllByLimitAndOffset(db, limit, 0)
            .onSuccess { _feedEntries.value = it }
    }

    fun cancelContactExchange() {
        viewState.hideExchangeDrawer()
    }

    suspend fun completeOnboarding(name: String?, bio: String?, location: String?, avatar: String?) {
        val updatedEntry = contactCardEntry.copy(
            card = contactCard.copy(name = name?.takeIf { it.isNotBlank() }),
            tags = contactCardEntry.tags - ContactCardEntry.TAG_ONBOARDING,
        )
        contactCardEntry = contactCardRepository.update(db, updatedEntry).getOrThrow()
        contactCard = contactCardEntry.card

        val peerId = identityEntry.peerId
        val body = ContentBody.Contact(
            bio = bio?.takeIf { it.isNotBlank() },
            avatar = avatar?.takeIf { it.length <= ContentBody.Contact.MAX_AVATAR_SIZE },
            location = location?.takeIf { it.isNotBlank() },
        )
        val envelope = Content(
            id = peerId,
            type = ContentType.CONTACT,
            authorId = peerId,
            createdAt = Clock.System.now(),
            signature = "self",
            body = body,
        )
        val entry = ContentEntry.from(envelope)
        contactCardContentEntry = contentEntryRepository.insert(db, entry).getOrThrow()
        contactCardContentEnvelope = envelope
        contactCardContent = body
    }

    fun appendMessage(message: ChatMessage) {
        // The chat map is keyed by the remote peer's node ID so the UI can look up
        // messages with chats[contact.peerId]. Use whichever side is not us.
        val conversationKey =
            if (message.senderId == contactCard.peerId) message.receiverId
            else message.senderId
        _chats.update { current ->
            current + (conversationKey to (current[conversationKey] ?: emptyList()) + message)
        }
    }

    fun contactLookup(peerId: String): ContactCard? =
        contacts.value.firstOrNull { it.peerId == peerId }?.card

    suspend fun resetData(): Boolean {
        viewState.showResetClearing()
        return runCatching {
            db.transaction {
                contactCardRepository.deleteAll(this).getOrThrow()
                identityRepository.deleteAll(this).getOrThrow()
                contentEntryRepository.deleteAll(this).getOrThrow()
                contactRoutingEntryRepository.deleteAll(this).getOrThrow()
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
            contentEntryRepository.deleteAll(db).getOrThrow()
            _feedEntries.value = emptyList()
            log.info("[dev] All content deleted.")
        }.onFailure {
            log.error("[dev] Failed to delete content: $it")
        }.isSuccess

    suspend fun generateRandomContent() {
        runCatching {
            val entries = RandomContentGenerator.generateRandomContent(_contacts.value)
            entries.forEach { contentEntryRepository.insert(db, it).getOrThrow() }
            log.info("[dev] Random content generated: ${entries.size} entries.")
            loadFeed()
        }.onFailure {
            log.error("[dev] Failed to generate content: $it")
        }
    }

    private suspend fun loadIdentity() {
        val existing = identityRepository.findAll(db).getOrThrow()
        require(existing.size <= 1) { "Expected at most one identity entry, got $existing" }
        val entry = existing.firstOrNull() ?: createAndSaveIdentity()
        identity = entry.identity
        identityEntry = entry
    }

    suspend fun updateAvatar(avatar: String?) {
        val updatedBody = contactCardContent.copy(avatar = avatar)
        val updatedEnvelope = contactCardContentEntry.content.copy(body = updatedBody)
        val updatedEntry = contactCardContentEntry.copy(content = updatedEnvelope)
        contactCardContentEntry = contentEntryRepository.update(db, updatedEntry).getOrThrow()
        contactCardContentEnvelope = updatedEnvelope
        contactCardContent = updatedBody
    }

    suspend fun receiveContent(envelope: Content) {
        // Contact content is keyed by authorId (peerId) in the DB, all other content by envelope.id.
        val isContact = envelope.type == ContentType.CONTACT
        val contentId = if (isContact) envelope.authorId else envelope.id

        val existing = contentEntryRepository.findOneByContentId(db, contentId).getOrNull()
        // For non-contact content, skip if we already have this version or newer.
        // For contact content, always accept — the peer is the authoritative source for their own profile.
        if (!isContact && existing != null && existing.version >= envelope.version) return

        ContentEntry.from(envelope, existing?.id ?: 0).also {
            contentEntryRepository.save(db, it).getOrThrow()
        }

        if (isContact) loadContacts() else loadFeed()
    }

    private suspend fun loadOwnContactContent() {
        val entry = contentEntryRepository.findOneByContentId(db, identityEntry.peerId).getOrThrow() ?: return
        val body = entry.content.body as? ContentBody.Contact ?: return
        contactCardContent = body
        contactCardContentEnvelope = entry.content
        contactCardContentEntry = entry
    }

    private suspend fun loadOwnContactCard() {
        val peerId = identityEntry.peerId
        val existing = contactCardRepository.findOneByPeerId(db, peerId).getOrThrow()
        if (existing != null) {
            contactCard = existing.card
            contactCardEntry = existing
            return
        }

        val card = ContactCard(
            schema = ContactCard.SCHEMA,
            sigKey = Base64.encode(identity.sigKeyPublic),
            encKey = Base64.encode(identity.encKeyPublic),
            name = "#$peerId",
        )

        contactCard = card
        val entry = ContactCardEntry(
            peerId = peerId,
            card = card,
            tags = listOf(ContactCardEntry.TAG_ONBOARDING)
        )
        contactCardEntry = contactCardRepository.insert(db, entry).getOrThrow()
    }

    private suspend fun createAndSaveIdentity(): IdentityEntry {
        val sigKeyPair: KeyPair = CryptoProvider.generateEd25519KeyPair()
        val encKeyPair: KeyPair = CryptoProvider.generateX25519KeyPair()
        val peerIdRaw = CryptoProvider.sha256(sigKeyPair.publicKey)
        val peerId = ContactCardCodec.derivePeerId(sigKeyPair.publicKey)

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
