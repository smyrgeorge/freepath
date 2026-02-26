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
  "seq":      <sequence number, integer, starts at 0>,
  "type":     "<frame type, see below>",
  "payload":  "<Base64-encoded bytes>"
}
```

| Field      | Required | Type     | Description                                                                                      |
|------------|----------|----------|--------------------------------------------------------------------------------------------------|
| `schema`   | Yes      | `int`    | Framing format version. Allows the framing format to evolve without breaking existing receivers. |
| `streamId` | Yes      | `string` | Identifies the logical stream this Frame belongs to. Scoped to a single peer pair.               |
| `seq`      | Yes      | `int`    | Sequence number of the logical message within this stream.                                       |
| `type`     | Yes      | `string` | Frame type. See [Frame types](#frame-types) below.                                               |
| `payload`  | Yes      | `string` | Base64-encoded payload bytes. Interpreted according to `type`.                                   |

### Frame types

| Type        | Description                                                                                      |
|-------------|--------------------------------------------------------------------------------------------------|
| `HANDSHAKE` | Carries handshake material (ephemeral public keys, identity proof). See [Handshake](#handshake). |
| `DATA`      | Carries an encrypted application message.                                                        |
| `ACK`       | Acknowledges receipt of a sequence number. Used on transports that support bidirectionality.     |
| `CLOSE`     | Signals orderly session teardown.                                                                |

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
   key. They derive a session key using HKDF with:
    - **IKM**: the X25519 shared secret.
    - **Info**: the concatenation of the two serialised `HANDSHAKE` frames (initiator's first, responder's second),
      binding the derived key to this specific exchange and preventing a MITM from manipulating handshake messages
      undetected.

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
  "schema":         <schema version, e.g. 1>,
  "senderId":       "<Base58-encoded Node ID of the sender>",
  "receiverId":     "<Base58-encoded Node ID of the intended recipient>",
  "timestamp":      <Unix epoch milliseconds>,
  "nonce":          "<Base64-encoded random nonce, 12 bytes>",
  "fragmentIndex":  <zero-based fragment index, 0 if not fragmented>,
  "fragmentCount":  <total fragments, 1 if not fragmented>,
  "payload":        "<Base64-encoded encrypted bytes (AES-GCM or ChaCha20-Poly1305)>",
  "signature":      "<Base64-encoded Ed25519 signature over all other fields>"
}
```

| Field           | Required | Type     | Description                                                                                                                         |
|-----------------|----------|----------|-------------------------------------------------------------------------------------------------------------------------------------|
| `schema`        | Yes      | `int`    | Schema version.                                                                                                                     |
| `senderId`      | Yes      | `string` | Node ID of the sender. The receiver looks up the sender's `sigKey` and `encKey` from their local contact list.                      |
| `receiverId`    | Yes      | `string` | Node ID of the intended recipient. Binds the envelope to a specific receiver, preventing replay to a different contact.             |
| `timestamp`     | Yes      | `long`   | Unix epoch milliseconds. Used for replay protection. Receivers reject envelopes older than a configurable threshold.                |
| `nonce`         | Yes      | `string` | Random 12-byte nonce, unique per envelope. Prevents ciphertext reuse.                                                               |
| `fragmentIndex` | Yes      | `int`    | Zero-based index of this fragment within a multi-envelope message. `0` if the message was not split.                                |
| `fragmentCount` | Yes      | `int`    | Total number of envelopes in this message. `1` means the message was not split.                                                     |
| `payload`       | Yes      | `string` | Encrypted content. Key derived from `X25519(sender_enc_private, receiver_enc_public)`, uniquely bound to this sender–receiver pair. |
| `signature`     | Yes      | `string` | Ed25519 signature by the sender over all other fields. Verifiable without a handshake.                                              |

Because a `StatelessEnvelope` is self-contained, it can be encoded in a QR code, displayed on screen, and scanned
by any device that holds the sender's contact card. No session, no handshake, no reply channel required.

If the content to be transmitted exceeds the QR code capacity, it is split across multiple envelopes using
`fragmentIndex` and `fragmentCount`. The receiver collects all fragments before attempting to decrypt.

## Transports

### LAN

LAN is the primary development transport. It is reliable, high-throughput, bidirectional, and works across all
platforms without special OS permissions.
**Wire envelope:**

```
MAGIC (4 bytes) | VERSION (1 byte) | TYPE (1 byte) | LENGTH (4 bytes, big-endian) | PAYLOAD (LENGTH bytes)
```

- `MAGIC`: fixed bytes identifying the Freepath protocol.
- `VERSION`: wire envelope format version, currently `1`. Versions the binary layout of the envelope itself (field
  order, header size). Distinct from the Frame's `schema` field, which versions the logical message format inside
  `PAYLOAD`. The two can evolve independently.
- `TYPE`: maps to the Frame `type` field, allowing routing without parsing the full Frame.
- `LENGTH`: byte length of `PAYLOAD`.
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
