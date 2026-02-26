# Transport

Freepath is a store-carry-forward network. Messages move between devices over whatever physical medium is available —
LAN, Bluetooth LE, an optical channel, or anything else that can carry bytes. No transport is assumed to be always
present, bidirectional, or reliable.

This spec covers the **Link Adapter** and **Transport / Physical** layers, plus the **Frame** abstraction that
connects them. Everything above is the Application Layer and is specified separately.

## Layer model

```
┌───────────────────────────────┐
│       Application Layer       │
│  Chat, hub, AI, UX            │
└───────────────────────────────┘
                ▲
┌───────────────────────────────┐
│    Protocol / Framing Layer   │
│  Frames, versioning, types    │
└───────────────────────────────┘
                ▲
┌───────────────────────────────┐
│       Link Adapter Layer      │
│  Fragmentation / reassembly   │
│  Sequence numbers             │
│  Retry / ACKs                 │
│  Handles unidirectional       │
└───────────────────────────────┘
                ▲
┌───────────────────────────────┐
│      Transport / Physical     │
│  ┌─────────┬───────────────┐  │
│  │ LAN     │ Reliable      │  │
│  │ BLE     │ connections   │  │
│  │ QR      │ Unidirectional│  │
│  │ Screen  │ transport     │  │
│  │ USB     │ file transfer │  │
│  │ Sound   │ audio pulses  │  │
│  └─────────┴───────────────┘  │
└───────────────────────────────┘
```

Each transport is implemented as a Link Adapter — a self-contained component that fragments outbound Frames to fit
the transport's MTU, reassembles inbound fragments, and handles retries where the transport supports them. Adding a
new transport means writing a new Link Adapter; the Frame format, the handshake, and everything built on top stay
exactly as they are.

## Frame

A **Frame** is the unit of data exchanged between the Framing Layer and the Link Adapter. Every Frame is:

- Self-contained and independently parseable.
- Sequence-numbered within a logical stream.
- Versioned, so the framing format can evolve.
- Optionally fragmented if the payload exceeds the transport's MTU.

```
{
  "schema":        <framing format version, e.g. 1>,
  "streamId":      "<Base58-encoded stream identifier>",
  "seq":           <sequence number, integer, starts at 0>,
  "fragmentIndex": <fragment index within this seq, starting at 0>,
  "fragmentCount": <total fragments for this seq>,
  "type":          "<frame type, see below>",
  "payload":       "<Base64-encoded bytes>"
}
```

| Field           | Required | Type     | Description                                                                                      |
|-----------------|----------|----------|--------------------------------------------------------------------------------------------------|
| `schema`        | Yes      | `int`    | Framing format version. Allows the framing format to evolve without breaking existing receivers. |
| `streamId`      | Yes      | `string` | Identifies the logical stream this Frame belongs to. Scoped to a single peer pair.               |
| `seq`           | Yes      | `int`    | Sequence number of the logical message within this stream.                                       |
| `fragmentIndex` | Yes      | `int`    | Zero-based index of this fragment within the fragmented message.                                 |
| `fragmentCount` | Yes      | `int`    | Total number of fragments for this `seq`. `1` means the message was not fragmented.              |
| `type`          | Yes      | `string` | Frame type. See [Frame types](#frame-types) below.                                               |
| `payload`       | Yes      | `string` | Base64-encoded payload bytes. Interpreted according to `type`.                                   |

### Frame types

| Type        | Description                                                                                      |
|-------------|--------------------------------------------------------------------------------------------------|
| `HANDSHAKE` | Carries handshake material (ephemeral public keys, identity proof). See [Handshake](#handshake). |
| `DATA`      | Carries an encrypted application message.                                                        |
| `ACK`       | Acknowledges receipt of a sequence number. Used on transports that support bidirectionality.     |
| `CLOSE`     | Signals orderly session teardown.                                                                |

### Handshake

The handshake establishes a shared session key between two peers and authenticates both identities. It takes place
over a bidirectional transport (LAN or BLE). Optical and other unidirectional transports skip the handshake and use
`StatelessEnvelope` instead.

**Flow:**

1. **Initiator** generates an ephemeral X25519 key pair and sends `HANDSHAKE` Frame 0 containing:
    - Ephemeral public key.
    - Own `nodeId`.
    - Own long-term `sigKey` public key.
    - A signature over (ephemeral public key ∥ nodeId) using the long-term Ed25519 private key.

2. **Responder** verifies the signature, generates their own ephemeral X25519 key pair, and sends `HANDSHAKE`
   Frame 1 containing the same fields.

3. Both sides perform X25519 Diffie-Hellman between their own ephemeral private key and the peer's ephemeral public
   key. They derive a session key using HKDF over the resulting shared secret.

4. All subsequent Frames in the session carry payloads encrypted with the session key using AES-GCM or
   ChaCha20-Poly1305.

Neither ephemeral private key is ever transmitted. The long-term identity keys authenticate the handshake but are
not used for encryption — that role belongs to the ephemeral keys. A passive observer who records the session cannot
decrypt it later even if they later obtain the long-term private keys.

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
  "schema":    <schema version, e.g. 1>,
  "senderId":  "<Base58-encoded Node ID of the sender>",
  "timestamp": <Unix epoch milliseconds>,
  "nonce":     "<Base64-encoded random nonce, 12 bytes>",
  "payload":   "<Base64-encoded encrypted bytes (AES-GCM or ChaCha20-Poly1305)>",
  "signature": "<Base64-encoded Ed25519 signature over schema, senderId, timestamp, nonce, payload>"
}
```

| Field       | Required | Type     | Description                                                                                                          |
|-------------|----------|----------|----------------------------------------------------------------------------------------------------------------------|
| `schema`    | Yes      | `int`    | Schema version.                                                                                                      |
| `senderId`  | Yes      | `string` | Node ID of the sender. The receiver looks up the sender's `sigKey` and `encKey` from their local contact list.       |
| `timestamp` | Yes      | `long`   | Unix epoch milliseconds. Used for replay protection. Receivers reject envelopes older than a configurable threshold. |
| `nonce`     | Yes      | `string` | Random 12-byte nonce, unique per envelope. Prevents ciphertext reuse.                                                |
| `payload`   | Yes      | `string` | Encrypted content. Encrypted with a key derived from the sender's `encKey` and the receiver's `encKey` via X25519.   |
| `signature` | Yes      | `string` | Ed25519 signature by the sender over all other fields. Verifiable without a handshake.                               |

Because a `StatelessEnvelope` is self-contained, it can be encoded in a QR code, displayed on screen, and scanned
by any device that holds the sender's contact card. No session, no handshake, no reply channel required.

If the content to be transmitted exceeds the QR code capacity, it must be split into a sequence of envelopes. Each
envelope carries a fragment index and a total count. The receiver scans all fragments before attempting to decrypt.

## Transports

### LAN

LAN is the primary development transport. It is reliable, high-throughput, bidirectional, and works across all
platforms without special OS permissions.
**Wire envelope:**

```
MAGIC (4 bytes) | VERSION (1 byte) | TYPE (1 byte) | LENGTH (4 bytes, big-endian) | PAYLOAD (LENGTH bytes)
```

- `MAGIC`: fixed bytes identifying the Freepath protocol.
- `VERSION`: wire format version, currently `1`.
- `TYPE`: maps to the Frame `type` field.
- `LENGTH`: byte length of `PAYLOAD`.
- `PAYLOAD`: a serialised Frame.

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

- [Bluetooth Low Energy — Wikipedia](https://en.wikipedia.org/wiki/Bluetooth_Low_Energy)
- [QR code — Wikipedia](https://en.wikipedia.org/wiki/QR_code)
- [Multicast DNS (mDNS) — Wikipedia](https://en.wikipedia.org/wiki/Multicast_DNS)
- [Diffie–Hellman key exchange — Wikipedia](https://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange)
- [Curve25519 — Wikipedia](https://en.wikipedia.org/wiki/Curve25519)
- [HKDF — Wikipedia](https://en.wikipedia.org/wiki/HKDF)
- [AES-GCM (Galois/Counter Mode) — Wikipedia](https://en.wikipedia.org/wiki/Galois/Counter_Mode)
- [ChaCha20-Poly1305 — Wikipedia](https://en.wikipedia.org/wiki/ChaCha20-Poly1305)
- [Forward secrecy — Wikipedia](https://en.wikipedia.org/wiki/Forward_secrecy)
- [Packet fragmentation — Wikipedia](https://en.wikipedia.org/wiki/IP_fragmentation)
