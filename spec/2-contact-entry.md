# Contact Entry

A **contact entry** is the local record the application creates when the user accepts a contact card. It is stored
only on the user's device and never shared. It combines the identity received from the contact card with annotations,
preferences, and activity metadata that belong entirely to the recipient.

The contact entry is not a replica of the contact card — it is a separate, local-only structure. The contact card
describes who someone is. The contact entry describes how you relate to them on your device.

## Fields

| Field        | Required | Type       | Description                                                                                                     |
|--------------|----------|------------|-----------------------------------------------------------------------------------------------------------------|
| `nodeId`     | Yes      | `string`   | Primary key. Derived locally from the contact's `sigKey`. See [contact.md — Node ID](1-contact.md).             |
| `trustLevel` | Yes      | `enum`     | Controls how content from this contact is handled. Defaults to `TRUSTED`. See [Trust levels](#trust-levels).    |
| `addedAt`    | Yes      | `long`     | Unix epoch milliseconds. When the contact card was first accepted.                                              |
| `name`       | No       | `string`   | Local override for the contact's name. If set, shown instead of the name from the contact card.                 |
| `lastSeenAt` | No       | `long`     | Unix epoch milliseconds. When content or a card from this contact was last received, directly or via a carrier. |
| `notes`      | No       | `string`   | Free-text field for the user's own reference. Never shared. Max 1024 chars.                                     |
| `pinned`     | No       | `boolean`  | Whether this contact is pinned to the top of the contact list. Defaults to `false`.                             |
| `muted`      | No       | `boolean`  | If `true`, no notifications are generated for content from this contact. Defaults to `false`.                   |
| `tags`       | No       | `string[]` | User-defined labels for organising contacts (e.g. `"family"`, `"work"`, `"local"`). Max 10 tags, 32 chars each. |

## Trust levels

The `trustLevel` field governs how the application handles content attributed to this contact. It is set by the user
at acceptance time and can be changed at any time.

| Value     | Meaning                                                                                                                                             |
|-----------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| `TRUSTED` | Content is received, stored, and propagated according to the user's propagation strategy. Private messages are accepted. This is the default.       |
| `KNOWN`   | Content is received and stored but not actively propagated. Useful for acquaintances or public figures the user wants to follow without amplifying. |
| `BLOCKED` | Content from this Node ID is ignored entirely and not stored. Existing content from this contact is removed from the local store immediately.       |

Changing a contact to `BLOCKED` takes effect immediately and applies retroactively to content already in the local
store.

Content from unknown Node IDs — identities not in the contact list — follows a separate, configurable policy
described in the propagation spec.

## Management

- **Rename.** Setting `name` overrides the name from the contact card locally. The override is never shared
  and does not affect how the contact presents themselves to others.
- **Delete.** Removing a contact entry deletes the local record. It does not prevent that contact's content from
  re-arriving in the future via other carriers. To permanently suppress content from a Node ID, set `trustLevel` to
  `BLOCKED` before deleting.
- **Export.** The contact's identity can be re-exported as a contact card (containing only the fields from the
  original card, with no local-only data) so the user can share a known contact's identity with someone else. This
  is a manual action and requires explicit user intent.

## References

- [Web of trust — Wikipedia](https://en.wikipedia.org/wiki/Web_of_trust)
