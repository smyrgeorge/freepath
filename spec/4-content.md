# Content

Content is any piece of information authored by a user and propagated through the network. Every piece of content
is cryptographically signed by its author, stored locally on each device that carries it, and travels through the
network via the store, carry, forward model — no server, no central repository.

All text fields across all content types are Markdown-enabled. Authors can use formatting, links, lists, and code
blocks wherever text is accepted.

## Content types

Freepath supports the following content types:

| Type         | Description                                                                                                                   |
|--------------|-------------------------------------------------------------------------------------------------------------------------------|
| **Post**     | A short Markdown message. The primary unit of expression in the network.                                                      |
| **Article**  | Long-form Markdown content with a title. No strict length limit, subject to local storage policies.                           |
| **Link**     | A URL with an optional Markdown description. The linked resource is not fetched or stored — only the reference travels.       |
| **Image**    | A single image with an optional Markdown caption. Supported formats: JPEG, WebP, PNG.                                         |
| **Comment**  | A Markdown response to a piece of content or to another comment. Forms a tree of discussion attached to the original content. |
| **Reaction** | A lightweight response (upvote or downvote) attached to a piece of content or comment.                                        |

Video is intentionally excluded at this stage. A phone is not a data center — the network prioritises content that
fits comfortably in a pocket.

## Content envelope

Every piece of content, regardless of type, is wrapped in a common envelope. The envelope carries the metadata
needed to verify, route, deduplicate, and attribute the content. The body carries the type-specific payload.

```
{
  "id":              "<Base58-encoded content ID>",
  "schema":          <schema version, e.g. 1>,
  "type":            "<post | article | link | image | comment | reaction>",
  "authorId":        "<Base58-encoded Node ID of the author>",
  "version":         <content version, starting at 1>,
  "prevId":          "<Base58-encoded ID of the previous version, optional>",
  "createdAt":       <Unix epoch milliseconds>,
  "expiresAt":       <Unix epoch milliseconds, optional>,
  "commentsEnabled": <true | false>,
  "hops":            <number of devices this content has passed through>,
  "signature":       "<Base64-encoded Ed25519 signature over the canonical envelope>",
  "body":            { ... }
}
```

| Field             | Required | Type      | Description                                                                                                                                                             |
|-------------------|----------|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `id`              | Yes      | `string`  | Content ID. Derived from a hash of the canonical body. Globally unique. See [Content ID](#content-id).                                                                  |
| `schema`          | Yes      | `int`     | Schema version. Allows future evolution of the format.                                                                                                                  |
| `type`            | Yes      | `string`  | One of: `post`, `article`, `link`, `image`, `comment`, `reaction`.                                                                                                      |
| `authorId`        | Yes      | `string`  | Node ID of the author. Used for attribution, deduplication, and trust evaluation.                                                                                       |
| `version`         | Yes      | `int`     | Version of this content item, starting at `1`. Incremented with each edit. See [Editing](#editing).                                                                     |
| `prevId`          | No       | `string`  | ID of the immediately preceding version of this content. Absent on the first version. Forms the edit chain.                                                             |
| `createdAt`       | Yes      | `long`    | Unix epoch milliseconds. When the content was originally authored. Set once and never changed across edits.                                                             |
| `expiresAt`       | No       | `long`    | Unix epoch milliseconds. After this time, carriers are free to evict the content. See [Expiry](#expiry).                                                                |
| `commentsEnabled` | Yes      | `boolean` | Whether comments are permitted on this content. Applies only to top-level types. Always `false` for `comment` and `reaction`.                                           |
| `hops`            | Yes      | `int`     | Number of device-to-device hops since the content left the author's device. Incremented by each carrier. Not signed — treat as informational only.                      |
| `signature`       | Yes      | `string`  | Ed25519 signature by the author over a canonical serialisation of the envelope (excluding the `hops` field). Verifiable by any device that holds the author's `sigKey`. |
| `body`            | Yes      | `object`  | Type-specific payload. See [Content bodies](#content-bodies).                                                                                                           |

## Content ID

The content ID is a stable, globally unique identifier for a specific version of a piece of content. It is derived
from the canonical body at authoring time and never changes:

```
id = Base58( SHA-256( canonical_body ) )
```

Because the ID is derived from the body, two devices that receive the same content independently will always arrive
at the same ID. This makes deduplication trivial: a device that already holds a given ID can discard any duplicate
it receives without inspecting the full payload.

Each edit produces a new ID. The chain of IDs linked by `prevId` forms the full edit history.

## Content bodies

### Post

```
{
  "text": "<Markdown, max 1024 chars>"
}
```

### Article

```
{
  "title": "<Markdown, max 128 chars>",
  "body":  "<Markdown>"
}
```

### Link

```
{
  "url":         "<URL, max 2048 chars>",
  "title":       "<Markdown, max 128 chars, optional>",
  "description": "<Markdown, max 512 chars, optional>"
}
```

The linked resource is not fetched, proxied, or stored. Only the reference and its optional metadata travel
through the network.

### Image

```
{
  "data":    "<Base64-encoded image>",
  "format":  "<jpeg | webp | png>",
  "width":   <pixels>,
  "height":  <pixels>,
  "caption": "<Markdown, max 512 chars, optional>"
}
```

| Constraint        | Value           |
|-------------------|-----------------|
| Max file size     | 2 MB            |
| Max dimensions    | 2048×2048 px    |
| Supported formats | JPEG, WebP, PNG |

### Comment

A comment is a Markdown response attached to a piece of content. Comments are themselves content — they carry the
same envelope structure, travel through the network independently, and are assembled into a tree locally by each
device. Comments can also be edited; each edit follows the same `version` / `prevId` chain as any other content.

```
{
  "parentId": "<Base58-encoded ID of the parent content or parent comment>",
  "text":     "<Markdown, max 1024 chars>"
}
```

The `parentId` can reference either a top-level content item or another comment, enabling arbitrarily deep
discussion trees. The tree is assembled locally from whatever comments the device has received — it may be
incomplete if some comments have not yet propagated to the receiving device.

Comments are only valid on content where the latest version has `commentsEnabled: true`. A comment referencing
a content item whose latest known version has comments disabled is rejected and not stored.

### Reaction

A reaction is a lightweight response attached to a piece of content or comment. Each user can hold at most one
reaction per content item — a newer reaction from the same author replaces the previous one via the standard edit
chain.

```
{
  "parentId": "<Base58-encoded ID of the target content or comment>",
  "value":    "<up | down>"
}
```

Reactions are aggregated locally. Because different devices may have received different subsets of reactions,
counts are always an approximation based on what has propagated to that device.

## Comments

Comments form a discussion tree attached to a root content item. Each comment references its parent via `parentId`.
The root of the tree is the original content item. Any comment can itself be the parent of further comments.

```
Post (root)
├── Comment A
│   ├── Comment A1
│   └── Comment A2
│       └── Comment A2a
└── Comment B
    └── Comment B1
```

Because comments propagate independently through the network, a device may receive comments out of order or without
their parent. In this case:

- A comment whose parent is not yet in the local store is held in a **pending** state.
- When the missing parent arrives, the pending comment is attached to the tree.
- A comment held in pending state for longer than a configurable threshold is discarded.

### Comment moderation

The author of the root content controls whether comments are permitted via the `commentsEnabled` flag. Because
this flag is part of the signed envelope, changing it requires publishing a new version of the content (incrementing
`version` and setting `prevId`).

Comments created while `commentsEnabled` was `true` are permanent — there is no network-wide mechanism to delete
them once they have propagated. This is an intentional design property: the network has no central authority to
issue deletions.

A user may delete a comment from their own local store at any time. If every device independently chooses to delete
a comment, it will naturally disappear from the network. This is the only form of distributed deletion available.

## Editing

Every piece of content — including comments and reactions — can be edited after publication. Each edit produces a
new content item with a new `id`, an incremented `version`, and a `prevId` pointing to the immediately preceding
version. The chain of versions linked by `prevId` forms the full edit history.

```
v1 (id: AAA, version: 1, prevId: —   )
 └── v2 (id: BBB, version: 2, prevId: AAA)
      └── v3 (id: CCC, version: 3, prevId: BBB)
```

When a device receives a new version of a content item, it replaces the displayed content with the latest version
it holds. Older versions are retained in the local store for history but are not actively propagated.

The `createdAt` field is fixed at the original authoring time and never changes across versions. The `id` of v1
serves as the stable logical identifier for the content across its entire edit history — comments and reactions
always reference the `id` of the version they were created against, not the root v1 id.

## Expiry

Content can carry an optional `expiresAt` timestamp. After this time:

- Carriers are free to evict the content from their local store.
- Incoming expired content is not stored.
- Expired content is not actively propagated.

Expiry is not enforced by a central authority — it is a cooperative signal that devices honour voluntarily. It is
particularly useful for time-sensitive content (event announcements, alerts) and for hubs that want to manage
storage automatically without manual curation.

## Visibility

Content can be **public**, **private**, or **access-controlled**:

| Visibility            | Description                                                                                                                                                                         |
|-----------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Public**            | Signed but not encrypted. Any device that receives it can read it. Propagated according to the carrier's strategy.                                                                  |
| **Private**           | Encrypted for a specific recipient using their `encKey`. Intermediate carriers can store and forward it but cannot read it. Only the intended recipient can decrypt it.             |
| **Access-controlled** | Encrypted with a symmetric key distributed out-of-band. Any device that holds the key can decrypt it. Designed for hubs that publish content accessible only to authorised members. |

A private or access-controlled piece of content uses the same envelope structure but its `body` is replaced with
an encrypted blob. The `authorId` and `type` fields remain visible to intermediate carriers so they can route and
deduplicate without decrypting.

Private and access-controlled content always has `commentsEnabled: false`.

The mechanism for distributing access-control keys (e.g. how a hub shares its key with authorised devices) is
outside the scope of this document and will be addressed in the hubs spec.

## Deletion

Deleting content removes it from the local store. There is no network-wide deletion mechanism — the network has no
central authority to instruct. If other devices still carry the content, it may re-arrive in the future via a
carrier. To prevent re-storage, the receiving device can mark the content ID as permanently suppressed.

Deleting a root content item does not automatically delete its comments or reactions. Comments and reactions
referencing a deleted or missing root are held in pending state and eventually discarded per the pending threshold.

## Size and storage

Every device has finite storage. Content competes for space with contact cards, message history, and propagation
metadata. Local storage policies — what to keep, what to evict — are described in the propagation spec. The
following limits apply at authoring time:

| Type     | Max body size |
|----------|---------------|
| Post     | ~1 KB         |
| Article  | 512 KB        |
| Link     | ~3 KB         |
| Image    | 2 MB          |
| Comment  | ~1 KB         |
| Reaction | ~256 bytes    |

These limits apply to the body only. The envelope adds a fixed overhead of approximately 512 bytes.
