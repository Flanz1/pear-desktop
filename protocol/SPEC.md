# Pear Remote protocol, version 1.0

This is the contract between the Pear Desktop `phone-remote` plugin (the
**desktop**) and the Android app (the **phone**). The zod schemas in
`protocol/src/` are the normative message definitions; this document covers
everything around them. "MUST", "SHOULD" and "MAY" are used as in RFC 2119.

## 1. Security model

The protocol protects one thing: only phones the user explicitly approved on
the desktop can control playback, and only from the local network.

| Requirement                      | How                                                                                                                                                                      |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Off by default                   | The listener does not run until the user enables it. Disabling it closes all connections and stops mDNS advertising.                                                     |
| LAN only                         | Binds to one LAN interface address (never `0.0.0.0` / `::`). Refuses peers outside RFC 1918, 169.254/16, fc00::/7, fe80::/10 (`isLanAddress`). No UPnP, no port mapping. |
| Explicit pairing                 | Pairing is only possible during a user-opened pairing window, and only completes after the user clicks Allow on the desktop.                                             |
| Encrypted, authenticated desktop | TLS with a self-signed certificate. The phone pins its SPKI SHA-256 fingerprint from the QR code.                                                                        |
| Per-device, revocable access     | Each paired phone has its own random token. The desktop stores only its hash. Revoking deletes it and closes the connection.                                             |
| Untrusted input                  | The desktop validates every message against the schema, enforces size and rate limits, and closes connections that misbehave.                                            |

Secrets in this protocol: device tokens, pairing secrets, pairing codes, and
the TLS private key. Implementations MUST NOT log, print, persist in plain
text (except as below), or include them in crash reports, test fixtures
derived from real devices, or analytics.

## 2. Transport

- WebSocket over TLS (`wss://<host>:<port>/pear-remote/ws`), default port
  26539 (`LIMITS.defaultPort`). Any other path gets HTTP 404.
- Text frames only, one JSON object per frame, UTF-8. Binary frames are a
  protocol error.
- Size limits, enforced before JSON parsing: phone to desktop 4 KiB, desktop
  to phone 1 MiB. The desktop MUST configure its WebSocket server's
  `maxPayload` to 4 KiB so oversize frames are rejected with close code 1009.
- Keepalive uses WebSocket ping/pong frames. The phone SHOULD ping every 30 s
  while connected; the desktop closes connections silent for 90 s.
- One connection per device: a new authenticated connection for a device ID
  closes the older one with close code 4409.

### 2.1 TLS identity

- On first enable, the desktop generates an ECDSA P-256 key pair and a
  self-signed certificate (CN `Pear Remote`, 10-year validity). The private key
  is stored with OS-level protection where available (Electron `safeStorage`).
- The pin is SHA-256 over the DER-encoded SubjectPublicKeyInfo, base64url
  without padding (43 chars). Pinning the key rather than the certificate lets
  the desktop reissue an expired certificate without re-pairing.
- The phone MUST verify the pin and MUST NOT fall back to system trust or
  accept a mismatched pin. Hostname verification is skipped (the pin replaces it).
- A user-initiated "Reset remote identity" on the desktop generates a new key,
  which invalidates every pairing. A phone that sees a pin mismatch for a known
  desktop shows "This computer's identity changed. Pair again." and does not
  connect.

## 3. Discovery

- While enabled, the desktop advertises `_pear-remote._tcp.local` over mDNS
  with TXT records:
  - `v` - supported protocol majors, comma-separated (e.g. `1`)
  - `id` - desktop instance ID (UUID, generated on first enable)
  - `fp` - SPKI fingerprint
- The phone uses discovery only to find the current address of a desktop it
  has already paired with (matched by `id`), and still verifies the pinned
  fingerprint on connect. It MUST NOT trust `fp` from mDNS for an unpaired
  desktop: first contact always goes through the QR code or the manual flow.
- Manual entry of `host:port` is supported as a fallback when multicast is
  blocked.

## 4. Version negotiation

- Versions are `{ major, minor }`. Additive changes (new optional message
  types, new commands, new capabilities) bump the minor. Anything else is
  breaking and bumps the major.
- The first message's `type` and `protocol` fields are a **version-stable
  envelope** (`HandshakeEnvelopeSchema`): they keep this shape in every major.
  The desktop reads them first, then validates the rest with the schema for
  that major.
- The desktop's supported table (`DESKTOP_SUPPORTED`) maps each accepted major
  to its highest implemented minor. After a major bump, the desktop MUST keep
  the previous major in this table for at least one release.
- `negotiate()` defines the outcome:
  - Client major supported: effective version is that major with
    `min(client minor, desktop minor)`. It is returned in `welcome.protocol`.
    Neither side sends anything newer than the effective version, which is why
    both sides can validate strictly.
  - Client major too old: `error` with code `update_required` and
    `supported: {min, max}`, then close 4426. The phone shows "Update the app".
  - Client major too new: `error` with `desktop_update_required`, then close 4426. The phone shows "Update Pear Desktop".
- Version negotiation happens before authentication, so a phone with an
  incompatible version gets a clear message. It reveals only the supported
  version range.

## 5. Connection lifecycle

```
phone                                   desktop
  |--- TLS + WebSocket upgrade ----------->|  peer address checked (LAN only)
  |--- hello | pair.request -------------->|  within 10 s, else close 4408
  |<-- welcome + state  (hello OK) --------|
  |<-- pair.result      (pairing OK) ------|  then close 1000; reconnect with hello
  |<-- error + close    (anything else) ---|
  |--- commands ------------------------->|
  |<-- result | error | state | queue -----|  responses carry the request id
  |<-- *.changed events ------------------|  pushed on change
```

Before `welcome`, any message that fails validation closes the connection with
4400 (or 1009 if oversize), after sending `error` where possible. No commands
are accepted before `welcome`.

### 5.1 Authentication (`hello`)

1. The desktop looks up `deviceId`. If found, it computes SHA-256 of the
   presented token and compares it with the stored hash in constant time
   (`crypto.timingSafeEqual`).
2. Unknown device and wrong token get the same response: `error` code
   `unauthorized`, close 4401. Neither case is distinguishable from the other.
3. Failed attempts are rate limited per peer address: after 5 failures in 60 s,
   further connections from that address are refused for 60 s. Failures are
   `unauthorized` hellos and `pairing_invalid` pair requests, counted together.
4. On success: update `lastSeenAt`, send `welcome`, then an unsolicited
   `state` (with `id: null`).

The token travels only inside the TLS connection in the `hello` body. It never
appears in a URL, query string, header, or log line.

## 6. Pairing

### 6.1 Pairing window

- The user clicks **Pair new phone** on the desktop. This opens a window of
  120 s showing:
  - a QR code with the pairing URI (below), and
  - a 6-digit code plus the short fingerprint (`shortFingerprint`, e.g.
    `9F86-D081-884C-7D65`) for manual pairing.
- The pairing secret (16 bytes) and code (6 digits) are generated with a CSPRNG
  for each window and are single-use.
- The window closes on: one successful pairing, the user clicking Cancel,
  timeout, the listener being disabled, or 5 failed attempts
  (`pairingMaxFailedAttempts`). A failed attempt is a `pair.request` with a
  wrong secret or code.
- A `pair.request` with the **correct** secret or code consumes the window:
  from that moment the window is closed to everyone else, and it ends with
  that request's outcome (Allow, Deny, timeout, or the phone disconnecting
  while the dialog is open, which dismisses the dialog). It is never reopened.
  Pairing another phone, or retrying after Deny, takes a new **Pair new
  phone** click with a fresh secret and code.
- Outside an open window, including while the confirmation dialog for another
  request is showing, every `pair.request` gets `pairing_closed` and close 4401. `pairing_closed` does not count as a failed attempt.
- Each `pairing_invalid` also counts as an authentication failure for the
  per-address limit in section 5.1 (5 failures in 60 s from one address, then
  60 s refusal), in addition to the window's own limit of 5.

### 6.2 Pairing URI (QR code)

```
pear-remote://pair?v=1&h=192.168.1.20&p=26539&fp=<spki>&s=<secret>&i=<instanceId>&n=<name>
```

All seven keys are required, each exactly once, with no extra keys
(`parsePairingUri`). `h` is an IP literal that passes `isLanAddress` and has no
IPv6 zone ID. The phone MUST reject a URI that fails these checks. See
`fixtures/pairing-uri.json`.

### 6.3 Flow

1. **QR path.** The phone scans the code, connects to `h:p`, verifies the pin
   `fp`, and sends `pair.request` with `secret`.
   **Manual path.** The user enters the host (or picks a discovered desktop)
   and the 6-digit code. The phone connects, shows the server's short
   fingerprint, and asks the user to confirm it matches the desktop screen
   **before** sending `pair.request` with `code`. If the user says it doesn't
   match, the phone disconnects without sending the code.
2. The desktop checks the secret or code in constant time. If it is wrong:
   `error` `pairing_invalid`, close 4401, and the failure counts toward the
   limit.
3. If it is right, the desktop shows a modal: _"Allow **{device.name}**
   ({device.model}, {peer IP}) to control playback?"_ with **Deny** as the
   default button. Nothing is paired without the user's click.
4. Deny: `error` `pairing_denied`, close 4401; the window is closed (see
   6.1). Window timeout while the dialog is open: dismiss it, `error`
   `pairing_timeout`, close 4401.
5. Allow: the desktop generates a device ID (UUID v4) and a 32-byte token,
   stores the device record, closes the window, and sends `pair.result`
   (`deviceId`, `token`, `instanceId`), then closes with 1000.
6. The phone stores `{instanceId, deviceId, token, fingerprint, last host,
   port, name}` (token in Android Keystore-backed encrypted storage) and
   reconnects with `hello`.

### 6.4 Device records and revocation

Desktop device record: `deviceId`, `name`, `model`, `tokenHash` (SHA-256 hex),
`pairedAt`, `lastSeenAt`. Never the token itself.

The desktop UI lists paired devices with name, model, pairing date, last seen,
and a **Revoke** button. Revoking deletes the record, sends `server.closing`
with reason `revoked` to a live connection, and closes it with 4403. The phone
then deletes its stored credentials for that desktop and shows "Access was
removed on the computer."

## 7. Messages

All schemas are in `src/client.ts` and `src/server.ts`. Generated JSON Schema
(2020-12) is in `schema/` for tooling; regenerate with
`node protocol/scripts/generate-schema.ts`. Where the two disagree, the zod
source wins (JSON Schema can't express the LAN-address refinement, for
example).

Both sides validate strictly: unknown fields and unknown types are rejected.
This is safe because of the negotiation rule in section 4.

### 7.1 Phone to desktop (after `welcome`)

Every command has an `id` (1-64 chars of `[A-Za-z0-9_-]`) chosen by the phone.
The desktop answers each command with exactly one of: `result {id}`, a typed
response carrying the same `id` (`state` for `state.get`, `queue` for
`queue.get`), or `error {id, ...}`.

| Type                                                 | Fields                            | Desktop behaviour                                    |
| ---------------------------------------------------- | --------------------------------- | ---------------------------------------------------- |
| `player.play` / `player.pause` / `player.togglePlay` |                                   |                                                      |
| `player.next` / `player.previous`                    |                                   |                                                      |
| `player.seek`                                        | `positionSeconds` (0 to 86400)    | Clamp to track duration                              |
| `player.setVolume`                                   | `volume` (integer 0-100)          |                                                      |
| `player.setMuted`                                    | `muted`                           | No-op if already in that state                       |
| `player.toggleShuffle`                               |                                   |                                                      |
| `player.setRepeat`                                   | `mode` NONE/ALL/ONE               | Cycle until reached                                  |
| `track.setLike`                                      | `status` LIKE/DISLIKE/INDIFFERENT | No-op if already in that state                       |
| `state.get`                                          |                                   | Replies with `state`                                 |
| `queue.get`                                          |                                   | Replies with `queue`                                 |
| `queue.play`                                         | `index`, `videoId`                | `stale_queue` if the item at `index` isn't `videoId` |

If the player isn't loaded yet, commands get `not_ready`.

### 7.2 Desktop to phone

| Type                                                                  | When                                                                               |
| --------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `welcome`                                                             | After a successful `hello`                                                         |
| `pair.result`                                                         | After the user allows pairing                                                      |
| `result`                                                              | Command succeeded                                                                  |
| `error`                                                               | See codes below. `id` is the command's ID, or null                                 |
| `state`                                                               | Full snapshot: right after `welcome` (`id: null`) and in reply to `state.get`      |
| `track.changed`                                                       | Track changes (or stops: `track: null`)                                            |
| `playback.changed`                                                    | Play/pause/buffering/stop, and seeks                                               |
| `volume.changed`, `repeat.changed`, `shuffle.changed`, `like.changed` | That value changed                                                                 |
| `queue.changed`                                                       | Queue changed. A hint only: the phone sends `queue.get` if it is showing the queue |
| `queue`                                                               | Reply to `queue.get`, at most 500 items, `truncated` if there were more            |
| `server.closing`                                                      | Before the desktop closes the connection on shutdown, disable, or revoke           |

**Position.** The desktop does not send per-second ticks. `playback.changed`
is sent on status changes and when the position jumps (a seek, or a drift of
more than 2 s from the expected position). While `status` is `playing`, the
phone advances the displayed position from the time it received the message.

**Change detection.** Desktop events are sent only when the value actually
changed. The desktop SHOULD coalesce bursts (for example, volume slider drags)
to at most 10 events per second per type.

### 7.3 Error codes

| Code                      | Meaning                                                                  | Closes?                                       |
| ------------------------- | ------------------------------------------------------------------------ | --------------------------------------------- |
| `bad_message`             | Failed validation                                                        | Before welcome: yes (4400). After: on the 3rd |
| `unknown_type`            | Reserved. v1 desktops report unknown types as `bad_message`              |                                               |
| `unauthorized`            | Unknown device or wrong token                                            | 4401                                          |
| `revoked`                 | Reserved for future use. v1 sends `server.closing` with reason `revoked` | 4403                                          |
| `update_required`         | Phone's major too old                                                    | 4426                                          |
| `desktop_update_required` | Desktop's major too old                                                  | 4426                                          |
| `pairing_closed`          | No pairing window open                                                   | 4401                                          |
| `pairing_invalid`         | Wrong secret or code                                                     | 4401                                          |
| `pairing_denied`          | User clicked Deny                                                        | 4401                                          |
| `pairing_timeout`         | Window expired before the user answered                                  | 4401                                          |
| `rate_limited`            | Over the command rate                                                    | After sustained abuse, 4429                   |
| `not_ready`               | Player not loaded                                                        | No                                            |
| `stale_queue`             | `queue.play` index/videoId mismatch                                      | No                                            |
| `internal`                | Desktop-side failure                                                     | No                                            |

`message` is English text for logs. The phone shows its own localized text
based on `code`.

## 8. Limits

Defined in `src/limits.ts` (`LIMITS`, `CLOSE_CODES`):

- Handshake timeout 10 s. Pairing window 120 s, 5 failed attempts.
- Commands: token bucket, burst 20, refill 10 per second. Over the limit, the
  command gets `rate_limited`. Over the limit continuously for 10 s, close 4429.
- After `welcome`, the 3rd invalid message closes the connection with 4400.
- String fields: titles 500 chars, device names 64 chars.

## 9. What each side implements

### Desktop (`src/plugins/phone-remote/`)

- Listener lifecycle (off by default, interface selection, LAN peer check,
  TLS identity generation and storage, mDNS advertising).
- Handshake: `parseHandshakeEnvelope` then `negotiate` then
  `parseClientHandshake`; `parseClientCommand` for everything after.
- Pairing window UI (QR, code, short fingerprint, Cancel), confirmation
  dialog, device store with hashed tokens, paired-device list with Revoke.
- Command handling via the app's existing player IPC, events from the
  song-info provider, rate limits, close codes.
- Run `node --test 'protocol/tests/*.test.ts'`. Desktop integration tests
  should also validate every outgoing message with `ServerMessageSchema`.

### Phone (`mobile/`)

- Kotlin models matching these schemas, with strict decoding (reject unknown
  keys and unknown types) and the same field constraints.
- QR parsing equivalent to `parsePairingUri`, pin verification, the manual
  flow with fingerprint confirmation, Keystore-backed credential storage.
- Handling of every error code and close code with a clear UI state
  (unreachable, update required, access removed, identity changed).
- Position interpolation, `queue.changed` handling, ping every 30 s.
- A contract test that loads every file in `protocol/fixtures/` and checks the
  Kotlin decoder accepts every `valid` entry and rejects every `invalid` one,
  and that encoding the decoded value gives back the same JSON.
