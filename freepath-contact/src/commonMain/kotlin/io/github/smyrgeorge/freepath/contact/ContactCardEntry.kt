package io.github.smyrgeorge.freepath.contact

data class ContactCardEntry(
    /** Primary key. Derived locally from the contact's sigKey. */
    val nodeId: String,
    /** The accepted contact card. */
    val card: ContactCard,
    /** Controls how content from this contact is handled. */
    val trustLevel: TrustLevel = TrustLevel.TRUSTED,
    /** Unix epoch milliseconds when the contact card was first accepted. */
    val addedAt: Long,
    /** Local override for the contact's display name. Shown instead of card.name when set. */
    val name: String? = null,
    /** Unix epoch milliseconds when content or a card from this contact was last received. */
    val lastSeenAt: Long? = null,
    /** Free-text field for the user's own reference. Never shared. Max 1024 chars. */
    val notes: String? = null,
    /** Whether this contact is pinned to the top of the contact list. */
    val pinned: Boolean = false,
    /** If true, no notifications are generated for content from this contact. */
    val muted: Boolean = false,
    /** User-defined labels for organising contacts. Max 10 tags, 32 chars each. */
    val tags: List<String> = emptyList(),
) {
    init {
        require(notes == null || notes.length <= MAX_NOTES_LENGTH) {
            "notes exceeds maximum length of $MAX_NOTES_LENGTH characters"
        }
        require(tags.size <= MAX_TAGS_COUNT) {
            "tags list exceeds maximum count of $MAX_TAGS_COUNT"
        }
        tags.forEachIndexed { index, tag ->
            require(tag.length <= MAX_TAG_LENGTH) {
                "tag at index $index exceeds maximum length of $MAX_TAG_LENGTH characters"
            }
        }
    }

    /**
     * Merges this entry with an incoming entry, preserving local-only fields.
     *
     * Per spec 1 Card updates:
     * - The incoming card is accepted only if its `updatedAt` is strictly greater.
     * - Local-only fields ([trustLevel], [addedAt], [name], [lastSeenAt], [notes], [pinned], [muted], [tags])
     *   are never modified by the merge.
     *
     * @param incoming The incoming contact entry with an updated card.
     * @return A new [ContactCardEntry] with the incoming card and all local-only fields preserved.
     * @throws IllegalArgumentException if `incoming.nodeId` does not match this entry's [nodeId].
     * @throws IllegalArgumentException if `incoming.card` is not newer than the current card.
     */
    fun merge(incoming: ContactCardEntry): ContactCardEntry {
        require(incoming.nodeId == nodeId) {
            "Cannot merge entries with different Node IDs: ${incoming.nodeId} != $nodeId"
        }
        require(incoming.card.updatedAt > card.updatedAt) {
            "Incoming card is not newer than stored card (${incoming.card.updatedAt} <= ${card.updatedAt})"
        }
        return copy(
            card = incoming.card,
            // All local-only fields are preserved from this entry
        )
    }

    companion object {
        const val MAX_NOTES_LENGTH = 1024
        const val MAX_TAGS_COUNT = 10
        const val MAX_TAG_LENGTH = 32
    }
}
