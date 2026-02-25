# Contact

A **contact** is another Freepath user whose cryptographic identity you have explicitly accepted onto your device. The
contact list is the local record of every identity you trust.

## Keys

Every Freepath identity is backed by two cryptographic keys, both derived from a single 32-byte seed generated once
on the device:

| Key                | Algorithm | Purpose                                                                                                   |
|--------------------|-----------|-----------------------------------------------------------------------------------------------------------|
| **Signing key**    | Ed25519   | Signs contact cards, posts, and all outgoing content. Lets any recipient verify authorship and integrity. |
| **Encryption key** | X25519    | Used for Diffie-Hellman key agreement when encrypting private messages end-to-end.                        |

Both keys are derived deterministically from the same seed, so the user has a single identity conceptually — the app
manages the two derived keys internally. The seed never leaves the device. The two public keys are the only key
material that is ever shared.

Ed25519 and X25519 operate on the same underlying curve (Curve25519), so both keys can be derived from a single seed
with no loss of security.

The contact card carries both public keys. Recipients use the signing public key to verify the card's signature and
all future content from this identity, and the encryption public key to encrypt private messages intended for them.

## Contact card

A **contact card** is the self-contained bundle of data that represents a user's public identity. It is the only thing
shared during a connection — no content, no message history, no device metadata beyond what is listed here.

```
{
  "schema":     <schema version, e.g. 1>,
  "sigKey":     "<Base64-encoded Ed25519 public key>",
  "encKey":     "<Base64-encoded X25519 public key>",
  "updatedAt":  <Unix epoch milliseconds>,
  "name":       "<Display name, UTF-8, optional>",
  "bio":        "<Short description, UTF-8, optional>",
  "avatar":     "<Base64-encoded WebP image, optional>",
  "location":   "<Free-text location hint, optional>"
}
```

| Field       | Required | Type     | Max size  | Description                                                                                                                                                   |
|-------------|----------|----------|-----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `schema`    | Yes      | `int`    | —         | Schema version. Allows future evolution of the format.                                                                                                        |
| `sigKey`    | Yes      | `string` | —         | Ed25519 public key, Base64-encoded. Used to verify the signature on this card and on all content attributed to this identity.                                 |
| `encKey`    | Yes      | `string` | —         | X25519 public key, Base64-encoded. Used by senders to derive a shared secret when encrypting private messages for this user.                                  |
| `updatedAt` | Yes      | `long`   | —         | Unix epoch timestamp in milliseconds of the last change to the card. Used by recipients to determine whether a re-received card is newer than the stored one. |
| `name`      | No       | `string` | 64 chars  | A human-readable display name chosen by the owner. Not globally unique.                                                                                       |
| `bio`       | No       | `string` | 256 chars | A short description the user writes about themselves.                                                                                                         |
| `avatar`    | No       | `string` | 64 KB     | Base64-encoded WebP image, square, no larger than 512×512 px. Larger images are cropped to a square by the UI.                                                |
| `location`  | No       | `string` | 128 chars | A free-text location hint (e.g. a city or region). Not a GPS coordinate. Never verified.                                                                      |

All optional fields may be omitted entirely or set to null. A card containing only `schema`, `sigKey`, `encKey`, and
`updatedAt` is valid.

Before transmission the card is signed by the owner's Ed25519 private key. The recipient verifies the signature
against the included `sigKey`. A card that fails verification is silently rejected and never stored.

## Card updates

A contact card may be received more than once — through a second direct encounter, or carried passively by an
intermediate device. When a card arrives for a Node ID that already exists in the local contact list, the application
applies the following rules:

1. **Verify the signature.** The incoming card must carry a valid signature from the same `sigKey` already on file.
   A card signed by a different key for the same Node ID is a conflict and is silently rejected.
2. **Compare timestamps.** The incoming card is accepted only if its `updatedAt` is strictly greater than the stored
   record's `updatedAt`. Equal or older timestamps are ignored — they are either duplicates or replays.
3. **Merge.** If both checks pass, the stored record is updated field by field with the values from the incoming card.
   Local-only fields (`trustLevel`, `addedAt`, `notes`, and any locally overridden display name) are never touched by
   an incoming card update.

This means a user can update their profile — change their display name, bio, avatar, or location — and the new card
will propagate through the network and silently refresh the stored record on every device that already knows them,
the next time their paths cross.

## Node ID

A **Node ID** is a short, stable identifier that uniquely represents a user within the network. It is the handle the
system uses everywhere it needs to refer to a user — in content envelopes, message headers, routing tables, and the
local contact list — without carrying the full public key around.

The Node ID is never transmitted in a contact card. Instead, every device derives it independently and locally from
the signing public key using a fixed, well-specified derivation:

```
nodeId = Base58( SHA-256(sigKey)[0..15] )
```

This produces a 22-character Base58 string (e.g. `4mXkR9qWzJvTsLpYcBnD2e`).

Because the derivation is deterministic and identical on every device, there is no need to trust a Node ID received
from the outside. No card, message, or third party can supply a mismatched or forged Node ID — every device will
always arrive at the same value for a given signing key on its own.

### Where the Node ID is used

- **Content attribution.** Every post carries the author's Node ID in its envelope. When a post arrives, the
  recipient looks up the Node ID in their contact list to attribute it to a known name and verify the signature
  against the stored `sigKey`.
- **Message addressing.** Private messages are addressed to a recipient's Node ID. The sending device looks up the
  Node ID in its contact list to retrieve the `encKey` needed to encrypt the message.
- **Routing.** As a message hops from device to device, intermediate carriers use the destination Node ID to decide
  whether they know the recipient directly or should keep forwarding. Short fixed-length IDs are fast to index and
  compare at each hop.
- **Deduplication.** When the same content arrives from multiple carriers, the Node ID in the envelope is used to
  identify the author and discard duplicates without re-parsing or re-verifying the full public key each time.
- **Contact list indexing.** The local contact list is keyed by Node ID. All internal references — from content,
  messages, trust records, and propagation logs — point to Node IDs rather than raw public keys, keeping storage
  compact and lookups fast.
- **Display and manual verification.** When two users want to confirm they exchanged the right card, they can read
  out or visually compare their Node IDs. A 22-character Base58 string is far more practical for this than a
  44-character Base64 public key.

## References

- [Ed25519 — Wikipedia](https://en.wikipedia.org/wiki/EdDSA)
- [X25519 / Diffie-Hellman key agreement — Wikipedia](https://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange)
- [Curve25519 — Wikipedia](https://en.wikipedia.org/wiki/Curve25519)
- [SHA-256 — Wikipedia](https://en.wikipedia.org/wiki/SHA-2)
- [Base58 — Wikipedia](https://en.wikipedia.org/wiki/Binary-to-text_encoding#Base58)
- [Base64 — Wikipedia](https://en.wikipedia.org/wiki/Base64)
- [Public-key cryptography — Wikipedia](https://en.wikipedia.org/wiki/Public-key_cryptography)
- [Digital signature — Wikipedia](https://en.wikipedia.org/wiki/Digital_signature)
- [End-to-end encryption — Wikipedia](https://en.wikipedia.org/wiki/End-to-end_encryption)
