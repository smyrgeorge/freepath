package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.database.util.Auditable
import io.github.smyrgeorge.freepath.database.util.ContactConverter
import io.github.smyrgeorge.freepath.database.util.InstantConverter
import io.github.smyrgeorge.freepath.database.util.StringListConverter
import io.github.smyrgeorge.sqlx4k.annotation.Converter
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlin.time.Clock
import kotlin.time.Instant

@Table("contact")
data class ContactEntry(
    @Id
    override val id: Int = 0,
    @Converter(InstantConverter::class)
    override var createdAt: Instant = Clock.System.now(),
    @Converter(InstantConverter::class)
    override var updatedAt: Instant = Clock.System.now(),
    /** Unique key. Derived locally from the contact's sigKey. */
    val peerId: String,
    /** The accepted contact contact. */
    @Converter(ContactConverter::class)
    val contact: Contact,
    /** Controls how content from this contact is handled. */
    val trustLevel: TrustLevel = TrustLevel.TRUSTED,
    /** Local override for the contact's display name. Shown instead of contact.name when set. */
    val name: String? = null,
    /** Unix epoch milliseconds when content or a contact from this contact was last received. */
    @Converter(InstantConverter::class)
    val lastSeenAt: Instant? = null,
    /** Free-text field for the user's own reference. Never shared. Max 1024 chars. */
    val notes: String? = null,
    /** Whether this contact is pinned to the top of the contact list. */
    val pinned: Boolean = false,
    /** If true, no notifications are generated for content from this contact. */
    val muted: Boolean = false,
    /** User-defined labels for organising contacts. Max 16 tags, 32 chars each. */
    @Converter(StringListConverter::class)
    val tags: List<String> = emptyList(),
) : Auditable<Int> {
    init {
        require(id >= 0) { "id must be non-negative" }
        require(peerId.matches(BASE58_REGEX)) {
            "peerId must be a 52-character Base58 string"
        }
        require(notes.isNullOrEmpty() || notes.isNotBlank()) { "notes cannot be blank" }
        require(notes == null || notes.length <= MAX_NOTES_LENGTH) {
            "notes exceeds maximum length of $MAX_NOTES_LENGTH characters"
        }
        require(tags.size <= MAX_TAGS_COUNT) { "tags list exceeds maximum count of $MAX_TAGS_COUNT" }
        tags.forEachIndexed { index, tag ->
            require(tag.isNotEmpty() && tag.length <= MAX_TAG_LENGTH) {
                "tag at index $index must be non-empty and not exceed $MAX_TAG_LENGTH characters"
            }
        }
    }

    /**
     * Merges this entry with an incoming entry, preserving local-only fields.
     *
     * Per spec 1 Contact updates:
     * - The incoming contact is accepted only if its `updatedAt` is strictly greater.
     * - Local-only fields ([trustLevel], [name], [lastSeenAt], [notes], [pinned], [muted], [tags])
     *   are never modified by the merge.
     *
     * @param incoming The incoming contact entry with an updated contact.
     * @return A new [ContactEntry] with the incoming contact and all local-only fields preserved.
     * @throws IllegalArgumentException if `incoming.peerId` does not match this entry's [peerId].
     * @throws IllegalArgumentException if `incoming.contact` is not newer than the current contact.
     */
    fun merge(incoming: ContactEntry): ContactEntry {
        require(incoming.peerId == peerId) {
            "Cannot merge entries with different peer IDs: ${incoming.peerId} != $peerId"
        }
        require(incoming.contact.updatedAt > contact.updatedAt) {
            "Incoming contact is not newer than stored contact (${incoming.contact.updatedAt} <= ${contact.updatedAt})"
        }
        return copy(
            contact = incoming.contact,
            // All local-only fields are preserved from this entry
        )
    }

    companion object {
        const val MAX_NOTES_LENGTH = 1024
        const val MAX_TAGS_COUNT = 16
        const val MAX_TAG_LENGTH = 32
        private val BASE58_REGEX = Regex("[1-9A-HJ-NP-Za-km-z]{52}")

        /** Tag applied to the user's own contact on first creation. Used to route to the onboarding screen. */
        const val TAG_ONBOARDING = "ONBOARDING"
    }
}
