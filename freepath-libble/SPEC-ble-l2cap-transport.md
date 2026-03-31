# BLE L2CAP Transport

This spec defines the BLE transport layer used by Freepath for device-to-device
communication over Bluetooth Low Energy. It covers framing, connection management,
request/response routing, peer discovery, and identity token matching.

The transport is built on BLE L2CAP Connection-Oriented Channels (CoC), providing
a bidirectional byte stream between two devices without the overhead and MTU
constraints of GATT characteristics.

## Service UUID

All Freepath BLE advertisements use the 128-bit service UUID:

```
81e2d89b-f75f-4c72-95c4-8db84b24bf10
```

Scanners filter on this UUID to discover Freepath devices.

## Frame Format

All communication over an L2CAP channel uses a length-prefixed binary frame:

```
+-------------------+----------+-----------------+
| Length (4 bytes)   | Type     | Payload        |
| big-endian uint32  | (1 byte) | (Length bytes) |
+-------------------+----------+-----------------+
```

| Field   | Size    | Description                        |
|---------|---------|------------------------------------|
| Length  | 4 bytes | Payload size in bytes (big-endian) |
| Type    | 1 byte  | Frame type identifier              |
| Payload | N bytes | Frame-type-specific data           |

Maximum payload size: 65,536 bytes (enforced by the read loop).

## Frame Types

| Type byte | Name       | Direction             | Description                        |
|-----------|------------|-----------------------|------------------------------------|
| `0x01`    | `EXCHANGE` | Bidirectional         | Contact exchange protocol messages |
| `0x02`    | `REQUEST`  | Initiator → Responder | Application-layer request          |
| `0x03`    | `RESPONSE` | Responder → Initiator | Application-layer response         |
| `0x04`    | `PING`     | Either                | Keepalive probe                    |
| `0x05`    | `PONG`     | Either                | Keepalive response                 |

### REQUEST payload format

```
+-------------------+-------------------+---------------------+---------+
| reqId (8 bytes)   | peerIdLen (1 byte) | peerId (N bytes)   | body    |
| little-endian     |                    | UTF-8 encoded      |         |
+-------------------+-------------------+---------------------+---------+
```

The sender's `peerId` is embedded in the frame header so the receiver can identify
the sender regardless of the OS-assigned BLE peripheral identifier. This is critical
on iOS where `CBCentral.identifier` (inbound connections) differs from
`CBPeripheral.identifier` (scan results) for the same remote device.

### RESPONSE payload format

```
+-------------------+------------------+-----------------------+
| reqId (8 bytes)   | status (1 byte)  | body or error         |
| little-endian     | 0x00=ok, 0x01=err| UTF-8 error or bytes  |
+-------------------+------------------+-----------------------+
```

### PING / PONG payload

Empty (0 bytes). The PING frame is sent by the keepalive loop; the receiver
immediately responds with a PONG frame.

### EXCHANGE payload

Opaque byte array — format defined by the
[Secure Contact Exchange](SPEC-secure-contact-exchange.md) protocol.

## L2CAP Server

Each device runs an L2CAP server that accepts inbound connections:

1. **Start** — the server binds an insecure L2CAP channel (no encryption at L2CAP level;
   application-layer encryption is used instead). The OS assigns a PSM (Protocol/Service
   Multiplexer) value.
2. **Advertise** — the assigned PSM is included in the BLE advertisement so that
   scanning devices can connect (see [Advertisement Format](#advertisement-format)).
3. **Accept** — inbound connections are emitted as `BleL2capChannel` instances and
   registered in the connection pool.
4. **Stop** — the server socket is closed and the PSM is unpublished.

### Platform differences

| Aspect         | Android                                              | iOS                                                                 |
|----------------|------------------------------------------------------|---------------------------------------------------------------------|
| API            | `BluetoothAdapter.listenUsingInsecureL2capChannel()` | `CBPeripheralManager.publishL2CAPChannelWithEncryption(false)`      |
| Accept model   | Blocking `accept()` in a coroutine loop              | Delegate callback (`didOpenL2CAPChannel`) emitted to a `SharedFlow` |
| PSM assignment | Synchronous after `listen`                           | Asynchronous via `didPublishL2CAPChannel` delegate                  |

## L2CAP Channel

A `BleL2capChannel` wraps a platform BLE socket with:

- **Read loop** — runs continuously, decoding length-prefixed frames from the input
  stream and emitting `BleFrame` instances on a `SharedFlow` (buffer capacity: 64).
- **Send** — writes a length-prefixed frame to the output stream.
- **Close** — closes both streams and cancels the read loop.

### Outbound connect

```
BleL2capChannel.connect(peripheralId, psm) → BleL2capChannel
```

| Platform | Implementation                                                                         |
|----------|----------------------------------------------------------------------------------------|
| Android  | `BluetoothDevice.createInsecureL2capChannel(psm)` + `socket.connect()`                 |
| iOS      | `CBCentralManager.retrievePeripherals` → `connectPeripheral` → `openL2CAPChannel(psm)` |

### Inbound accept

Inbound channels are created by the L2CAP server and emitted via its `incoming` flow.
The `peripheralId` is the OS-assigned identifier of the remote device:

- Android: Bluetooth MAC address (e.g. `60:D6:46:B8:CF:A6`)
- iOS: `CBCentral.identifier` UUID string (differs from `CBPeripheral.identifier`)

## Connection Pool

The `BleConnectionPool` manages L2CAP channels with automatic lifecycle management.

### Entry lifecycle

```
   ┌────────────────────────────────────────────────────────┐
   │                                                        │
   │  getOrCreate(peripheralId)                             │
   │  ┌──────────┐    ┌──────────┐    ┌──────────┐          │
   │  │ PSM      │    │ L2CAP    │    │ Enrolled │          │
   │  │ Lookup   │───>│ Connect  │───>│ (active) │          │
   │  └──────────┘    └──────────┘    └──────────┘          │
   │                                       │                │
   │  registerInbound(channel)             │                │
   │  ┌──────────┐    ┌──────────┐         │                │
   │  │ Accept   │───>│ Enrolled │─────────┤                │
   │  │ (server) │    │ (active) │         │                │
   │  └──────────┘    └──────────┘         │                │
   │                                       ▼                │
   │                              ┌──────────────┐          │
   │                              │  Keepalive   │          │
   │                              │  Loop (5s)   │          │
   │                              └──────────────┘          │
   │                                   │    │               │
   │                            ok     │    │  fail         │
   │                                   │    │               │
   │                              ┌────┘    └────┐          │
   │                              ▼              ▼          │
   │                         [continue]    ┌─────────┐      │
   │                                       │Reconnect│      │
   │                                       │(3 tries)│      │
   │                                       └─────────┘      │
   │                                          │    │        │
   │                                     ok   │    │ fail   │
   │                                          ▼    ▼        │
   │                                    [continue] [evict]  │
   └────────────────────────────────────────────────────────┘
```

### Enrollment

Channels enter the pool via two paths:

- **Outbound** — `getOrCreate(peripheralId)` resolves the PSM, connects, and enrolls.
  If another coroutine races to create the same entry, the loser's channel is closed.
- **Inbound** — `registerInbound(channel)` enrolls an accepted channel. Same race
  handling applies.

Both paths start a `receiveLoop` and a `keepaliveLoop` for the entry.

### Keepalive

Every 5 seconds, the keepalive loop:

1. Checks idle timeout (30 seconds since last data) — evicts if exceeded
2. Sends a `PING` frame and waits up to 5 seconds for a `PONG`
3. On PONG failure, attempts reconnect with exponential backoff:
    - Attempt 1: wait 2s, reconnect
    - Attempt 2: wait 4s, reconnect
    - Attempt 3: wait 8s, reconnect
4. If all 3 attempts fail, the entry is evicted

### Channel reuse

A single L2CAP connection is used bidirectionally. When device A connects to device B,
both devices can send REQUEST and RESPONSE frames on the same channel. This avoids
opening a second BLE connection in the opposite direction, which can cause issues on
iOS where simultaneous central + peripheral connections to the same device are unreliable.

Pool entries are tagged with the remote peer's `peerId` (extracted from the first
REQUEST frame's header). The `findEntryForPeer(peerId)` method allows looking up an
existing channel regardless of which `peripheralId` it was registered under.

### Peripheral ID resolution

When sending a request to a peer, `resolvePeripheralId(peerId)` checks four tiers:

| Tier | Source         | Description                                                                           |
|------|----------------|---------------------------------------------------------------------------------------|
| 0    | Pool entry     | Reuse existing channel tagged with this peerId                                        |
| 1    | Routing table  | `blePeripheralId` from the `contact_routing` database table                           |
| 2    | Scan match     | In-memory `peripherals` map where `matchedPeerId == peerId`                           |
| 3    | Token re-match | Compute expected token for this peer's secret and match against unmatched peripherals |

If the primary resolution fails (e.g. stale MAC after Android address rotation), the
`sendRequest` method catches the connection error, removes the stale pool entry, and
retries via `resolvePeripheralIdFallback` which skips tier 1 and goes straight to
token matching.

## Advertisement Format

The BLE advertisement carries the service UUID and the L2CAP PSM (required for
connecting) plus an optional identity token (for peer identification).

### Android

Uses the standard BLE advertisement + scan response split:

| Packet        | Content                                                       |
|---------------|---------------------------------------------------------------|
| Advertisement | Service UUID (128-bit)                                        |
| Scan response | Service data: `[PSM 2 bytes LE]` `[token 8 bytes (optional)]` |

`setIncludeDeviceName(false)` — no device name is broadcast.

### iOS

iOS `CBPeripheralManager.startAdvertising()` only allows `CBAdvertisementDataServiceUUIDsKey`
and `CBAdvertisementDataLocalNameKey`. The PSM and token are encoded in the local name:

| Format        | Example                    |
|---------------|----------------------------|
| Without token | `fp:00c0`                  |
| With token    | `fp:00c0:afa592dc18262ee4` |

The PSM is a 4-character hex string. The token is a 16-character hex string (8 bytes).

### Scan parsing

The scanner handles both formats:

1. If `serviceData(FREEPATH_SERVICE_UUID)` is available and has >= 2 bytes → Android format
2. Otherwise, match the local name against `fp:([0-9a-fA-F]{1,4})(?::([0-9a-fA-F]{16}))?`

## Peer Discovery and Identification

### Discovery

The scan loop runs continuously, filtering for `FREEPATH_SERVICE_UUID`. Each advertisement
updates the in-memory `peripherals` map with:

- `PeripheralDiscovered` event (discoveredAt, rssi, name, etc.)
- PSM (preserved across advertisements if not present in a follow-up)
- Identity token (raw 8 bytes, if present)
- Matched peerId (if token matches a known contact)

Peripherals expire after 30 seconds without a new advertisement.

### Identification via rotating tokens

When a scanned advertisement includes an identity token, the scanner computes the
expected token for each known contact's `identitySecret` and compares. Both the
current and previous 15-minute epoch are checked to handle rotation boundaries.

On first match, a `PeerIdentified` event is emitted and the routing table's
`blePeripheralId` is updated to the current OS-assigned identifier.

See [SPEC-secure-contact-exchange.md](SPEC-secure-contact-exchange.md) for token
derivation details.

## Timings and Constants

| Constant                         | Value        | Description                                    |
|----------------------------------|--------------|------------------------------------------------|
| `KEEPALIVE_INTERVAL`             | 5 seconds    | Time between keepalive pings                   |
| `PONG_TIMEOUT`                   | 5 seconds    | Max wait for a PONG response                   |
| `IDLE_TIMEOUT`                   | 30 seconds   | Evict entry if no data received                |
| `MAX_RECONNECT_ATTEMPTS`         | 3            | Reconnect attempts after ping failure          |
| `RECONNECT_BACKOFF_BASE`         | 2 seconds    | Base delay for exponential backoff             |
| `PSM_WAIT_TIMEOUT`               | 10 seconds   | Max wait for PSM to appear in advertisements   |
| `PSM_RETRY_INTERVAL`             | 500 ms       | Retry interval during PSM wait                 |
| `ADVERTISEMENT_EXPIRE_THRESHOLD` | 30 seconds   | Peripheral removed from discovery map          |
| `TOKEN_ROTATION_CHECK_INTERVAL`  | 1 minute     | How often to check if token epoch changed      |
| `SECRETS_CACHE_INTERVAL_MS`      | 30 seconds   | How long the contact secrets DB cache is valid |
| `MAX_PAYLOAD_SIZE`               | 65,536 bytes | Maximum frame payload size                     |

## References

- [L2CAP — Wikipedia](https://en.wikipedia.org/wiki/Logical_link_control_and_adaptation_protocol)
- [Bluetooth Low Energy — Wikipedia](https://en.wikipedia.org/wiki/Bluetooth_Low_Energy)
- [Core Bluetooth (Apple)](https://developer.apple.com/documentation/corebluetooth)
- [Android Bluetooth](https://developer.android.com/develop/connectivity/bluetooth)
