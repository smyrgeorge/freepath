# Secure BLE Contact Exchange

This spec defines a secure contact exchange protocol for BLE. It supersedes the original
design (which used a PIN-derived encryption key) with a protocol that achieves forward
secrecy and eliminates offline PIN brute-force attacks.

Key properties:

- Ephemeral X25519 key exchange → forward secrecy
- PIN used only for MitM confirmation, not key derivation → offline brute-force impossible
- Non-empty AAD on all AEAD payloads, bound to role and session → no context-confusion
- 5 messages, 3 GATT characteristics

## GATT Service Layout

| Name        | UUID                                   | Properties  |
|-------------|----------------------------------------|-------------|
| Service     | `81e2d89b-f75f-4c72-95c4-8db84b24bf11` | —           |
| `EPHEMERAL` | `81e2d89b-f75f-4c72-95c4-8db84b24bf12` | Read, Write |
| `CARD`      | `81e2d89b-f75f-4c72-95c4-8db84b24bf13` | Read, Write |
| `STATUS`    | `81e2d89b-f75f-4c72-95c4-8db84b24bf15` | Write       |

> [!NOTE]
> UUID `81e2d89b-f75f-4c72-95c4-8db84b24bf14` is reserved for the internal PING
> connectivity-probe characteristic and is not part of the exchange protocol.

- `EPHEMERAL`: exchange raw 32-byte ephemeral X25519 public keys
- `CARD`: combined PIN confirmation (32 bytes) + encrypted card payload
- `STATUS`: exchange result written by initiator

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
```

`PIN_bytes` = UTF-8 encoding of the 4-digit decimal string (e.g. "0734" → `[48, 55, 51, 52]`).

**Why offline brute-force is not possible:** `pinKey` requires `sessionKey`, which requires
the ephemeral DH private key. A passive eavesdropper who records BLE traffic cannot derive
`sessionKey` without solving the discrete log problem on Curve25519.

**Why MitM is detected:** a MitM computes a different `sessionKey` for each side, producing
`pinConfirm` values that do not match, causing the exchange to abort.

## Encryption Format

All encrypted payloads use ChaCha20-Poly1305 with a 12-byte random nonce:

```
payload = nonce (12 bytes) || ciphertext+tag
```

AAD is never empty:

| Message    | AAD                     |
|------------|-------------------------|
| Card (I→R) | `"I"` \|\| `session_id` |
| Card (R→I) | `"R"` \|\| `session_id` |

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
    |  GATT connect ─────────────────────>   |
    |                                        |
    |  1. write i_eph_pub to EPHEMERAL  ──>  |
    |                                        |
    |  2. read r_eph_pub from EPHEMERAL  <── |
    |                                        |
    |  [both derive sessionKey, session_id]  |
    |                                        |
    |              [Bob reads PIN from       |
    |               Alice's screen]          |
    |                                        |
    |  [both derive pinKey,                  |
    |   pinConfirm_I, pinConfirm_R]          |
    |                                        |
    |  3. write to CARD  ────────────────>   |
    |  pinConfirm_I (32)                     |
    |  nonce || ChaCha20(aliceCard)          |
    |  AAD = "I" || session_id               |
    |                                        |  verify pinConfirm_I
    |                                        |  decrypt aliceCard
    |                                        |
    |  4. read from CARD  <──────────────    |
    |  pinConfirm_R (32)                     |
    |  nonce || ChaCha20(bobCard)            |
    |  AAD = "R" || session_id               |
    |                                        |
    |  verify pinConfirm_R                   |
    |  decrypt bobCard                       |
    |                                        |
    |  5. write STATUS  ─────────────────>   |
    |  SUCCESS                               |
    |                                        |
    |  GATT disconnect ─────────────────>    |
    |                                        |
    |  confirmation screen                   |  confirmation screen
```

### Step-by-step

1. **Initiator sends ephemeral key** — generates `(i_eph_priv, i_eph_pub)`, writes `i_eph_pub`
   (32 bytes) to `EPHEMERAL`

2. **Responder sets ephemeral key** — reads `i_eph_pub`; generates `(r_eph_priv, r_eph_pub)`;
   sets `r_eph_pub` as the readable value on `EPHEMERAL`; both parties compute `sharedSecret`,
   `session_id`, and `sessionKey`

3. **Initiator sends card** — Bob enters the PIN shown by Alice; both derive `pinKey`,
   `pinConfirm_I`, `pinConfirm_R`; initiator writes `pinConfirm_I || nonce || ciphertext(aliceCard)`
   to `CARD`

4. **Responder validates and sends card** — constant-time comparison of received `pinConfirm_I`
   against expected value; aborts with `FAILURE` on mismatch; decrypts Alice's card; encrypts own
   card; sets `pinConfirm_R || nonce || ciphertext(bobCard)` as the readable value on `CARD`;
   initiator reads, performs same constant-time validation on `pinConfirm_R`, decrypts Bob's card

5. **Initiator sends STATUS** — writes `SUCCESS` (or `FAILURE` if any validation failed) to
   `STATUS`; both parties show confirmation screen

## Status Values

| Value  | Description                                    |
|--------|------------------------------------------------|
| `0x00` | SUCCESS — exchange completed successfully      |
| `0x01` | FAILURE — peer failed a verification step      |
| `0x02` | ERROR — unexpected error (malformed data, etc) |

## Security Properties

| Property                  | How it's achieved                                                              |
|---------------------------|--------------------------------------------------------------------------------|
| **Confidentiality**       | ChaCha20-Poly1305 with `sessionKey` from ephemeral X25519                      |
| **Integrity**             | Poly1305 MAC; AAD binds each card to its role and session                      |
| **Forward secrecy**       | Ephemeral keypairs discarded after exchange; past sessions stay encrypted      |
| **No offline PIN attack** | `pinKey` requires `sessionKey`; `sessionKey` requires ephemeral DH private key |
| **MitM resistance**       | MitM derives a different `sessionKey` → wrong `pinConfirm` → exchange aborts   |
| **Mutual authentication** | Both parties must produce a valid `pinConfirm` to proceed                      |
| **Reflection resistance** | `pinConfirm_I` and `pinConfirm_R` derived with distinct `info` strings         |
| **Context binding**       | `session_id` in AAD ties every AEAD message to this ephemeral session          |

## PIN Requirements

- Exactly 4 digits (0000–9999)
- Randomly generated by initiator at exchange start
- Single-use (valid for one exchange attempt only)
- Expiry: 30 seconds from generation

## Error Handling

If any step fails (DH validation, PIN confirmation mismatch, AEAD decryption failure), the
exchange aborts immediately. Ephemeral keypairs MUST be discarded on abort and never reused.
The user must restart the exchange from the beginning.

## References

- [ChaCha20-Poly1305 — Wikipedia](https://en.wikipedia.org/wiki/ChaCha20-Poly1305)
- [HKDF — Wikipedia](https://en.wikipedia.org/wiki/HKDF)
- [Curve25519 — Wikipedia](https://en.wikipedia.org/wiki/Curve25519)
- [Noise Protocol Framework — noiseprotocol.org](https://noiseprotocol.org/noise.html)
- [Bluetooth Low Energy — Wikipedia](https://en.wikipedia.org/wiki/Bluetooth_Low_Energy)
