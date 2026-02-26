# LAN Transport

LAN is the primary Freepath transport. It is reliable, high-throughput, bidirectional, and available across all
platforms without special OS permissions. This document specifies how devices discover each other on a local
network, how TCP connections are established, and how the Freepath wire envelope is carried over those connections.

> [!NOTE]
> **Scope note.** This document covers only LAN-specific behaviour. The Frame format, handshake, Link Adapter
> Packet structure, and session lifecycle are specified in [5-transport.md](5-transport.md).

## Peer discovery

Devices discover each other using Multicast DNS (mDNS) / DNS-SD service advertisement. A device announces
its presence by registering a service of type `_freepath._tcp.local.` and includes its Node ID in the TXT
record. Discovering devices use this information to decide whether to initiate a connection.

### Service advertisement

A device MUST advertise its service only when it is willing to accept inbound connections. It MUST withdraw
the advertisement when it is no longer accepting connections (e.g. app backgrounded, shutting down).

The service instance name SHOULD be the device's human-readable name followed by a random 4-hex-digit suffix
to reduce collision probability, e.g. `Alice's iPhone (a3f2)._freepath._tcp.local.`

**TXT record fields:**

| Key      | Required | Description                                                                                                                                                                                     |
|----------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `v`      | Yes      | Protocol version, currently `1`. Discovering peers MUST ignore advertisements whose `v` value is not supported, without attempting to connect.                                                  |
| `nodeId` | Yes      | Base58-encoded Node ID of the advertising device. Used by discovering peers to check their contact list before connecting. Not signed; see [Security considerations](#security-considerations). |

The **SRV record** carries the hostname and TCP port the device is listening on. The port is chosen dynamically
by the OS; there is no fixed default port.

### Connection initiation

Upon discovering a service advertisement, a device:

1. Decodes the `nodeId` from the TXT record.
2. Checks whether this `nodeId` exists in the local contact list. If not, the advertisement is ignored and
   no connection is attempted.
3. Checks whether an active session with this peer already exists. If a live session is already established,
   no new connection is initiated.
4. Opens a TCP connection to the host and port from the SRV record.
5. Initiates the handshake as described in [5-transport.md](5-transport.md).

Only one active TCP connection per peer pair is maintained at a time. If both devices discover each other
simultaneously and both attempt to connect, the duplicate connection MUST be detected after the handshake
completes (both sides now know each other's verified `nodeId`) and one connection MUST be closed. The
connection opened by the peer with the lexicographically smaller `nodeId` SHOULD be retained.

### Advertisement lifecycle

| Event                         | Action                                                             |
|-------------------------------|--------------------------------------------------------------------|
| App comes to foreground       | Begin advertising; start accepting inbound connections             |
| App goes to background        | Withdraw advertisement; close idle connections                     |
| User disables LAN transport   | Withdraw advertisement; close all LAN connections                  |
| Handshake timeout or error    | Close the TCP connection; do not withdraw advertisement            |
| `seq` approaches `0xFFFFFFFF` | Send CLOSE frame; close TCP connection; re-advertise if applicable |

## Wire envelope

Each Link Adapter Packet is wrapped in a binary wire envelope before being sent over TCP. Because TCP is a
stream protocol, framing relies entirely on the `LENGTH` field — there are no message boundaries in the
stream. Receivers MUST read exactly `LENGTH` bytes for `PAYLOAD` after reading the fixed 10-byte header.

```
MAGIC (4 bytes) | VERSION (1 byte) | TYPE (1 byte) | LENGTH (4 bytes, big-endian) | PAYLOAD (LENGTH bytes)
```

| Field     | Size     | Description                                                                                                                                                                                                                                                                                                  |
|-----------|----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `MAGIC`   | 4 bytes  | The sequence `0x46 0x52 0x45 0x45` (`FREE` in ASCII), uniquely identifying the Freepath protocol. Receivers MUST close the connection immediately if the magic bytes do not match, without reading any further fields.                                                                                       |
| `VERSION` | 1 byte   | Wire envelope format version, currently `1`. Versions the binary layout of this envelope (field order, header size), distinct from the Frame's `schema` field inside `PAYLOAD`. Receivers MUST close the connection immediately upon receiving an unsupported `VERSION`, without reading any further fields. |
| `TYPE`    | 1 byte   | Unauthenticated routing hint mapping to the Frame `type` field. Allows routing decisions without parsing the full Frame. Receivers MUST NOT act on this value — including closing the connection — before the inner Frame has passed AEAD verification.                                                      |
| `LENGTH`  | 4 bytes  | Byte length of `PAYLOAD`. Receivers MUST enforce an implementation-defined maximum and close the connection immediately if exceeded, without reading `PAYLOAD`.                                                                                                                                              |
| `PAYLOAD` | variable | A serialised Link Adapter Packet as defined in [5-transport.md](5-transport.md).                                                                                                                                                                                                                             |

## Connection management

**Keep-alive.** Implementations SHOULD enable TCP keep-alive on all Freepath connections to detect silent
peer disconnection promptly, avoiding stale session state.

**Reconnection.** If a TCP connection drops and the peer is still discoverable via mDNS, the Session
Dispatcher MAY initiate a new TCP connection and perform a fresh handshake. The new session MUST use a fresh
`streamId` and reset `seq` to 0, as required by [5-transport.md](5-transport.md).

**Idle timeout.** Implementations MAY close connections that have carried no traffic for an
implementation-defined idle period. A CLOSE frame MUST be sent before closing an idle connection so the peer
can cleanly discard its session state.

**Inbound connection limit.** Implementations MUST enforce an implementation-defined maximum on the number
of concurrent inbound TCP connections to prevent resource exhaustion from connection floods.

## Security considerations

**mDNS advertisements are unauthenticated.** The `nodeId` in the TXT record is not signed. An attacker on
the same LAN could advertise a forged `nodeId` to provoke a connection attempt from a target device. This
is harmless: the handshake requires both peers to be in each other's contact lists, so any connection from
an unknown or misrepresented peer is rejected at the handshake stage without revealing any session material.

**TCP pre-handshake exposure.** A TCP connection is established before the handshake completes, briefly
exposing the local IP address and port to any peer on the LAN. Implementations MUST close the connection
and free all associated resources if the handshake does not complete within the implementation-defined
timeout specified in [5-transport.md](5-transport.md).

**Connection flood.** Any device on the LAN can open a TCP connection before being rejected by the
handshake. Implementations MUST enforce the inbound connection limit above and SHOULD rate-limit connection
attempts from a single source IP address to reduce the cost of handshake-flood attacks.

## References

- [Multicast DNS (mDNS) — Wikipedia](https://en.wikipedia.org/wiki/Multicast_DNS)
- [DNS-SD — Wikipedia](https://en.wikipedia.org/wiki/Zero-configuration_networking#DNS-SD)
- [Transmission Control Protocol — Wikipedia](https://en.wikipedia.org/wiki/Transmission_Control_Protocol)
- [Zero-configuration networking — Wikipedia](https://en.wikipedia.org/wiki/Zero-configuration_networking)
- [TCP keepalive — Wikipedia](https://en.wikipedia.org/wiki/Keepalive#TCP_keepalive)
