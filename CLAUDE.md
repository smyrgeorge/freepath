# Freepath

A spec-only repository — no code yet. All work is in `spec/` (Markdown documents).

## Repository structure

| Path        | Purpose                                                  |
|-------------|----------------------------------------------------------|
| `docs/`     | Published HTML documentation                             |
| `spec/`     | Protocol and data model specifications                   |
| `tools/`    | Pandoc templates and Lua filters for PDF/HTML generation |
| `README.md` | Project vision and concept overview                      |

## Spec files

| File                            | Covers                                                                            |
|---------------------------------|-----------------------------------------------------------------------------------|
| `spec/1-contact.md`             | Contact card structure, keys, Node ID derivation, card updates                    |
| `spec/2-contact-entry.md`       | Local-only contact metadata: trust level, name override, tags, mute, pin          |
| `spec/3-contact-exchange.md`    | QR (unidirectional), NFC + Bluetooth (bidirectional), card validation flow        |
| `spec/4-content.md`             | Content types, envelope, editing chain, comments, reactions, expiry, visibility   |
| `spec/5-transport.md`           | Transport layers: Frame, Handshake, StatelessEnvelope, LAN/BLE/optical transports |
| `spec/6-transport-lan.md`       | LAN transport detail: mDNS peer discovery, wire envelope, connection management   |
| `spec/7-transport-bluetooth.md` | BLE transport: fragmentation, iOS background restrictions (stub — to be expanded) |
| `spec/8-transport-optical.md`   | Optical transport: QR / screen-to-camera, StatelessEnvelope usage (stub)          |

## Spec conventions

- Spec files are numbered to indicate reading order
- JSON field names use camelCase
- Timestamps are Unix epoch milliseconds (`long`)
- IDs use Base58 encoding derived from SHA-256
- All text content fields are Markdown-enabled
- Trust level enum values are uppercase (`TRUSTED`, `KNOWN`, `BLOCKED`)
- `schema` = wire format version (int); `version` = content edit version (int, starts at 1)
- References sections use Wikipedia links formatted as `[Title — Wikipedia](URL)`
- Scope/boundary notes use GitHub `> [!NOTE]` blockquotes
- Cross-spec links use relative paths: `[3-contact-exchange.md](3-contact-exchange.md)`
- ASCII diagrams must maintain exact character-width alignment (outer box = 31 inner chars)
- New spec files follow the pattern: numbered prose intro → field tables → references section
- QR code exchange is unidirectional by default; NFC and Bluetooth are bidirectional by default
- NFC bootstraps a Bluetooth connection (iOS cannot push NDEF); actual card exchange happens over BLE

## Key design decisions (apply across all specs)

- Two-key identity model: `sigKey` (Ed25519) for signing, `encKey` (X25519) for encryption — both derived from one seed
- `nodeId` is transmitted for convenience but always verified locally: `Base58(SHA-256(sigKey)[0..15])`
- Content IDs are derived from body hash — never assigned externally
- Visibility has three levels: public, private (single recipient), access-controlled (symmetric key for hubs)
- All content supports editing via `version` / `prevId` chain; comments are first-class content
