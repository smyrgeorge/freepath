# Contact Exchange

Contact exchange is the process by which one or both users share their contact cards, establishing a connection.
It is the only moment in Freepath that requires physical proximity. Everything that follows — content propagation,
private messaging, trust — flows from this in-person act.

There is no remote invitation, no username lookup, and no server to mediate the process.

## Exchange modes

Two modes are supported:

| Mode               | Cards transmitted                | Outcome                                                                                                     |
|--------------------|----------------------------------|-------------------------------------------------------------------------------------------------------------|
| **Unidirectional** | One party sends their card only. | The receiving party can add the sender as a contact. The sender receives nothing and adds no one.           |
| **Bidirectional**  | Both parties send their card.    | Both parties can add each other as contacts. This is the typical case for establishing a mutual connection. |

## What is exchanged

Only the contact card is transmitted — nothing else. No content from the local store, no message history, no
propagation lists. See [1-contact.md](1-contact.md) for the full contact card structure.

## Exchange methods

Three in-person methods are supported. Users may use whichever method their devices support.

### QR code

Unidirectional by default. A QR code is inherently one-directional — one device displays, one scans — making it
well suited for cases where only one party needs to share their identity.

**Flow:**

1. User A opens the **Share** screen. The app renders a QR code encoding their signed contact card.
2. User B opens the **Scan** screen and scans User A's code.
3. User B's app verifies the card and presents the [confirmation screen](#confirmation).
4. User A receives nothing and is not prompted.

To achieve a bidirectional exchange via QR, the flow is repeated in the opposite direction: User B displays their
code and User A scans it. The UI should offer a shortcut to continue into the reverse scan immediately after
completing the first.

For devices without a camera, the contact card can be exported as a short alphanumeric string that can be read aloud
or typed manually.

### NFC tap

Bidirectional by default. However, iOS does not allow third-party apps to push NDEF data to another device — it
only supports reading NFC tags. A true simultaneous bidirectional NFC exchange is therefore not possible on iOS.

To preserve the "tap to connect" UX on both platforms, NFC is used only to bootstrap a Bluetooth connection. The
tap exchanges a minimal session token; the actual card exchange then happens over Bluetooth.

**Flow:**

1. Both users open the **Exchange via NFC** screen.
2. They tap their phones together.
3. The NFC tap transmits a short-lived session token from one device to the other (one direction is sufficient).
4. Both devices use the session token to open a Bluetooth LE connection to each other automatically — no manual
   device selection required.
5. Over Bluetooth, both devices exchange their contact cards bidirectionally.
6. Both apps verify the received card and present the [confirmation screen](#confirmation).

From the user's perspective the experience is a single tap followed immediately by the confirmation screen. The
Bluetooth handshake happens silently in the background.

The app does not advertise for NFC or Bluetooth exchange at any other time. The exchange screen must be open on
both devices.

### Bluetooth

Bidirectional by default. Both devices advertise and exchange cards in a single connection.

**Flow:**

1. Both users open the **Exchange via Bluetooth** screen.
2. Both devices advertise a short-lived Bluetooth LE service and discover each other.
3. Either user selects the other from the list.
4. The app performs a bidirectional exchange — each device sends its own card and receives the other's.
5. Both apps verify the received card and present the [confirmation screen](#confirmation).

The device is not discoverable at any other time. The exchange screen must be explicitly opened and closed by the
user.

## Receiving a card

When a contact card arrives through any method, the app performs the following checks before storing anything:

1. **Schema check.** The card's `schema` field must be a version the app understands. An unknown or unsupported
   version is rejected with a user-visible error.
2. **Node ID verification.** The app derives the Node ID locally from the included `sigKey` and compares it against
   the transmitted `nodeId`. A mismatch means the card is malformed or tampered with — it is rejected and the user
   is shown an error.
3. **Signature verification.** The card must carry a valid signature from the `sigKey` included in it. A card that
   fails verification is rejected, never stored, and the user is shown an error.
4. **Duplicate check.** If a card from the same Node ID is already in the contact list, the incoming card is
   compared by `updatedAt`. If it is not strictly newer, it is ignored. If it is newer, it triggers a card update
   rather than a new contact creation (see [1-contact.md — Card updates](1-contact.md#card-updates)).

Only a card that passes all three checks proceeds to the confirmation step.

## Confirmation

After a valid card is received, the user is shown a confirmation screen before anything is stored:

```
Add contact?

  Name:    Alice
  Node ID: 4mXkR9qWzJvTsLpYcBnD2e

  [ Add ]   [ Ignore ]
```

If the user taps **Add**, a contact entry is created in the local store (see [2-contact-entry.md](2-contact-entry.md))
with `trustLevel` defaulting to `TRUSTED`. The user may change the trust level immediately or at any later time.

If the user taps **Ignore**, the card is discarded and nothing is stored. The other party's device is not notified.
