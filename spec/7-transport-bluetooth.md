# Bluetooth LE Transport

BLE is packet-based, low-throughput, and subject to OS-imposed background restrictions. It is used for both
the contact exchange bootstrap (see [3-contact-exchange.md](3-contact-exchange.md)) and for general message
propagation when LAN is unavailable.

> [!NOTE]
> **Scope note.** This document currently contains only the content migrated from
> [5-transport.md](5-transport.md). A full BLE specification — GATT service layout, characteristic UUIDs,
> peer discovery, connection management, and platform-specific behaviour — will be written once LAN transport
> development is further along.

## Fragmentation

Because BLE imposes a small ATT MTU, Frames must be fragmented at the Link Adapter level. The reassembly
buffer is maintained per `streamId`. Frames arriving out of order are buffered until the complete message
can be reassembled.

## iOS background restrictions

On iOS, background BLE operation is restricted. When the app is backgrounded, the Link Adapter must queue
pending outbound Frames and flush them when the app returns to the foreground or the OS grants a background
task slot.

## References

- [Bluetooth Low Energy — Wikipedia](https://en.wikipedia.org/wiki/Bluetooth_Low_Energy)
- [Generic Attribute Profile (GATT) — Wikipedia](https://en.wikipedia.org/wiki/Bluetooth_Low_Energy#GATT_profile)
