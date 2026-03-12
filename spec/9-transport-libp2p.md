# libp2p Transport

The libp2p transport enables Freepath devices to exchange messages over the public internet using the
[libp2p](https://libp2p.io/) peer-to-peer networking stack. It complements the LAN transport (spec 6)
by reaching peers that are not on the same local network.

> [!NOTE]
> **Scope note.** This document specifies the application-layer message format and security model for the
> libp2p transport only. Peer discovery via the Kademlia DHT, relay reservation, NAT traversal (DCUtR),
> and the underlying Noise transport encryption are implementation concerns not repeated here.

## Overview

The libp2p transport uses a request-response messaging protocol layered on top of a Noise-encrypted
connection. Noise provides transport-layer confidentiality and peer authentication at the libp2p level.
This document specifies the additional **freepath application-layer security** applied on top: end-to-end
encryption with ChaCha20-Poly1305 and sender authentication with Ed25519, using the same key material as
all other Freepath transports.

> [!NOTE]
> **Forward secrecy trade-off.** The application-layer encryption uses `StatelessEnvelope` (see
> [5-transport.md](5-transport.md)), which derives the encryption key from static long-term `encKey`
> pairs rather than ephemeral session keys. This provides no per-session forward secrecy: compromise of a
> device's `encKeyPrivate` exposes all past messages encrypted to or from that device. The LAN transport
> provides forward secrecy via ephemeral handshake keys; the libp2p transport does not. A future version
> may migrate to session keys (see [Migration path](#migration-path)).

## Message format

Every message sent over the libp2p transport begins with a **type byte** that identifies the message
kind. Receivers MUST inspect this byte before any further parsing.

| Type byte | Name                           | Description                                                              |
|-----------|--------------------------------|--------------------------------------------------------------------------|
| `0x01`    | Chat (plaintext)               | Reserved; MUST NOT be sent; receivers MUST drop and warn                 |
| `0x02`    | Contact exchange request       | LAN-only; see [Contact exchange messages](#contact-exchange-messages)    |
| `0x03`    | Contact exchange response      | LAN-only; see [Contact exchange messages](#contact-exchange-messages)    |
| `0xFF`    | Encrypted envelope             | A `StatelessEnvelope` (spec 5); see [Encrypted messages](#encrypted-messages) |

All other type byte values are reserved. Receivers MUST drop messages with unrecognised type bytes
without closing the connection.

## Encrypted messages

Chat messages MUST be sent as encrypted envelopes (type `0xFF`). Sending an unencrypted chat message
(type `0x01`) is a protocol violation; receivers MUST drop it and log a warning.

### Wire layout

```
┌─────────────────────────────────────────────────────────┐
│  type byte: 0xFF  (1 byte)                              │
├─────────────────────────────────────────────────────────┤
│  StatelessEnvelope — UTF-8 JSON (variable length)       │
│                                                         │
│  Fields (defined in 5-transport.md):                    │
│    schema        integer   envelope format version (1)  │
│    senderId      string    Base58-encoded sender nodeId │
│    receiverId    string    Base58-encoded receiver nodeId│
│    timestamp     integer   Unix epoch milliseconds      │
│    nonce         string    Base64-encoded 12-byte nonce │
│    fragmentIndex integer   always 0                     │
│    fragmentCount integer   always 1                     │
│    payload       string    Base64-encoded ciphertext    │
│    signature     string    Base64-encoded Ed25519 sig   │
└─────────────────────────────────────────────────────────┘
```

`fragmentIndex` and `fragmentCount` are always `0` and `1` respectively; the libp2p request-response
protocol handles framing and reliability, so fragmentation at this layer is unnecessary.

### Plaintext content

The plaintext wrapped inside the envelope is the inner message, beginning with its own type byte:

| Inner type | Description                      |
|------------|----------------------------------|
| `0x01`     | Chat: `[0x01][UTF-8 text bytes]` |

### Key derivation

Key derivation follows the `StatelessEnvelope` scheme defined in [5-transport.md](5-transport.md):

```
shared_secret = X25519(sender.encKeyPrivate, receiver.encKeyPublic)
key           = HKDF-SHA256(
                    ikm    = shared_secret,
                    salt   = zeros(32),
                    info   = "freepath-stateless-v1" ∥ senderIdRaw ∥ receiverIdRaw,
                    outLen = 32,
                )
nonce         = random(12)
ciphertext    = ChaCha20-Poly1305.encrypt(key, nonce, plaintext, AAD)
```

`AAD` and the full field encoding are defined in [5-transport.md](5-transport.md).

### Signature

The Ed25519 signature covers `AAD ∥ ciphertext` (not the plaintext) and is produced with the sender's
`sigKeyPrivate`. Receivers MUST verify the signature using the `sigKeyPublic` on file in their contact
list for the claimed `senderId`. Receivers MUST NOT trust the received key — the contact list is
authoritative.

Receivers MUST also verify that `receiverId` matches their own `nodeId` and reject the envelope if it
does not. This prevents replay of a valid envelope to a different contact.

Messages whose `senderId` is not in the receiver's contact list MUST be dropped silently. This is not
an error condition: it can occur legitimately when a relay-connected peer identification event fires
before the contact exchange has been completed.

### Replay protection

Replay protection follows the `StatelessEnvelope` scheme defined in [5-transport.md](5-transport.md).
Receivers MUST reject envelopes older than a configurable threshold; the recommended default is 7 days.
Receivers MUST also reject envelopes whose `timestamp` is more than a small clock-skew tolerance (e.g.
5 minutes) in the future.

> [!NOTE]
> **Implementation note.** Unlike the store-carry-forward transports (optical, BLE), the libp2p
> transport is online-only. Implementations MAY choose a shorter backward window (e.g. 1 hour) if they
> do not expect legitimately delayed delivery over libp2p.

## Contact exchange messages

Contact exchange messages (types `0x02` and `0x03`) are **LAN-only** and are intentionally **not**
wrapped in a `StatelessEnvelope`.

**Why not encrypted.** Two independent bootstrapping problems prevent `StatelessEnvelope` encryption:

1. The contact exchange request (type `0x02`) is sent to a stranger whose `ContactCard` — and
   therefore `encKeyPublic` — the initiator does not yet hold. ECDH is impossible without the
   receiver's public key.
2. The contact exchange response (type `0x03`) carries the responder's `ContactCard` as its payload.
   The initiator cannot decrypt the envelope to read B's keys because B's keys are the encrypted
   content — a circular dependency.

**Why LAN-only.** Contact exchange is a proximity gesture: two people in the same physical space
deliberately exchange contact details. Accepting exchange messages from internet-relay connections
would allow a remote attacker to inject unsolicited exchange requests. Receivers MUST silently drop
any contact exchange message that did not arrive over a LAN connection (i.e. whose remote address
does not indicate a direct TCP connection — no `p2p-circuit` in the connection multiaddr).

**Existing security.** Exchange messages are authenticated by the Ed25519 signature embedded in the
`SignedContactCard` payload, as specified in [3-contact-exchange.md](3-contact-exchange.md). The
Noise transport layer additionally protects exchange content from network-level interception.

> [!NOTE]
> **Future improvement.** A future version of this spec may introduce application-layer encryption
> for exchange messages, for example using an anonymous ephemeral Diffie-Hellman key embedded in
> the request so the responder can encrypt their reply. Until then the LAN-only constraint and
> `SignedContactCard` authentication are the applicable security controls.

### Contact exchange request wire layout

```
[0x02][4-byte BE pin_len][pin bytes][SignedContactCard — JSON-encoded UTF-8 bytes]
```

### Contact exchange response wire layout

```
[0x03][SignedContactCard — JSON-encoded UTF-8 bytes]
```

`SignedContactCard` encoding is defined in [3-contact-exchange.md](3-contact-exchange.md).

## Security considerations

**Transport vs. application encryption.** All libp2p connections are encrypted and authenticated at the
transport layer by the Noise protocol using the libp2p keypair (derived from `sigKeyPrivate`). The
`StatelessEnvelope` adds a second, independent layer of encryption using the freepath `encKey` /
`sigKey` material. An attacker who compromises the Noise layer does not compromise the freepath
application layer, and vice versa.

**Relay opacity.** When a connection is routed through a libp2p relay node, the relay sees only
Noise-encrypted bytes. The relay cannot read application-layer content. The `StatelessEnvelope` adds
a further layer so that even a compromised or malicious relay cannot decrypt message content.

**No forward secrecy.** As noted in the Overview, the `StatelessEnvelope` key is derived from static
long-term `encKey` pairs. Compromise of a device's `encKeyPrivate` exposes all past messages. The LAN
transport's ephemeral handshake keys do not have this property. See [Migration path](#migration-path).

**No TOFU.** Signature verification uses only keys already present in the receiver's contact list. A
message from an unknown peer is dropped. This is consistent with the HandshakeHandler policy in
[5-transport.md](5-transport.md).

**Contact exchange LAN-only constraint.** Contact exchange messages MUST only be accepted from peers
connected via a direct LAN connection. Implementations MUST drop any `0x02` or `0x03` message
received over a relay circuit (`p2p-circuit` in the connection multiaddr). This prevents remote
attackers from sending unsolicited exchange requests via internet relay. Exchange messages are
authenticated by the `SignedContactCard` signature and protected at the network level by Noise; the
PIN provides the proximity confirmation that is the exchange's security core.

## Migration path

The current implementation uses `StatelessEnvelope` (stateless, per-message ECDH). A future version
may migrate to a session-key approach (HandshakeHandler + AeadCodec) for reduced per-message overhead
and per-session forward secrecy. When that migration occurs:

- The `0xFF` outer type byte is retained.
- A version byte is placed immediately after `0xFF` to distinguish the stateless format (version `0x01`)
  from the session-key format (version `0x02`). Receivers dispatch on this byte before parsing the
  remainder of the payload.
- The session-key format uses a two-frame freepath handshake (as specified in
  [5-transport.md](5-transport.md)) exchanged over the libp2p request-response protocol, followed by
  `AeadCodec` frames for all subsequent messages.
- No changes are required to message dispatching above the `0xFF` handler, contact lookup, or the
  exchange flow.

## References

- [libp2p — docs.libp2p.io](https://docs.libp2p.io/)
- [Noise Protocol Framework — Wikipedia](https://en.wikipedia.org/wiki/Noise_Protocol_Framework)
- [Kademlia — Wikipedia](https://en.wikipedia.org/wiki/Kademlia)
- [ChaCha20-Poly1305 — Wikipedia](https://en.wikipedia.org/wiki/ChaCha20-Poly1305)
- [HKDF — Wikipedia](https://en.wikipedia.org/wiki/HKDF)
- [X25519 — Wikipedia](https://en.wikipedia.org/wiki/Curve25519)
- [Ed25519 — Wikipedia](https://en.wikipedia.org/wiki/EdDSA)
