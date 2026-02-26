# Transport

Freepath is a store-carry-forward network. Messages move between devices over whatever physical medium is available —
LAN, Bluetooth LE, an optical channel, or anything else that can carry bytes. No transport is assumed to be always
present, bidirectional, or reliable.

This spec covers the **Link Adapter** and **Transport / Physical** layers, plus the **Frame** abstraction that
connects them. Everything above is the Application Layer and is specified separately.

## Layer model

The diagram below shows the layers and the transformations that happen at each one as a message travels from
the application down to the wire. Unidirectional transports (optical, sound, USB) bypass the normal Frame stack
entirely and use `StatelessEnvelope` instead — a deliberate breach of the layering model made necessary by the
physical impossibility of a handshake on those transports.

```
┌──────────────────────────────────────────────────────────┐
│                       Application Layer                  │
│                                                          │
│   Posts       Messages      Reactions      Comments      │
│   ┌────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│   │type    │  │type      │  │type      │  │type      │   │
│   │body    │  │body      │  │emoji     │  │body      │   │
│   │authorId│  │authorId  │  │authorId  │  │authorId  │   │
│   │...     │  │...       │  │...       │  │...       │   │
│   └────────┘  └──────────┘  └──────────┘  └──────────┘   │
└──────────────────────────────┬───────────────────────────┘
                               │  raw content object
           ┌───────────────────┴───────────────────┐
           │ bidirectional transport?              │
          YES                                      NO
           │                                       │
           ▼                                       ▼
┌────────────────────────────┐       ┌─────────────────────────────┐
│  Protocol / Framing Layer  │       │      StatelessEnvelope      │
│                            │       │                             │
│  - assigns streamId, seq   │       │  - derives key from static  │
│  - encrypts with session   │       │    long-term encKey pair    │
│    key (ChaCha20-Poly1305  │       │  - random nonce per msg     │
│  - wraps into a Frame      │       │  - Ed25519 signature        │
│                            │       │  - built-in fragmentation   │
│  { schema, streamId,       │       │    (fragmentIndex/Count)    │
│    seq, type, payload }    │       │  - replay via timestamp     │
└────────────┬───────────────┘       │    + (senderId, nonce)      │
             │  one Frame            └──────────────┬──────────────┘
             ▼                                      │  one or more envelopes
┌───────────────────────────┐                       │
│   Link Adapter Layer      │                       │
│                           │                       │
│  Frame fits in MTU?       │                       │
│                           │                       │
│  YES → single packet      │                       │
│  NO  → split into N       │                       │
│  ┌─────────┐ ┌─────────┐  │                       │
│  │SEQ IDX  │ │SEQ IDX  │  │                       │
│  │CNT data │ │CNT data │  │                       │
│  └─────────┘ └─────────┘  │                       │
└────────────┬──────────────┘                       │
             │  Link Adapter Packets                │
             └──────────────┬───────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                     Transport / Physical                    │
│                                                             │
│       LAN (TCP)          BLE              Optical (QR)      │
│   ┌────────────┐    ┌──────────┐    ┌───────────────────┐   │
│   │MAGIC|VER   │    │ATT write │    │ StatelessEnvelope │   │
│   │TYPE |LEN   │    │packets   │    │ displayed as QR   │   │
│   │PAYLOAD     │    │          │    │                   │   │
│   └────────────┘    └──────────┘    └───────────────────┘   │
└──────────────────────────────┬──────────────────────────────┘
                               │  bytes on the wire / air / screen
                               ▼
                            peer
```

> [!NOTE]
> **Layering trade-off.** `StatelessEnvelope` is a deliberate exception to the transport-agnostic design: because
> unidirectional transports cannot perform a handshake, encryption key derivation, authentication, fragmentation,
> and replay protection all differ from the normal Frame path. The alternative — requiring a bidirectional channel
> for all communication — would make optical and similar transports impossible. The forward secrecy guarantee is
> also weaker: the session key in the Frame path is ephemeral and discarded after the session, while the
> `StatelessEnvelope` key is derived from static long-term keys and is persistent.

Each transport is implemented as a Link Adapter — a self-contained component that fragments outbound Frames to fit
the transport's MTU, reassembles inbound fragments, and handles retries where the transport supports them. Adding a
new transport means writing a new Link Adapter; the Frame format, the handshake, and everything built on top stay
exactly as they are.

## Frame

A **Frame** is the logical unit of data produced by the Framing Layer and consumed by the Link Adapter. Every
Frame is:

- Self-contained and independently parseable.
- Sequence-numbered within a logical stream.
- Versioned, so the framing format can evolve.

If a serialised Frame exceeds the transport's MTU, the Link Adapter splits it into chunks for transmission and
reassembles them on the receiving side. Fragmentation metadata lives in the
[Link Adapter Packet](#link-adapter-packet), not in the Frame itself.

```
{
  "schema":   <framing format version, e.g. 1>,
  "streamId": "<Base58-encoded stream identifier>",
  "seq":      <sequence number, uint32, starts at 0>,
  "type":     "<frame type, see below>",
  "payload":  "<Base64-encoded bytes>"
}
```

| Field      | Required | Type     | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
|------------|----------|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `schema`   | Yes      | `int`    | Framing format version. Allows the framing format to evolve without breaking existing receivers.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `streamId` | Yes      | `string` | Identifies the logical stream this Frame belongs to. Scoped to a single peer pair.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `seq`      | Yes      | `uint32` | Sequence number of the logical message within this stream. Unsigned 32-bit integer, starts at 0. The monotonicity counter applies only to post-handshake frames (DATA, ACK, CLOSE) and is scoped to each direction independently; it is initialised fresh at session establishment (logically equivalent to "last accepted = −1", so the first DATA/ACK/CLOSE with `seq`=0 MUST be accepted). Receivers MUST reject any frame whose `seq` is not strictly greater than the last accepted `seq` in the same stream, preventing replay of captured frames. A session MUST be torn down and a new handshake performed before `seq` reaches `0xFFFFFFFF` to prevent nonce reuse. |
| `type`     | Yes      | `string` | Frame type. See [Frame types](#frame-types) below.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `payload`  | Yes      | `string` | Base64-encoded payload bytes. Interpreted according to `type`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |

### Frame types

| Type        | Description                                                                                                                                                                                                                                                                                                                                    |
|-------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `HANDSHAKE` | Carries handshake material (ephemeral public keys, identity proof). See [Handshake](#handshake).                                                                                                                                                                                                                                               |
| `DATA`      | Carries an encrypted application message.                                                                                                                                                                                                                                                                                                      |
| `ACK`       | Acknowledges receipt of a sequence number. Used on transports that support bidirectionality. After the handshake completes, ACK frames MUST be encrypted and authenticated identically to DATA frames. The plaintext payload is the 4-byte big-endian `seq` value being acknowledged.                                                          |
| `CLOSE`     | Signals orderly session teardown. After the handshake completes, CLOSE frames MUST be encrypted and authenticated identically to DATA frames. A CLOSE received after session establishment that fails authentication MUST be rejected. The plaintext payload is empty (0 bytes); its presence and successful AEAD verification are the signal. |

**Payload summary by frame type:**

| Type        | Payload content                                      | Encrypted |
|-------------|------------------------------------------------------|-----------|
| `HANDSHAKE` | `EPHEMERAL_KEY \| SIGKEY \| NODEID_RAW \| SIGNATURE` | No        |
| `DATA`      | Application message ciphertext                       | Yes       |
| `ACK`       | 4-byte big-endian `seq` being acknowledged           | Yes       |
| `CLOSE`     | Empty (0 bytes) — presence alone is the signal       | Yes       |

### Link Adapter Packet

The Link Adapter wraps each serialised Frame — or a fragment of one — in a binary packet before handing it to the
transport. This is the actual unit transmitted on the wire or over the air.

```
SEQ (4 bytes, big-endian) | FRAG_INDEX (2 bytes, big-endian) | FRAG_COUNT (2 bytes, big-endian) | LENGTH (4 bytes, big-endian) | DATA (LENGTH bytes)
```

- `SEQ`: Sequence number matching the `seq` field of the Frame being transmitted. Allows the receiver to reassemble
  fragments of the same logical message.
- `FRAG_INDEX`: Zero-based index of this fragment. `0` if the Frame was not fragmented.
- `FRAG_COUNT`: Total number of fragments for this `SEQ`. `1` means the Frame was not fragmented.
- `LENGTH`: Byte length of `DATA`.
- `DATA`: A contiguous chunk of the serialised Frame bytes.

The receiver buffers incoming packets by `SEQ` and reassembles `DATA` chunks in `FRAG_INDEX` order before
parsing the Frame. Packets with the same `SEQ` arriving from different streams are distinguished by the
transport connection or channel — the packet format itself is scoped to a single peer pair.

Receivers MUST enforce an implementation-defined maximum on `FRAG_COUNT` and reject packets that exceed it.
Receivers MUST also reject any packet where `FRAG_COUNT` is 0 or where `FRAG_INDEX >= FRAG_COUNT`.
Reassembly state for a given `SEQ` MUST be discarded if all fragments have not arrived within an
implementation-defined timeout, freeing the associated buffer. These limits are necessary to prevent memory
exhaustion from malformed or malicious packets.

### Handshake

The handshake establishes a shared session key between two peers and authenticates both identities. It takes place
over a bidirectional transport (LAN or BLE). Optical and other unidirectional transports skip the handshake and use
`StatelessEnvelope` instead.

**Flow:**

1. **Initiator** generates an ephemeral X25519 key pair and sends `HANDSHAKE` Frame 0 containing:
    - Ephemeral public key.
    - Own `nodeId`.
    - Own long-term `sigKey` public key.
    - A signature over `(EPHEMERAL_KEY ∥ NODEID_RAW)` as defined in
      the [HANDSHAKE payload format](#handshake-payload-format), using the long-term Ed25519 private key.

2. **Responder** looks up the claimed `nodeId` in their local contact list, verifies that the received `sigKey`
   matches the key on file, and verifies the signature using the `sigKey` retrieved from the contact list — not
   the received key. If no matching contact exists, the key does not match, or the signature is invalid, the
   handshake MUST be aborted. The Responder then generates their own ephemeral X25519 key pair and sends
   `HANDSHAKE` Frame 1 containing the same fields.

3. **Initiator** applies the same checks on `HANDSHAKE` Frame 1: looks up the Responder's `nodeId`, verifies
   the received `sigKey` matches the contact list, and verifies the signature using the `sigKey` from the contact
   list. If any check fails, the handshake MUST be aborted.

4. Both sides perform X25519 Diffie-Hellman between their own ephemeral private key and the peer's ephemeral public
   key. Implementations MUST verify that the resulting shared secret is not the all-zeros value; a zero output
   indicates the peer supplied a low-order point and the handshake MUST be aborted. They derive a session key
   using HKDF-SHA-256 with:
    - **IKM**: the X25519 shared secret.
    - **Salt**: 32 zero bytes (the HKDF-SHA-256 default).
    - **Info**: the concatenation of Frame 0's raw HANDSHAKE payload bytes and Frame 1's raw HANDSHAKE payload
      bytes (the `payload` field of each Frame, Base64-decoded), binding the derived key to this specific exchange
      and preventing a MITM from manipulating handshake messages undetected.
    - **OKM length**: 32 bytes.

5. All subsequent Frames in the session carry payloads encrypted with ChaCha20-Poly1305 using the session key.
   The nonce for each Frame is the frame's `seq` value zero-padded to 12 bytes (big-endian), guaranteeing nonce
   uniqueness for the lifetime of the session. The AEAD Additional Authenticated Data (AAD) is the concatenation
   of `schema` (4-byte big-endian) ∥ `seq` (4-byte big-endian) ∥ `type` (1-byte length prefix + UTF-8 bytes)
   ∥ `streamId` (UTF-8 bytes). Binding all header fields to the ciphertext ensures any modification to `schema`,
   `seq`, `type`, or `streamId` in transit causes AEAD verification to fail. Schema version 1 mandates
   ChaCha20-Poly1305; future schema versions may introduce cipher negotiation.

Implementations MUST discard any DATA, ACK, or CLOSE frame received before the handshake has completed.
Implementations MUST discard any HANDSHAKE frame received after the handshake has completed; a replayed or
retransmitted HANDSHAKE MUST NOT cause re-derivation of the session key or any other change to session state.
Implementations MUST also abandon incomplete handshake state and release all associated resources if the
handshake is not completed within an implementation-defined timeout.

#### HANDSHAKE payload format

The `payload` field of a HANDSHAKE Frame (Base64-decoded) is a fixed-length binary structure:

```
EPHEMERAL_KEY (32 bytes) | SIGKEY (32 bytes) | NODEID_RAW (16 bytes) | SIGNATURE (64 bytes)
```

- `EPHEMERAL_KEY`: the sender's ephemeral X25519 public key.
- `SIGKEY`: the sender's long-term Ed25519 public key.
- `NODEID_RAW`: the raw 16-byte Node ID: `SHA-256(sigKey)[0..15]`.
- `SIGNATURE`: Ed25519 signature over `(EPHEMERAL_KEY ∥ NODEID_RAW)` using the sender's long-term Ed25519
  private key.

Neither ephemeral private key is ever transmitted. The long-term identity keys authenticate the handshake but are
not used for encryption — that role belongs to the ephemeral keys. A passive observer who records the session cannot
decrypt it later even if they later obtain the long-term private keys. Implementations MUST NOT cache or reuse
session keys across handshakes; each new connection MUST perform a full handshake to derive a fresh session key,
ensuring the `seq`-derived nonce sequence always begins at 0 under a distinct key.

### StatelessEnvelope

> [!NOTE]
> **Scope note.** Contact exchange (QR codes carrying raw contact cards, NFC session tokens bootstrapping BLE) is a
> pre-transport concern specified in [3-contact-exchange.md](3-contact-exchange.md). It operates before any contact
> relationship exists and does not use the Frame or `StatelessEnvelope` formats. `StatelessEnvelope` applies only
> after a contact relationship has been established — the sender must already be in the receiver's contact list.

Some transports — optical (screen-to-camera), sound, USB — are unidirectional: data flows one way only, with no
back-channel for the receiver to respond. A live handshake is impossible on these transports because it requires
both sides to exchange messages.

A `StatelessEnvelope` solves this. It is a self-contained message that carries everything the receiver needs to
authenticate and decrypt it — sender identity, encrypted payload, and a signature — with no prior session. The only
prerequisite is that the sender is already in the receiver's contact list, so the receiver holds the sender's public
keys locally.

```
{
  "schema":         <schema version, e.g. 1>,
  "senderId":       "<Base58-encoded Node ID of the sender>",
  "receiverId":     "<Base58-encoded Node ID of the intended recipient>",
  "timestamp":      <Unix epoch milliseconds>,
  "nonce":          "<Base64-encoded random nonce, 12 bytes>",
  "fragmentIndex":  <zero-based fragment index, 0 if not fragmented>,
  "fragmentCount":  <total fragments, 1 if not fragmented>,
  "payload":        "<Base64-encoded encrypted bytes (ChaCha20-Poly1305)>",
  "signature":      "<Base64-encoded Ed25519 signature over all other fields>"
}
```

| Field           | Required | Type     | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
|-----------------|----------|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `schema`        | Yes      | `int`    | Schema version.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `senderId`      | Yes      | `string` | Node ID of the sender. The receiver looks up the sender's `sigKey` and `encKey` from their local contact list.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `receiverId`    | Yes      | `string` | Node ID of the intended recipient. The receiver MUST verify this matches their own `nodeId` and reject the envelope if it does not. Binds the envelope to a specific receiver, preventing replay to a different contact.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `timestamp`     | Yes      | `long`   | Unix epoch milliseconds. Used for replay protection. Receivers MUST reject envelopes older than a configurable threshold. Receivers MUST also reject envelopes whose `timestamp` is more than a small clock-skew tolerance (e.g. 5 minutes) in the future, preventing senders from extending the validity window by setting future timestamps.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `nonce`         | Yes      | `string` | Random 12-byte nonce, unique per envelope. Prevents ciphertext reuse. Receivers MUST track seen `(senderId, nonce)` pairs within the replay-protection window and reject any envelope whose pair has already been processed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `fragmentIndex` | Yes      | `int`    | Zero-based index of this fragment within a multi-envelope message. `0` if the message was not split. Receivers MUST reject any envelope where `fragmentIndex >= fragmentCount`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `fragmentCount` | Yes      | `int`    | Total number of envelopes in this message. `1` means the message was not split. Receivers MUST reject any envelope where `fragmentCount` is 0.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `payload`       | Yes      | `string` | ChaCha20-Poly1305 encrypted content (schema version 1). Encryption key derived via HKDF-SHA-256(IKM=X25519(sender\_enc\_private, receiver\_enc\_public), Salt=32 zero bytes, Info="freepath-stateless-v1" ∥ senderId\_raw ∥ receiverId\_raw, OKM=32 bytes), where senderId\_raw and receiverId\_raw are the Base58-decoded Node ID bytes. Implementations MUST verify the X25519 output is not all-zeros before calling HKDF; a zero result indicates a low-order sender encKey and the envelope MUST be rejected. The `nonce` field is used directly as the ChaCha20-Poly1305 nonce. The AAD is the concatenation of signature input fields 1–7 (encoded as in the signature input table): `schema` ∥ senderId\_raw ∥ receiverId\_raw ∥ `timestamp` ∥ nonce\_raw ∥ `fragmentIndex` ∥ `fragmentCount`. This binds the ciphertext to its envelope context at the AEAD layer, complementing the Ed25519 signature. |
| `signature`     | Yes      | `string` | Ed25519 signature by the sender over all other fields using the canonical input defined below. Verifiable without a handshake.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |

**Signature input:** The Ed25519 signature is computed over the following byte sequence (fields concatenated in this
order, no separators):

| # | Field           | Encoding                            | Size     |
|---|-----------------|-------------------------------------|----------|
| 1 | `schema`        | 4-byte big-endian integer           | 4 bytes  |
| 2 | `senderId`      | Base58-decoded Node ID bytes        | 16 bytes |
| 3 | `receiverId`    | Base58-decoded Node ID bytes        | 16 bytes |
| 4 | `timestamp`     | 8-byte big-endian integer           | 8 bytes  |
| 5 | `nonce`         | Base64-decoded raw bytes            | 12 bytes |
| 6 | `fragmentIndex` | 4-byte big-endian integer           | 4 bytes  |
| 7 | `fragmentCount` | 4-byte big-endian integer           | 4 bytes  |
| 8 | `payload`       | Base64-decoded raw ciphertext bytes | variable |

All fixed-length fields precede the variable-length `payload`, making the input unambiguous without length prefixes.

Because a `StatelessEnvelope` is self-contained, it can be encoded in a QR code, displayed on screen, and scanned
by any device that holds the sender's contact card. No session, no handshake, no reply channel required.

If the content to be transmitted exceeds the QR code capacity, it is split across multiple envelopes using
`fragmentIndex` and `fragmentCount`. Upon receiving each fragment, the receiver MUST verify its Ed25519 signature
before buffering the payload; fragments that fail verification MUST be discarded immediately. Once all fragments have
arrived and their signatures verified, the receiver decrypts each fragment's `payload`
independently using that fragment's `nonce`, then concatenates the resulting plaintexts in `fragmentIndex` order.

> [!NOTE]
> **Forward secrecy trade-off.** The `StatelessEnvelope` encryption key is derived from the static long-term
> `encKey` pair of both parties. Unlike the session key established by the handshake (which uses ephemeral
> keys and is discarded after the session), this key is persistent. If either party's `encKey` private key is
> compromised in the future, an attacker who recorded past envelopes can decrypt them. This is an inherent
> consequence of the stateless, no-handshake design and cannot be avoided without a prior interactive exchange.

## Transports

### LAN

LAN is the primary development transport. It is reliable, high-throughput, bidirectional, and works across all
platforms without special OS permissions.
**Wire envelope:**

```
MAGIC (4 bytes) | VERSION (1 byte) | TYPE (1 byte) | LENGTH (4 bytes, big-endian) | PAYLOAD (LENGTH bytes)
```

- `MAGIC`: the 4-byte sequence `0x46 0x52 0x45 0x45` (`FREE` in ASCII), uniquely identifying the Freepath
  protocol. Receivers MUST close the connection immediately if the magic bytes do not match, without reading
  any further fields.
- `VERSION`: wire envelope format version, currently `1`. Versions the binary layout of the envelope itself (field
  order, header size). Distinct from the Frame's `schema` field, which versions the logical message format inside
  `PAYLOAD`. The two can evolve independently. Receivers MUST close the connection immediately upon receiving an
  unsupported `VERSION` value, without reading any further fields.
- `TYPE`: unauthenticated routing hint that maps to the Frame `type` field, allowing routing without parsing
  the full Frame. Receivers MUST NOT act on this value — including closing the connection — before the inner
  Frame has passed AEAD verification.
- `LENGTH`: byte length of `PAYLOAD`. Receivers MUST enforce an implementation-defined maximum and close the
  connection immediately if it is exceeded, without reading `PAYLOAD`.
- `PAYLOAD`: a serialised Link Adapter Packet.

LAN peers discover each other via mDNS service advertisement. A device advertises its service only while it is
willing to accept connections.

### Bluetooth LE

BLE is packet-based, low-throughput, and subject to OS-imposed background restrictions. It is used for both the
contact exchange bootstrap (see [3-contact-exchange.md](3-contact-exchange.md)) and for general message propagation
when LAN is unavailable.

Because BLE imposes a small ATT MTU, Frames must be fragmented at the Link Adapter level. The reassembly buffer is
maintained per `streamId`. Frames arriving out of order are buffered until the complete message can be reassembled.

On iOS, background BLE operation is restricted. When the app is backgrounded, the Link Adapter must queue pending
outbound Frames and flush them when the app returns to the foreground or the OS grants a background task slot.

### Optical (screen-to-camera)

Optical transport is unidirectional: one device displays a visual code, the other scans it. It is used for:

- Bootstrapping a connection when no other transport is available.
- Identity and key exchange where QR code capacity is sufficient.
- Small critical messages in environments where all radio transports are unavailable.

Optical transport cannot support a live handshake. Instead, it uses a [StatelessEnvelope](#statelessenvelope).

### Other transports

USB and sound-based transports follow the same principles as optical transport: each is represented as a Link
Adapter that produces and consumes Frames. Neither requires changes to any layer above the Link Adapter.

## References

- [Store and forward — Wikipedia](https://en.wikipedia.org/wiki/Store_and_forward)
- [Bluetooth Low Energy — Wikipedia](https://en.wikipedia.org/wiki/Bluetooth_Low_Energy)
- [QR code — Wikipedia](https://en.wikipedia.org/wiki/QR_code)
- [Multicast DNS (mDNS) — Wikipedia](https://en.wikipedia.org/wiki/Multicast_DNS)
- [Diffie–Hellman key exchange — Wikipedia](https://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange)
- [Curve25519 — Wikipedia](https://en.wikipedia.org/wiki/Curve25519)
- [Ed25519 — Wikipedia](https://en.wikipedia.org/wiki/EdDSA)
- [HKDF — Wikipedia](https://en.wikipedia.org/wiki/HKDF)
- [Authenticated encryption — Wikipedia](https://en.wikipedia.org/wiki/Authenticated_encryption)
- [ChaCha20-Poly1305 — Wikipedia](https://en.wikipedia.org/wiki/ChaCha20-Poly1305)
- [Forward secrecy — Wikipedia](https://en.wikipedia.org/wiki/Forward_secrecy)
- [Packet fragmentation — Wikipedia](https://en.wikipedia.org/wiki/IP_fragmentation)
