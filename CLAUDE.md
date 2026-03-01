# Freepath

Protocol specification and reference implementation. Specs live in `spec/`; Kotlin Multiplatform implementation in
`freepath-transport-crypto`, `freepath-transport`, `freepath-transport-lan`, and `freepath-transport-lan-demo`.

## Repository structure

| Path                           | Purpose                                                                                                   |
|--------------------------------|-----------------------------------------------------------------------------------------------------------|
| `spec/`                        | Protocol and data model specifications                                                                    |
| `freepath-transport-crypto/`   | Crypto primitives: `CryptoProvider` expect/actual (JVM+Android via BouncyCastle, iOS via Swift/CryptoKit) |
| `freepath-transport/`          | Protocol core: handshake, session, frame codec, crypto                                                    |
| `freepath-transport-lan/`      | LAN adapter library: TCP + mDNS peer discovery (JVM + Android)                                            |
| `freepath-transport-lan-demo/` | JVM demo app: multi-node heartbeat demo + Docker setup                                                    |
| `build-logic/`                 | Gradle convention plugins (`freepath.dokka`, `freepath.swift.interop`)                                    |
| `docs/`                        | Published HTML documentation                                                                              |
| `tools/`                       | Pandoc templates and Lua filters for PDF/HTML generation                                                  |
| `README.md`                    | Project vision and concept overview                                                                       |

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

## Implementation modules

### `freepath-transport-crypto` — Crypto primitives

Kotlin Multiplatform library (JVM, Android, iOS). Provides the `CryptoProvider` expect/actual interface:

- **Primitives** — `randomBytes`, X25519 key agreement, HKDF-SHA256, ChaCha20-Poly1305 AEAD, Ed25519 sign/verify
- **JVM + Android actual** — `jvmAndroidMain` shared source set; BouncyCastle satisfies both targets from one
  implementation
- **iOS actual** — cinterop to a Swift `@objc CryptoBridge` class backed by Apple CryptoKit (iOS 14+)

**iOS build pipeline:**

- `src/swift/` — Swift Package Manager package (`Package.swift` + `Sources/CryptoBridge/CryptoBridge.swift`)
- `src/nativeInterop/cinterop/CryptoBridge.def` — static template with ObjC interface; the build injects
  `staticLibraries`/`libraryPaths`/`linkerOpts` at build time
- `build-logic` plugin `io.github.smyrgeorge.freepath.swift.interop` drives the Swift build and def-file generation;
  linker opts are embedded in the klib and propagate automatically to transitive consumers

**Key dependencies:** `bouncycastle` (JVM/Android), Apple `CryptoKit` (iOS)

### `freepath-transport` — Protocol core

Kotlin Multiplatform library (JVM, Android, iOS). Implements:

- **`Frame` / `FrameCodec`** — JSON-serialized wire frames (schema, streamId, seq, wireType, payload)
- **`AeadCodec`** — ChaCha20-Poly1305 encryption with AAD derived from frame metadata
- **`HandshakeHandler`** — Two-frame handshake; derives session key via X25519 + HKDF-SHA256; verifies peer identity
  against contact list (no TOFU)
- **`StatefulProtocol`** — Session state machine: seq tracking, rollover guard (teardown before `0xFFFFFFF0`), ACK/CLOSE
  handling, 300 ms disconnect grace period
- **`StatelessEnvelopeCodec`** — Seal/open envelopes for unidirectional transports; X25519 HKDF-SHA256 key derivation;
  Ed25519 signing over ciphertext
- **`WireEnvelopeCodec`** — TCP wire framing: magic `"FREE"` + version + type + 4-byte length; max 16 MiB payload
- **`LinkAdapterCodec`** — `LinkAdapterPacket` header (seq, fragIndex, fragCount) for fragmentation/reassembly
- **`Base58`** / **`BinaryCodec`** — Encoding utilities
- **`CryptoProvider`** — `expect`/`actual` crypto interface; actuals live in `freepath-transport-crypto`

**Key interfaces:** `Protocol`, `LinkAdapter`, `PeerDiscovery`

**Key dependencies:** `project(":freepath-transport-crypto")`, `kotlinx-coroutines-core`, `kotlinx-serialization-json`,
`bignum`, `log4k`

### `freepath-transport-lan` — LAN adapter

Kotlin Multiplatform library targeting JVM and Android (minSdk 26). Implements:

- **`LanLinkAdapter`** — TCP connections; duplicate-connection resolution (lexicographically smaller nodeId wins);
  concurrent outbound connect guard; `LINK_MTU` = 16 KiB
- **`LanServer`** — TCP server; OS-assigned port; max 8 inbound connections
- **`LanConnection`** — Per-socket Ktor read/write channels; fragmentation at MTU; reassembly keyed by seq with 30 s
  timeout and max 64 concurrent slots
- **`MdnsPeerDiscovery` (JVM)** — JmDNS; service type `_freepath._tcp.`; TXT record `v=1` + nodeId
- **`MdnsPeerDiscovery` (Android)** — `NsdManager`; API 34+ uses `registerServiceInfoCallback`; API 26–33 uses legacy
  `resolveService` with serial resolve channel

**Key dependencies:** `project(":freepath-transport")`, `ktor-network`, `jmdns` (JVM), `log4k`

### `freepath-transport-lan-demo` — LAN demo app

JVM-only module (not a library). Implements:

- **`DemoApp`** — 20-node deterministic contact pool (SHA-256-seeded SHA1PRNG → Ed25519 + X25519); periodic heartbeat
  sends; SIGTERM shutdown hook
- **Docker** — `src/docker/` with `Dockerfile`, `docker-compose.yml`, `run.sh`; run `src/docker/run.sh` from the
  project root to build the fatJar and launch nodes via `docker compose`

**Key dependencies:** `project(":freepath-transport-lan")`, `kotlinx-coroutines-core`, `log4k-slf4j`

### Build system

- All modules use `alias(libs.plugins.kotlin.multiplatform)` directly — no custom KMP convention plugins
- JVM target: 21; `-Xjsr305=strict`; progressive Kotlin mode enabled; parallel builds + config-cache enabled
- `freepath-transport-lan` targets JVM, Android, and iOS

**`build-logic/` convention plugins** — precompiled script plugins (no `gradlePlugin { }` registration needed; plugin
ID = file name):

| Plugin ID                                     | File                                                     | Purpose                                                                                                                 |
|-----------------------------------------------|----------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `io.github.smyrgeorge.freepath.dokka`         | `io.github.smyrgeorge.freepath.dokka.gradle.kts`         | Applies Dokka + configures GitHub source links                                                                          |
| `io.github.smyrgeorge.freepath.swift.interop` | `io.github.smyrgeorge.freepath.swift.interop.gradle.kts` | Builds a Swift Package, generates the cinterop `.def` with embedded linker opts, wires up cinterops for all iOS targets |

**`swift.interop` plugin DSL** — configure in any module that has iOS targets and a Swift package:

```kotlin
swiftInterop {
    packageName = "CryptoBridge"       // SPM target name and cinterop name — required
    frameworks = listOf("CryptoKit")  // Apple system frameworks to link
    // swiftSourceDir = "src/swift"    // default: directory containing Package.swift
    // templateDefFile = "src/nativeInterop/cinterop/<packageName>.def"  // default
}
```

Helper classes (`BuildSwiftPackageTask`, `GenerateDefFileTask`, `SwiftInteropExtension`) live in
`build-logic/src/main/kotlin/io/github/smyrgeorge/freepath/swift/`.

## Key design decisions (apply across all specs and implementation)

- Two-key identity model: `sigKey` (Ed25519) for signing, `encKey` (X25519) for encryption — both derived from one seed
- `nodeId` is transmitted for convenience but always verified locally: `Base58(SHA-256(sigKey)[0..15])`
- HandshakeHandler looks up sigKey from contact list — never trusts the received key directly; unknown peers are
  rejected (no TOFU)
- Content IDs are derived from body hash — never assigned externally
- Visibility has three levels: public, private (single recipient), access-controlled (symmetric key for hubs)
- All content supports editing via `version` / `prevId` chain; comments are first-class content
