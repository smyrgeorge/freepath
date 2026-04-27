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
import io.github.smyrgeorge.freepath.model.content.ContentBodyCodec
import io.github.smyrgeorge.freepath.model.content.ContentCodec
import io.github.smyrgeorge.freepath.model.content.ContentType
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

class ContentService(
    override val db: ISQLite,
    private val identityService: IdentityService,
    private val contactService: ContactService,
    private val contentRepository: ContentEntryRepository,
    private val contentSyncRepository: ContentSyncEntryRepository,
) : Service {
    private val peerId: String get() = identityService.peerId
    private val identity: Identity get() = identityService.identity

    context(db: QueryExecutor)
    suspend fun getFeed(limit: Int = 50, offset: Int = 0): List<ContentEntry> =
        contentRepository.findAllByLimitAndOffset(limit, offset).getOrThrow()

    context(db: QueryExecutor)
    suspend fun getByAuthor(authorId: String, limit: Int = 50, offset: Int = 0): List<ContentEntry> =
        contentRepository.findAllByAuthorIdAndLimitAndOffset(authorId, limit, offset).getOrThrow()

    context(db: QueryExecutor)
    suspend fun getContactContent(peerId: String): ContentEntry {
        suspend fun getEmptyContactContent(peerId: String): ContentEntry {
            val body = ContentBody.Contact(bio = null, avatar = null, location = null)
            val content = Content(
                id = ContentBodyCodec.deriveId(body),
                type = ContentType.CONTACT,
                authorId = peerId,
                signature = "",
                body = body,
            )
            val new = ContentEntry.from(content, trust = ContentTrust.UNKNOWN)
            return contentRepository.insert(new).getOrThrow()
        }

        return contentRepository.findOneByAuthorIdAndTypeContact(peerId).getOrNull()
            ?: getEmptyContactContent(peerId)
    }


    context(db: QueryExecutor)
    suspend fun getOwnContactContent(): Pair<ContentEntry, ContentBody.Contact> {
        val existing = contentRepository.findOneByAuthorIdAndTypeContact(peerId).getOrThrow()
        if (existing != null) {
            return existing to existing.contact()
        }

        val body = ContentBody.Contact(bio = null, avatar = null, location = null)
        val envelope = ContentCodec.seal(
            body = body,
            authorId = peerId,
            sigKeyPrivate = identity.sigKeyPrivate,
        )
        val entry = ContentEntry.from(envelope, trust = ContentTrust.VERIFIED)
        return contentRepository.insert(entry).getOrThrow() to body
    }

    context(db: Transaction)
    suspend fun save(body: ContentBody): ContentEntry {
        val envelope = ContentCodec.seal(
            body = body,
            authorId = peerId,
            sigKeyPrivate = identity.sigKeyPrivate,
        )
        return save(envelope)
    }

    context(db: Transaction)
    suspend fun save(content: Content): ContentEntry {
        // Contact content is keyed by authorId (peerId) in the DB, all other content by envelope.id.
        val contentId = if (content.isContact) content.authorId else content.id

        val existing = contentRepository.findOneByContentId(contentId).getOrNull()
        // For non-contact content, skip if we already have this version or newer.
        // For contact content, always accept — the peer is the authoritative source for their own profile.
        if (!content.isContact
            && existing != null
            && existing.version >= content.version
        ) return existing

        return ContentEntry
            .from(content, existing?.id ?: 0, content.trust())
            .also {
                contentRepository.save(it).getOrThrow()
            }
    }

    context(db: Transaction)
    suspend fun updateAvatar(avatar: String): ContentEntry {
        val entry = getContactContent(peerId)
        val body = entry.contact().copy(avatar = avatar)
        val sealed = ContentCodec.edit(entry.content, body, identity.sigKeyPrivate)
        val updated = ContentEntry.from(sealed, id = entry.id, trust = sealed.trust())
        return contentRepository.save(updated).getOrThrow()
    }

    context(db: Transaction)
    suspend fun completeOnboarding(
        bio: String?,
        location: String?,
        avatar: String?
    ): Pair<ContentEntry, ContentBody.Contact> = with(db) {
        val body = ContentBody.Contact(
            bio = bio?.takeIf { it.isNotBlank() },
            avatar = avatar?.takeIf { it.length <= ContentBody.Contact.MAX_AVATAR_SIZE },
            location = location?.takeIf { it.isNotBlank() },
        )

        val existing = getContactContent(peerId)
        val sealed = ContentCodec.edit(existing.content, body, identity.sigKeyPrivate)
        val entry = ContentEntry.from(sealed, id = existing.id, trust = sealed.trust())
        contentRepository.save(entry).getOrThrow() to body
    }

    context(db: QueryExecutor)
    suspend fun getSyncEntry(peerId: String, contentId: String): ContentSyncEntry? =
        contentSyncRepository.findOneByPeerIdAndContentId(peerId, contentId).getOrThrow()

    context(db: Transaction)
    suspend fun saveSyncEntry(entry: ContentSyncEntry): ContentSyncEntry =
        contentSyncRepository.save(entry).getOrThrow()

    context(db: Transaction)
    suspend fun generateRandomSelfContent(): List<ContentEntry> =
        RandomContentGenerator.generateSelfContent(peerId, identity.sigKeyPrivate).map { save(it) }

    context(db: Transaction)
    suspend fun generateRandomContactContent(): List<ContentEntry> =
        RandomContentGenerator.generateContactContent(contactService.getContacts()).map { save(it) }

    context(db: QueryExecutor)
    private suspend fun Content.trust(): ContentTrust {
        val contact = contactService.getByPeerId(authorId)
        return when {
            contact == null -> ContentTrust.UNKNOWN
            ContentCodec.verify(this, contact.contact.sigKey) -> ContentTrust.VERIFIED
            else -> ContentTrust.FAILED
        }
    }

    context(db: Transaction)
    suspend fun deleteAll() {
        contentRepository.deleteAll().getOrThrow()
        contentSyncRepository.deleteAll().getOrThrow()
    }
}
