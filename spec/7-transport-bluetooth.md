# Bluetooth LE Transport

BLE is packet-based, low-throughput, and subject to OS-imposed background restrictions. It is used for both
the contact exchange bootstrap (see [3-contact-exchange.md](3-contact-exchange.md)) and for general message
propagation when LAN is unavailable.

> [!NOTE]
> **Scope note.** A full BLE specification — peer discovery and connection management — will be written
> once LAN transport development is further along.

## Fragmentation

Because BLE imposes a small ATT MTU, Frames must be fragmented at the Link Adapter level. The reassembly
buffer is maintained per `streamId`. Frames arriving out of order are buffered until the complete message
can be reassembled.

## iOS background restrictions

On iOS, background BLE operation is restricted. When the app is backgrounded, the Link Adapter must queue
pending outbound Frames and flush them when the app returns to the foreground or the OS grants a background
task slot.

## Contact exchange — GATT service layout

Contact exchange over BLE uses the Freepath GATT service. The service UUID and both
characteristic UUIDs share the same Freepath namespace prefix:

| Name         | UUID                                   | Properties      |
|--------------|----------------------------------------|-----------------|
| Service      | `81e2d89b-f75f-4c72-95c4-8db84b24bf11` | —               |
| `CARD_READ`  | `81e2d89b-f75f-4c72-95c4-8db84b24bf12` | Read            |
| `CARD_WRITE` | `81e2d89b-f75f-4c72-95c4-8db84b24bf13` | Write           |

`CARD_READ` returns the device's own signed contact card bytes (the wire format produced
by `ContactSignedCodec.encode()`). `CARD_WRITE` accepts the peer's signed contact
card bytes in the same format.

Both characteristics carry raw bytes with no additional framing. Cards larger than the
ATT MTU are split automatically by the platform GATT stack using `PREPARE_WRITE` /
`EXECUTE_WRITE` (write with response).

## Contact exchange — flow

Both devices must have the Exchange via Bluetooth screen open (see
[3-contact-exchange.md](3-contact-exchange.md)).

```
+---------+                          +---------+
|  Alice  |                          |   Bob   |
| (init.) |                          | (resp.) |
+---------+                          +---------+
    |  advertise + host GATT service      |
    |<----------------------------------->|
    |                                     |
    |  discover Bob in scan results       |
    |                                     |
    |  GATT connect ───────────────────>  |
    |  CARD_READ  (read) <──────────────  |  Bob's card bytes
    |  CARD_WRITE (write) ─────────────>  |  Alice's card bytes
    |  GATT disconnect ────────────────>  |
    |                                     |
    |  verify Bob's card                  |  verify Alice's card
    |  confirmation screen                |  confirmation screen
```

Alice is the **initiator**: she selects Bob from the discovered-peer list. Bob is the
**responder**: his GATT server passively supplies his card and receives Alice's.

The initiator must perform GATT operations in order: connect → read CARD_READ →
write CARD_WRITE → disconnect. The responder's confirmation screen is triggered
when CARD_WRITE receives data.

## Platform permissions

- **Android**: requires `BLUETOOTH`, `BLUETOOTH_ADMIN` (API < 31) or `BLUETOOTH_CONNECT`
  + `BLUETOOTH_ADVERTISE` + `BLUETOOTH_SCAN` (API ≥ 31) permissions in the manifest.
- **iOS**: requires `NSBluetoothAlwaysUsageDescription` in `Info.plist` (iOS 13+; also add
  `NSBluetoothPeripheralUsageDescription` for iOS 12 backward compatibility).

## References

- [Bluetooth Low Energy — Wikipedia](https://en.wikipedia.org/wiki/Bluetooth_Low_Energy)
- [Generic Attribute Profile (GATT) — Wikipedia](https://en.wikipedia.org/wiki/Bluetooth_Low_Energy#GATT_profile)
