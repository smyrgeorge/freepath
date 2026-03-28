# Freepath

Protocol specification and reference implementation. Specs live in `spec/`; Kotlin Multiplatform implementation in
`freepath-util`, `freepath-crypto`, `freepath-contact`, `freepath-transport`, `freepath-transport-lan`,
`freepath-database`, `freepath-libp2p`, `freepath-wasm`, and `freepath-app`.

## Commands

```bash
# Build everything
./gradlew build

# Run tests for a specific module
./gradlew :freepath-transport:jvmTest
./gradlew :freepath-contact:jvmTest

# Regenerate KSP-generated database repositories (after changing @Table/@Column annotations)
./gradlew :freepath-database:kspCommonMainKotlinMetadata

# Run the LAN demo (builds fatJar + launches Docker nodes)
./examples/transport-lan/src/docker/run.sh

# Build Compose desktop app (JVM)
./gradlew :freepath-app:composeApp:run

# Android APK
./gradlew :freepath-app:androidApp:assembleDebug

# Build freepath-libp2p native lib for JVM host (requires cargo)
./gradlew :freepath-libp2p:buildRustJvm

# Build freepath-libp2p JNI libs for Android (requires cargo-ndk + Android NDK)
./gradlew :freepath-libp2p:buildRustAndroid

# Run freepath-wasm tests (requires: rustup target add wasm32-unknown-unknown)
./gradlew :freepath-wasm:jvmTest
```

## Repository structure

| Path                      | Purpose                                                                                                   |
|---------------------------|-----------------------------------------------------------------------------------------------------------|
| `spec/`                   | Protocol and data model specifications                                                                    |
| `freepath-util/`          | Shared utilities: `Base58` encoder/decoder                                                                |
| `freepath-crypto/`        | Crypto primitives: `CryptoProvider` expect/actual (JVM+Android via BouncyCastle, iOS via Swift/CryptoKit) |
| `freepath-contact/`       | Contact identity model: `Contact`, `ContactEntry`, `ContactCodec` (specs 1 & 2)                   |
| `freepath-transport/`     | Protocol core: handshake, session, frame codec, crypto                                                    |
| `freepath-transport-lan/` | LAN adapter library: TCP + mDNS peer discovery (JVM + Android + iOS)                                      |
| `freepath-database/`      | SQLite persistence: sqlx4k + KSP-generated repos, migrations, `ContactEntry`, `IdentityEntry`         |
| `freepath-libp2p/`        | Rust libp2p swarm wrapper (KMP): `Libp2pModule` expect/actual, `Libp2pEvent`, mDNS via Swift `MdnsBridge`; Rust crate via cinterop (iOS) / JNI (Android) / native dylib (JVM) |
| `freepath-wasm/`          | WASM runtime (KMP): `WasmModule` interface + `loadWasmModule(ByteArray)`; Chicory on JVM/Android, wasm3 via cinterop on iOS |
| `freepath-app/`           | Compose Multiplatform mobile app (composeApp, androidApp, iosApp)                                         |
| `examples/transport-lan/` | JVM demo app: multi-node heartbeat demo + Docker setup                                                    |
| `build-logic/`            | Gradle convention plugins (`freepath.dokka`, `freepath.swift.interop`, `freepath.rust.interop`)           |
| `docs/`                   | Published HTML documentation                                                                              |
| `tools/`                  | Pandoc templates and Lua filters for PDF/HTML generation                                                  |
| `README.md`               | Project vision and concept overview                                                                       |

## Spec files

| File                            | Covers                                                                               |
|---------------------------------|--------------------------------------------------------------------------------------|
| `spec/1-contact.md`             | Contact card structure, keys, Node ID derivation, card updates                       |
| `spec/2-contact-entry.md`       | Local-only contact metadata: trust level, name override, tags, mute, pin             |
| `spec/3-contact-exchange.md`    | QR (unidirectional), NFC + Bluetooth + LAN/PIN (bidirectional), card validation flow |
| `spec/4-content.md`             | Content types, envelope, editing chain, comments, reactions, expiry, visibility      |
| `spec/5-transport.md`           | Transport layers: Frame, Handshake, StatelessEnvelope, LAN/BLE/optical transports    |
| `spec/6-transport-lan.md`       | LAN transport detail: mDNS peer discovery, wire envelope, connection management      |
| `spec/7-transport-bluetooth.md` | BLE transport: fragmentation, iOS background restrictions (stub — to be expanded)    |
| `spec/8-transport-optical.md`   | Optical transport: QR / screen-to-camera, StatelessEnvelope usage (stub)             |

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
- QR code exchange is unidirectional by default; NFC, Bluetooth, and LAN are bidirectional by default
- NFC bootstraps a Bluetooth connection (iOS cannot push NDEF); actual card exchange happens over BLE
- LAN exchange uses a 6-digit PIN (generated by initiator, entered by receiver) as a mutual confirmation step; PIN is
  single-use, 60-second expiry

## Implementation modules

### `freepath-util` — Shared utilities

Kotlin Multiplatform library (JVM, Android, iOS). Provides:

- **`Base58`** — Bitcoin-alphabet Base58 encoder/decoder; used for Node ID encoding and stream ID generation

**Key dependencies:** `bignum`

### `freepath-crypto` — Crypto primitives

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

### `freepath-contact` — Contact identity model

Kotlin Multiplatform library (JVM, Android, iOS). Implements specs 1 and 2:

- **`Contact`** — `@Serializable data class`; wire-format public identity contact:ontactcontactaontact`ontactKontact ontacteontactey`
  (Base64), `updatedAt`; optional `name`, `bio`, `avatar`, `location`; `nodeId` is a `@Transient lazy val`
  computed locally as `Base58([0x12, 0x20] ∥ SHA-256(sigKey))` — never transmitted
- **`ContactEntry`** — local-only record (not serialized); combines a `Contact` with trust level, timestamps,
  personal notes, pin/mute flags, and user-defined tags
- **`TrustLevel`** — `enum { TRUSTED, KNOWN, BLOCKED }` per spec 2
- **`SignedContact`** — transmission wrapper: card + Base64 Ed25519 signature over JSON-encoded card bytes
- **`ContactCodec`** — Node ID derivation (`Base58([0x12,0x20] ∥ SHA-256(sigKey))` — libp2p multihash format), sign/verify, seal/open,
  card-update rules (`shouldUpdate`), JSON encode/decode

**Key dependencies:** `project(":freepath-crypto")`, `project(":freepath-util")`, `kotlinx-serialization-json`

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
- **`BinaryCodec`** — Binary encoding utility
- **`CryptoProvider`** — `expect`/`actual` crypto interface; actuals live in `freepath-crypto`

**Key interfaces:** `Protocol` (transport-layer interface — distinct from `freepath-app`'s `Protocol` sealed class of
actor messages), `LinkAdapter`, `PeerDiscovery`

**Key dependencies:** `project(":freepath-crypto")`, `project(":freepath-util")`, `kotlinx-coroutines-core`,
`kotlinx-serialization-json`, `log4k`

### `freepath-transport-lan` — LAN adapter

Kotlin Multiplatform library targeting JVM, Android (minSdk 26), and iOS. Implements:

- **`LanLinkAdapter`** — TCP connections; duplicate-connection resolution (lexicographically smaller nodeId wins);
  concurrent outbound connect guard; `LINK_MTU` = 64 KiB; `onInboundConnectionEstablished` callback fires for inbound
  connections (distinct from `onConnectionEstablished` which is outbound-only and initiates the handshake)
- **`LanServer`** — TCP server; OS-assigned port; max 128 inbound connections
- **`LanConnection`** — Per-socket Ktor read/write channels; fragmentation at MTU; reassembly keyed by seq with 30 s
  timeout and max 64 concurrent slots
- **`MdnsPeerDiscovery` (JVM)** — JmDNS; service type `_freepath._tcp.`; TXT record `v=1` + nodeId
- **`MdnsPeerDiscovery` (Android)** — `NsdManager`; API 34+ uses `registerServiceInfoCallback`; API 26–33 uses legacy
  `resolveService` with serial resolve channel

**Key dependencies:** `project(":freepath-transport")`, `ktor-network`, `jmdns` (JVM), `log4k`

### `freepath-database` — SQLite persistence

Kotlin Multiplatform library (JVM and Android). Implements:

- **`ContactEntry`** — persisted contact record: combines `Contact` with trust level, timestamps, name
  override, tags, pin/mute flags
- **`IdentityEntry`** — persisted local identity (single row; nodeId + `Identity` key material)
- **`ContactEntryRepository` / `IdentityEntryRepository`** — interfaces; implementations generated by KSP at
  build time (`generated/` package); regenerate with `./gradlew :freepath-database:kspCommonMainKotlinMetadata`
- **`migrations`** — `V1_CreateTableContact`, `V2_CreateTableIdentity`; run via `ISQLite.migrate()`

**Key dependencies:** `sqlx4k-sqlite` (Rust-backed SQLite via `sqlx4k`), `ksp`

### `freepath-libp2p` — Rust libp2p wrapper

Kotlin Multiplatform library (JVM, Android, iOS). Wraps a Rust libp2p swarm via cinterop/JNI. Provides:

- **`Libp2pModule`** — `expect`/`actual`; `start(nodeId, sigKeyPrivate, listenAddrs)` / `stop()` / `dial(multiaddr)` /
  `sendRequest` / `sendResponse` / `sendResponseFailed`; default listen addrs: TCP + QUIC-v1 on IPv4 + IPv6,
  OS-assigned ports
- **`Libp2pEvent`** — sealed event hierarchy: `PeerConnected`, `PeerDisconnected`, `NewListenAddr`, `PeerIdentified`,
  `MdnsPeerDiscovered`, `MdnsPeerExpired`, `RequestReceived`, `ResponseReceived`, `RequestFailed`
- **`RpcManager`** — coroutine-based request/response correlation over raw libp2p request events
- **`LanPeerAddressCodec`** — converts `"host:port"` LAN addresses to libp2p multiaddr strings; skips link-local
  IPv6 (`fe80::`) addresses — they require a scope ID and cannot be dialled reliably across devices

**Build prerequisites:** Rust toolchain (`cargo`); for Android: `cargo-ndk` + Android NDK installed via SDK Manager.

**Key dependencies:** Rust libp2p (Rust crate), Swift `MdnsBridge` (iOS mDNS), `jmdns` (JVM mDNS)

### `freepath-wasm` — WASM runtime

Kotlin Multiplatform library (JVM, Android, iOS). Provides a thin, uniform interface for executing WASM modules:

- **`WasmModule`** — `fun call(function: String, input: String): String`; WASM modules must export `wasm_alloc(i32)->i32`,
  `wasm_dealloc(i32,i32)`, `wasm_result_ptr()->i32`, and each callable as `(ptr: i32, len: i32) -> i32`
- **`loadWasmModule(ByteArray): WasmModule`** — `expect`/`actual`; Chicory runtime on JVM/Android, wasm3 via cinterop
  (`Wasm3Bridge`) on iOS

**Test prerequisites:** `wasm32-unknown-unknown` Rust target (`rustup target add wasm32-unknown-unknown`);
`buildTestFixtures` runs automatically before `jvmTest`.

**Key dependencies:** `chicory-runtime` (JVM/Android), wasm3 via SPM (iOS)

### `freepath-app` — Compose Multiplatform mobile app

Multi-target app (Android, iOS, JVM desktop) using Compose Multiplatform. Key files in `composeApp/src/commonMain`:

- **`AppResources`** — singleton holding `db`, `lanAdapter`, `lanProtocol`, actor `system` ref; lifecycle entry points
  (`startupActorSystem`, `openDatabase`, `startupLan`)
- **`AppState`** — read-only `StateFlow` holder (`discoveredPeers`, `contacts`); internal helpers called only by
  `AppActor`; never mutated directly from UI
- **`AppUiState`** — pure UI state (`showAddContactDrawer`, `pendingContact`, `pendingDeepLink`); mutated directly
  from UI/platform code (no actor round-trip needed)
- **`AppActor`** — `BehaviorActor` (actor4k) that serialises all business-state mutations; handles `AcceptContact`,
  `SetTrustLevel`, `PeerDiscovered/Connected/Lost/Disconnected`, `AppForegrounded`
- **`Protocol`** — sealed interface of all actor messages; always route business mutations through `system.tell()`

**Non-obvious patterns:**

- All business-state mutations MUST go through `AppActor` via `system.tell(Protocol.Xxx)` — prevents race conditions
- UI-only state (drawer open/closed, pending cards) lives in `AppUiState` and can be mutated directly
- `tell()` is `suspend fun` in actor4k — wrap non-suspend callbacks with `scope.launch { system.tell(...) }`
- `ctx.log` (actor's built-in logger) has no lambda overloads — use `ctx.log.info("string")` not `{ "string" }`

**Key dependencies:** `actor4k`, `project(":freepath-database")`, `project(":freepath-transport-lan")`,
`compose-multiplatform`

### `examples/transport-lan` — LAN demo app

JVM-only module (not a library). Implements:

- **`DemoApp`** — 20-node deterministic contact pool (SHA-256-seeded SHA1PRNG → Ed25519 + X25519); periodic heartbeat
  sends; SIGTERM shutdown hook
- **Docker** — `src/docker/` with `Dockerfile`, `docker-compose.yml`, `run.sh`; run from the project root:
  `./examples/transport-lan/src/docker/run.sh`

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
| `io.github.smyrgeorge.freepath.rust.interop`  | `io.github.smyrgeorge.freepath.rust.interop.gradle.kts`  | Builds a Rust crate for iOS (cinterop static lib), JVM (native dylib via `buildRustJvm`/`copyNativeLibJvm`), and Android (JNI via `buildRustAndroid` using `cargo-ndk`) |

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

**`rust.interop` plugin DSL** — configure in any module with a Rust crate and iOS/JVM/Android targets:

```kotlin
rustInterop {
    crateName = "freepath_libp2p"              // required; cinterop name and JNI lib name
    linkerOpts = "-framework SystemConfiguration -framework Security"  // Apple frameworks
    // cargoDir = "src/rust"                   // default
    // headerDir = "src/nativeInterop/cinterop"  // default
}
```

Helper classes (`RustBuildTask`, `RustGenerateDefFileTask`, `RustInteropExtension`) live in
`build-logic/src/main/kotlin/io/github/smyrgeorge/freepath/rust/`.

## Key design decisions (apply across all specs and implementation)

- Two-key identity model: `sigKey` (Ed25519) for signing, `encKey` (X25519) for encryption — both derived from one seed
- `nodeId` is a `@Transient lazy val` on `Contact`, never transmitted; derived as `Base58([0x12, 0x20] ∥ SHA-256(sigKey))` (libp2p multihash format — always starts with `Qm`, 46 chars)
- HandshakeHandler looks up sigKey from contact list — never trusts the received key directly; unknown peers are
  rejected (no TOFU)
- Content IDs are derived from body hash — never assigned externally
- Visibility has three levels: public, private (single recipient), access-controlled (symmetric key for hubs)
- All content supports editing via `version` / `prevId` chain; comments are first-class content
