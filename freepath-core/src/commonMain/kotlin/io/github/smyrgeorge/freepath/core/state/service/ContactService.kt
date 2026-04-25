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
    override val db: ISQLite,
    private val identityService: IdentityService,
    private val contactRepository: ContactEntryRepository,
    private val contactRoutingRepository: ContactRoutingEntryRepository,
) : Service {
    private val peerId: String get() = identityService.peerId
    private val identity: Identity get() = identityService.identity

    context(db: QueryExecutor)
    suspend fun getContacts(): List<ContactEntry> =
        contactRepository.findAll().getOrThrow().filter { it.peerId != peerId }

    context(db: QueryExecutor)
    suspend fun getByPeerId(peerId: String): ContactEntry? =
        contactRepository.findOneByPeerId(peerId).getOrThrow()

    context(db: QueryExecutor)
    suspend fun getAllBleContactRouting(): List<ContactRoutingEntry> =
        contactRoutingRepository.findAllByIdentitySecretNotNull().getOrThrow()

    context(db: Transaction)
    suspend fun getOwnContact(): ContactEntry = getByPeerId(peerId) ?: save(identity)

    context(db: Transaction)
    suspend fun setTrustLevel(entry: ContactEntry, level: TrustLevel): ContactEntry {
        val updated = entry.copy(trustLevel = level)
        return contactRepository.update(updated).getOrThrow()
    }

    context(db: Transaction)
    private suspend fun save(identity: Identity): ContactEntry {
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
        return contactRepository.insert(entry).getOrThrow()
    }

    context(db: Transaction)
    suspend fun save(contact: Contact): ContactEntry {
        val existing = getByPeerId(contact.peerId)
        return if (existing == null) {
            val entry = ContactEntry(peerId = contact.peerId, contact = contact)
            contactRepository.insert(entry).getOrThrow()
        } else if (contact.updatedAt > existing.contact.updatedAt) {
            val entry = existing.merge(ContactEntry(peerId = contact.peerId, contact = contact))
            contactRepository.update(entry).getOrThrow()
        } else existing // stored card is already up to date — no-op
    }

    context(db: Transaction)
    suspend fun saveBleContactExchange(contact: Contact, bleIdentitySecret: ByteArray): ContactRoutingEntry {
        val now = Clock.System.now()
        val bleIdentitySecretB64 = Base64.encode(bleIdentitySecret)
        save(contact)
        val existing = contactRoutingRepository.findOneByPeerId(peerId).getOrNull()
        val entry = existing?.copy(
            bleUpdatedAt = now, bleIdentitySecret = bleIdentitySecretB64
        ) ?: ContactRoutingEntry(
            peerId = peerId,
            bleUpdatedAt = now,
            bleIdentitySecret = bleIdentitySecretB64
        )
        return contactRoutingRepository.save(entry).getOrThrow()
    }

    context(db: Transaction)
    suspend fun completeOnboarding(name: String?): ContactEntry {
        val contact = getOwnContact()
        val updated = contact.copy(
            contact = contact.contact.copy(
                name = name?.takeIf { it.isNotBlank() }
            ),
            tags = contact.tags - ContactEntry.TAG_ONBOARDING,
        )
        return contactRepository.update(updated).getOrThrow()
    }

    context(db: Transaction)
    suspend fun deleteAll() {
        contactRepository.deleteAll().getOrThrow()
        contactRoutingRepository.deleteAll().getOrThrow()
    }
}
