# Optical Transport (screen-to-camera)

Optical transport is unidirectional: one device displays a visual code, the other scans it. It is used for:

- Bootstrapping a connection when no other transport is available.
- Identity and key exchange where QR code capacity is sufficient.
- Small critical messages in environments where all radio transports are unavailable.

Because optical transport is unidirectional, it cannot support a live handshake. It uses
[`StatelessEnvelope`](5-transport.md#statelessenvelope) instead.

> [!NOTE]
> **Scope note.** This document currently contains only the content migrated from
> [5-transport.md](5-transport.md). A full optical specification — QR code capacity limits, display
> and scanning behaviour, multi-frame sequencing, and error recovery — will be written once LAN transport
> development is further along.

## References

- [QR code — Wikipedia](https://en.wikipedia.org/wiki/QR_code)
