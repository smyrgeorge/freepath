# Secure BLE Contact Exchange

This spec defines a secure contact exchange protocol for BLE. It supersedes the original
design (which used a PIN-derived encryption key) with a protocol that achieves forward
secrecy and eliminates offline PIN brute-force attacks.

Key properties:

- Ephemeral X25519 key exchange → forward secrecy
- PIN used only for MitM confirmation, not key derivation → offline brute-force impossible
- Non-empty AAD on all AEAD payloads, bound to role and session → no context-confusion
- 5 messages over a single L2CAP channel
- Identity secret derived during exchange → enables rotating BLE identity tokens

## Transport

The exchange runs over a BLE L2CAP channel (Connection-Oriented Channel). All 5 messages
use the `EXCHANGE` frame type defined by the `BleFrameType` wire format:

```
[4-byte BE payload length] [1-byte type = EXCHANGE] [payload]
```

The initiator opens an outbound L2CAP connection to the responder's published PSM.
The responder accepts the inbound connection from its L2CAP server. Both sides send
and receive `EXCHANGE` frames on the same channel.

See [SPEC-ble-l2cap-transport.md](SPEC-ble-l2cap-transport.md) for full transport details.

## Key Derivation

Both parties generate a fresh X25519 keypair for each exchange:

```
i_eph_pub, i_eph_priv = X25519_keygen()   # initiator
r_eph_pub, r_eph_priv = X25519_keygen()   # responder

sharedSecret = X25519(own_eph_priv, peer_eph_pub)

session_id = SHA256(i_eph_pub || r_eph_pub)   # 32-byte binding used as AAD salt

sessionKey = HKDF-SHA256(
    ikm    = sharedSecret,
    salt   = i_eph_pub || r_eph_pub,
    info   = "freepath-ble-v2-session",
    outLen = 32
)

pinKey = HKDF-SHA256(
    ikm    = sessionKey,
    salt   = PIN_bytes,
    info   = "freepath-ble-v2-pin",
    outLen = 32
)

pinConfirm_I = HKDF-SHA256(ikm=pinKey, salt=session_id, info="freepath-ble-v2-pin-I", outLen=32)
pinConfirm_R = HKDF-SHA256(ikm=pinKey, salt=session_id, info="freepath-ble-v2-pin-R", outLen=32)

identitySecret = HKDF-SHA256(
    ikm    = sharedSecret,
    salt   = i_eph_pub || r_eph_pub,
    info   = "freepath-ble-v2-identity",
    outLen = 32
)
```

`PIN_bytes` = UTF-8 encoding of the 4-digit decimal string (e.g. "0734" → `[48, 55, 51, 52]`).

**Why offline brute-force is not possible:** `pinKey` requires `sessionKey`, which requires
the ephemeral DH private key. A passive eavesdropper who records BLE traffic cannot derive
`sessionKey` without solving the discrete log problem on Curve25519.

**Why MitM is detected:** a MitM computes a different `sessionKey` for each side, producing
`pinConfirm` values that do not match, causing the exchange to abort.

**Identity secret:** derived from the ephemeral DH shared secret with a distinct `info` string,
independent of the PIN. Both sides compute the same value. It is stored persistently and used
to compute rotating BLE identity tokens (see [Rotating Identity Tokens](#rotating-identity-tokens)).

## Encryption Format

All encrypted payloads use ChaCha20-Poly1305 with a 12-byte random nonce:

```
payload = nonce (12 bytes) || ciphertext+tag
```

Contact cards are signed with Ed25519 (`ContactSignedCodec.seal`) before encryption and
verified after decryption (`ContactSignedCodec.open`). A forged card will fail signature
verification even if the AEAD decryption succeeds.

AAD is never empty:

| Message    | AAD                      |
|------------|--------------------------|
| Card (I→R) | `0x01` \|\| `session_id` |
| Card (R→I) | `0x02` \|\| `session_id` |

The role byte is `0x01` (initiator) or `0x02` (responder), not ASCII characters.

## Exchange Flow

Both devices must have the Exchange via Bluetooth screen open. The PIN is generated
by the initiator and displayed on their screen; the responder enters it manually.

```
+---------+                              +---------+
|  Alice  |                              |   Bob   |
| (init.) |                              | (resp.) |
+---------+                              +---------+
    |                                        |
    |  generate PIN + i_eph keypair          |  generate r_eph keypair
    |                                        |
    |  L2CAP connect ───────────────────>    |
    |                                        |
    |  1. EXCHANGE: i_eph_pub (32 B) ────>   |
    |                                        |
    |  2. EXCHANGE: r_eph_pub (32 B) <────   |
    |                                        |
    |  [both derive sessionKey, session_id,  |
    |   pinKey, pinConfirm_I, pinConfirm_R,  |
    |   identitySecret]                      |
    |                                        |
    |              [Bob reads PIN from       |
    |               Alice's screen]          |
    |                                        |
    |  3. EXCHANGE: ─────────────────────>   |
    |  pinConfirm_I (32)                     |
    |  nonce || ChaCha20(signedAliceCard)    |
    |  AAD = 0x01 || session_id              |
    |                                        |  verify pinConfirm_I
    |                                        |  decrypt + verify aliceCard
    |                                        |
    |  4. EXCHANGE: <─────────────────────   |
    |  pinConfirm_R (32)                     |
    |  nonce || ChaCha20(signedBobCard)      |
    |  AAD = 0x02 || session_id              |
    |                                        |
    |  verify pinConfirm_R                   |
    |  decrypt + verify bobCard              |
    |                                        |
    |  5. EXCHANGE: status byte ──────────>  |
    |  SUCCESS (0x00)                        |
    |                                        |
    |  [both store identitySecret +          |
    |   peer contact in routing table]       |
    |                                        |
    |  confirmation screen                   |  confirmation screen
```

### Step-by-step

1. **Initiator sends ephemeral key** — generates `(i_eph_priv, i_eph_pub)`, sends `i_eph_pub`
   (32 bytes) as an `EXCHANGE` frame

2. **Responder sends ephemeral key** — receives `i_eph_pub`; generates `(r_eph_priv, r_eph_pub)`;
   both parties compute `sharedSecret`, `session_id`, `sessionKey`, and `identitySecret`;
   sends `r_eph_pub` as an `EXCHANGE` frame

3. **Initiator sends card** — Bob enters the PIN shown by Alice; both derive `pinKey`,
   `pinConfirm_I`, `pinConfirm_R`; initiator sends `pinConfirm_I || nonce || ciphertext(signedAliceCard)`
   as an `EXCHANGE` frame

4. **Responder validates and sends card** — constant-time comparison of received `pinConfirm_I`
   against expected value; aborts with `FAILURE` status on mismatch; decrypts and verifies Alice's
   signed card; encrypts own signed card; sends `pinConfirm_R || nonce || ciphertext(signedBobCard)`
   as an `EXCHANGE` frame; initiator performs same constant-time validation on `pinConfirm_R`,
   decrypts and verifies Bob's card

5. **Initiator sends status** — sends `SUCCESS` (or `FAILURE`/`ERROR` if any validation failed)
   as a 1-byte `EXCHANGE` frame; both parties show confirmation screen

### Early failure detection

Both sides detect a 1-byte payload at steps 3 and 4. If the peer sends a status byte
instead of a card payload, the receiver immediately aborts with a clear error message
rather than attempting to parse it as a card.

## Status Values

| Value  | Description                                    |
|--------|------------------------------------------------|
| `0x00` | SUCCESS — exchange completed successfully      |
| `0x01` | FAILURE — peer failed a verification step      |
| `0x02` | ERROR — unexpected error (malformed data, etc) |

## Error Handling

If any step fails (DH validation, PIN confirmation mismatch, AEAD decryption failure,
signature verification failure), the failing side:

1. Sends a 1-byte status frame (`FAILURE` or `ERROR`) to the peer
2. Emits a `Failed` event to the application layer
3. Aborts the exchange

Ephemeral keypairs MUST be discarded on abort and never reused. The user must restart
the exchange from the beginning. Each step has a 30-second timeout; if no response
arrives within this window, the exchange aborts.

## Rotating Identity Tokens

After a successful exchange, both parties store `identitySecret` in the routing table.
This secret is used to compute rotating BLE advertisement tokens that allow contacts
to identify each other without connecting.

### Token computation

```
epoch = unix_millis / ROTATION_INTERVAL_MS    # 15-minute intervals

token = HKDF-SHA256(
    ikm    = identitySecret,
    salt   = epoch_bytes (8 bytes LE),
    info   = "freepath-ble-v2-token",
    outLen = 8
)
```

### Advertisement format

| Platform | Format                                                         |
|----------|----------------------------------------------------------------|
| Android  | Service data: `[PSM 2 bytes LE] [token 8 bytes]`               |
| iOS      | Local name: `"fp:PPPP:TTTTTTTTTTTTTTTT"` (PSM hex + token hex) |

### Token matching

The scanner extracts the 8-byte token from the advertisement and computes the expected
token for each known contact's `identitySecret`. Both the current and previous epoch
are checked to handle the rotation boundary window. Matching uses constant-time
comparison to prevent timing attacks.

### Properties

| Property                 | How it's achieved                                                        |
|--------------------------|--------------------------------------------------------------------------|
| **No passive tracking**  | Token rotates every 15 minutes; observers cannot correlate across epochs |
| **No active probing**    | Only holders of the shared secret can compute the expected token         |
| **No connection needed** | Matching happens at scan time from advertisement data                    |

### Multi-contact rotation

Each device shares a different `identitySecret` with each contact. Since only one token
can be advertised at a time, the device rotates through contacts' secrets across epochs:
`secret = secrets[epoch % secrets.size]`. With N contacts and 15-minute rotation, each
contact can identify the device once every N × 15 minutes.

## Security Properties

| Property                  | How it's achieved                                                              |
|---------------------------|--------------------------------------------------------------------------------|
| **Confidentiality**       | ChaCha20-Poly1305 with `sessionKey` from ephemeral X25519                      |
| **Integrity**             | Poly1305 MAC; AAD binds each card to its role and session                      |
| **Authenticity**          | Contact cards are Ed25519-signed before encryption; verified after decryption  |
| **Forward secrecy**       | Ephemeral keypairs discarded after exchange; past sessions stay encrypted      |
| **No offline PIN attack** | `pinKey` requires `sessionKey`; `sessionKey` requires ephemeral DH private key |
| **MitM resistance**       | MitM derives a different `sessionKey` → wrong `pinConfirm` → exchange aborts   |
| **Mutual authentication** | Both parties must produce a valid `pinConfirm` to proceed                      |
| **Reflection resistance** | `pinConfirm_I` and `pinConfirm_R` derived with distinct `info` strings         |
| **Context binding**       | `session_id` in AAD ties every AEAD message to this ephemeral session          |
| **Identity privacy**      | Rotating tokens prevent passive tracking; only contacts can match              |

## PIN Requirements

- Exactly 4 digits (0000–9999)
- Randomly generated by initiator at exchange start
- Single-use (valid for one exchange attempt only)

## References

- [ChaCha20-Poly1305 — Wikipedia](https://en.wikipedia.org/wiki/ChaCha20-Poly1305)
- [HKDF — Wikipedia](https://en.wikipedia.org/wiki/HKDF)
- [Curve25519 — Wikipedia](https://en.wikipedia.org/wiki/Curve25519)
- [Noise Protocol Framework — noiseprotocol.org](https://noiseprotocol.org/noise.html)
- [Bluetooth Low Energy — Wikipedia](https://en.wikipedia.org/wiki/Bluetooth_Low_Energy)
- [L2CAP — Wikipedia](https://en.wikipedia.org/wiki/Logical_link_control_and_adaptation_protocol)
