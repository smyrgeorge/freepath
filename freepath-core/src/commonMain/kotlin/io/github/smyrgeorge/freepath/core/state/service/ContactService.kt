package io.github.smyrgeorge.freepath.core.state.service

import io.github.smyrgeorge.freepath.database.ContactEntry
import io.github.smyrgeorge.freepath.database.ContactEntryRepository
import io.github.smyrgeorge.freepath.database.ContactRoutingEntry
import io.github.smyrgeorge.freepath.database.ContactRoutingEntryRepository
import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.model.contact.Identity
import io.github.smyrgeorge.freepath.model.contact.TrustLevel
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlin.io.encoding.Base64
import kotlin.time.Clock

class ContactService(
    private val db: ISQLite,
    private val identityService: IdentityService,
    private val contactRepository: ContactEntryRepository,
    private val contactRoutingRepository: ContactRoutingEntryRepository,
) {
    private val peerId: String get() = identityService.peerId
    private val identity: Identity get() = identityService.identity

    suspend fun getContacts(): List<ContactEntry> = getContacts(db)
    suspend fun getContacts(db: QueryExecutor): List<ContactEntry> =
        contactRepository.findAll(db).getOrThrow().filter { it.peerId != peerId }

    suspend fun getByPeerId(peerId: String): ContactEntry? = getByPeerId(db, peerId)
    suspend fun getByPeerId(db: QueryExecutor, peerId: String): ContactEntry? =
        contactRepository.findOneByPeerId(db, peerId).getOrThrow()

    suspend fun setTrustLevel(entry: ContactEntry, level: TrustLevel): ContactEntry {
        val updated = entry.copy(trustLevel = level)
        return contactRepository.update(db, updated).getOrThrow()
    }

    suspend fun getOwnContact(): ContactEntry = getOwnContact(db)
    private suspend fun getOwnContact(db: QueryExecutor): ContactEntry =
        getByPeerId(db, peerId) ?: save(db, identity)

    private suspend fun save(db: QueryExecutor, identity: Identity): ContactEntry {
        val new = Contact(
            schema = Contact.SCHEMA,
            sigKey = Base64.encode(identity.sigKeyPublic),
            encKey = Base64.encode(identity.encKeyPublic),
            name = "#$peerId",
        )

        val entry = ContactEntry(
            peerId = peerId,
            contact = new,
            tags = listOf(ContactEntry.TAG_ONBOARDING)
        )
        return contactRepository.insert(db, entry).getOrThrow()
    }

    suspend fun save(db: QueryExecutor, contact: Contact): ContactEntry {
        val existing = getByPeerId(contact.peerId)
        return if (existing == null) {
            val entry = ContactEntry(peerId = contact.peerId, contact = contact)
            contactRepository.insert(db, entry).getOrThrow()
        } else if (contact.updatedAt > existing.contact.updatedAt) {
            val entry = existing.merge(ContactEntry(peerId = contact.peerId, contact = contact))
            contactRepository.update(db, entry).getOrThrow()
        } else existing // stored card is already up to date — no-op
    }

    suspend fun saveBleContactExchange(contact: Contact, bleIdentitySecret: ByteArray) {
        val now = Clock.System.now()
        val bleIdentitySecretB64 = Base64.encode(bleIdentitySecret)
        db.transaction {
            save(this, contact)
            val existing = contactRoutingRepository.findOneByPeerId(this, peerId).getOrNull()
            val entry = existing?.copy(bleUpdatedAt = now, bleIdentitySecret = bleIdentitySecretB64)
                ?: ContactRoutingEntry(peerId = peerId, bleUpdatedAt = now, bleIdentitySecret = bleIdentitySecretB64)
            contactRoutingRepository.save(this, entry).getOrThrow()
        }
    }

    suspend fun completeOnboarding(db: QueryExecutor, name: String?): ContactEntry {
        val contact = getOwnContact(db)
        val updated = contact.copy(
            contact = contact.contact.copy(name = name?.takeIf { it.isNotBlank() }),
            tags = contact.tags - ContactEntry.TAG_ONBOARDING,
        )
        return contactRepository.update(db, updated).getOrThrow()
    }

    suspend fun deleteAll(tx: Transaction) {
        contactRepository.deleteAll(tx).getOrThrow()
        contactRoutingRepository.deleteAll(tx).getOrThrow()
    }
}