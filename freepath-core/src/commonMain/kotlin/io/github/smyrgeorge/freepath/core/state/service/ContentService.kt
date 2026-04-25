package io.github.smyrgeorge.freepath.core.state.service

import io.github.smyrgeorge.freepath.core.state.RandomContentGenerator
import io.github.smyrgeorge.freepath.database.ContentEntry
import io.github.smyrgeorge.freepath.database.ContentEntryRepository
import io.github.smyrgeorge.freepath.database.ContentSyncEntry
import io.github.smyrgeorge.freepath.database.ContentSyncEntryRepository
import io.github.smyrgeorge.freepath.database.ContentTrust
import io.github.smyrgeorge.freepath.model.contact.Identity
import io.github.smyrgeorge.freepath.model.content.Content
import io.github.smyrgeorge.freepath.model.content.ContentBody
import io.github.smyrgeorge.freepath.model.content.ContentCodec
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

class ContentService(
    private val db: ISQLite,
    private val contactService: ContactService,
    private val contentRepository: ContentEntryRepository,
    private val contentSyncRepository: ContentSyncEntryRepository,
) {
    lateinit var peerId: String
    lateinit var identity: Identity

    fun initialize(identity: Identity) {
        this.identity = identity
        this.peerId = identity.peerId
    }

    suspend fun getFeed(limit: Int = 50, offset: Int = 0): List<ContentEntry> =
        contentRepository.findAllByLimitAndOffset(db, limit, offset).getOrThrow()

    suspend fun getContactContent(peerId: String): ContentEntry = getContactContent(db, peerId)
    suspend fun getContactContent(db: QueryExecutor, peerId: String): ContentEntry =
        contentRepository
            .findOneByAuthorIdAndTypeContact(db, peerId)
            .getOrNull() ?: error("No contact content found for peer $peerId")

    private fun getContactContentBody(entry: ContentEntry): ContentBody.Contact =
        with(entry) {
            content.body as? ContentBody.Contact
                ?: error("Contact content expected, got ${content.body::class.simpleName}")
        }

    suspend fun getContactContentBody(peerId: String): ContentBody.Contact =
        with(getContactContent(peerId)) { getContactContentBody(this) }

    suspend fun getOwnContactContent(): Pair<ContentEntry, ContentBody.Contact> {
        val existing = contentRepository.findOneByAuthorIdAndTypeContact(db, peerId).getOrThrow()
        if (existing != null) {
            return existing to getContactContentBody(existing)
        }

        val body = ContentBody.Contact(bio = null, avatar = null, location = null)
        val envelope = ContentCodec.seal(
            body = body,
            authorId = peerId,
            sigKeyPrivate = identity.sigKeyPrivate,
        )
        val entry = ContentEntry.from(envelope, trust = ContentTrust.VERIFIED)
        return contentRepository.insert(db, entry).getOrThrow() to body
    }

    suspend fun save(body: ContentBody): ContentEntry {
        val envelope = ContentCodec.seal(
            body = body,
            authorId = peerId,
            sigKeyPrivate = identity.sigKeyPrivate,
        )
        return save(envelope)
    }

    suspend fun save(content: Content): ContentEntry {
        // Contact content is keyed by authorId (peerId) in the DB, all other content by envelope.id.
        val contentId = if (content.isContact) content.authorId else content.id

        val existing = contentRepository.findOneByContentId(db, contentId).getOrNull()
        // For non-contact content, skip if we already have this version or newer.
        // For contact content, always accept — the peer is the authoritative source for their own profile.
        if (!content.isContact
            && existing != null
            && existing.version >= content.version
        ) return existing

        return ContentEntry
            .from(content, existing?.id ?: 0, content.trust())
            .also {
                contentRepository.save(db, it).getOrThrow()
            }
    }

    suspend fun updateAvatar(avatar: String): ContentEntry {
        val body = getContactContentBody(peerId).copy(avatar = avatar)
        val existing = getContactContent(db, peerId)
        val sealed = ContentCodec.edit(existing.content, body, identity.sigKeyPrivate)
        val entry = ContentEntry.from(sealed, id = existing.id, trust = sealed.trust(db))
        return contentRepository.save(db, entry).getOrThrow()
    }

    suspend fun completeOnboarding(
        db: QueryExecutor,
        bio: String?,
        location: String?,
        avatar: String?
    ): Pair<ContentEntry, ContentBody.Contact> {
        val body = ContentBody.Contact(
            bio = bio?.takeIf { it.isNotBlank() },
            avatar = avatar?.takeIf { it.length <= ContentBody.Contact.MAX_AVATAR_SIZE },
            location = location?.takeIf { it.isNotBlank() },
        )

        val existing = getContactContent(db, peerId)
        val sealed = ContentCodec.edit(existing.content, body, identity.sigKeyPrivate)
        val entry = ContentEntry.from(sealed, id = existing.id, trust = sealed.trust(db))
        return contentRepository.save(db, entry).getOrThrow() to body
    }

    suspend fun getSyncEntry(peerId: String, contentId: String): ContentSyncEntry? =
        contentSyncRepository.findOneByPeerIdAndContentId(db, peerId, contentId).getOrThrow()

    suspend fun saveSyncEntry(entry: ContentSyncEntry): ContentSyncEntry =
        contentSyncRepository.save(db, entry).getOrThrow()

    suspend fun deleteAll() {
        contentRepository.deleteAll(db).getOrThrow()
        contentSyncRepository.deleteAll(db).getOrThrow()
    }

    suspend fun deleteAll(tx: Transaction) {
        contentRepository.deleteAll(tx).getOrThrow()
        contentSyncRepository.deleteAll(tx).getOrThrow()
    }

    suspend fun generateRandomSelfContent(): List<ContentEntry> {
        val entries = RandomContentGenerator.generateSelfContent(
            selfPeerId = peerId,
            selfSigKeyPrivate = identity.sigKeyPrivate,
        )
        entries.forEach { contentRepository.insert(db, it).getOrThrow() }
        return entries
    }

    suspend fun generateRandomContactContent(): List<ContentEntry> {
        val entries = RandomContentGenerator.generateContactContent(
            contacts = contactService.getContacts(),
        )
        entries.forEach { contentRepository.insert(db, it).getOrThrow() }
        return entries
    }

    private suspend fun Content.trust(): ContentTrust = trust(db)
    private suspend fun Content.trust(db: QueryExecutor): ContentTrust {
        val contact = contactService.getByPeerId(db, authorId)
        return when {
            contact == null -> ContentTrust.UNKNOWN
            ContentCodec.verify(this, contact.contact.sigKey) -> ContentTrust.VERIFIED
            else -> ContentTrust.FAILED
        }
    }
}