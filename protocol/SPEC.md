# Pear Remote protocol, version 1.1

This is the contract between the Pear Desktop `phone-remote` plugin (the
**desktop**) and the Android app (the **phone**). The zod schemas in
`protocol/src/` are the normative message definitions; this document covers
everything around them. "MUST", "SHOULD" and "MAY" are used as in RFC 2119.

Version 1.1 is an additive minor version over 1.0: it adds catalog browsing,
search and thumbnails (section 10) and an optional `thumb` field on now-playing
data. Nothing in 1.0 changes, and a 1.0 phone or desktop keeps working with a
1.1 peer because negotiation picks the lower minor.

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
| Catalog (1.1)                    | Opaque per-device refs instead of YouTube IDs, desktop-only image fetching from an allowlist, re-encoded thumbnails. See section 10.8.                                   |

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

### 4.1 Minor versions and capabilities

| Version | Adds                                                                                 |
| ------- | ------------------------------------------------------------------------------------ |
| 1.0     | Base protocol                                                                        |
| 1.1     | Catalog commands and messages, three catalog error codes, `thumb` on Track/QueueItem |

- A desktop at 1.1 MAY advertise optional features in `welcome.capabilities`
  (`CAPABILITIES` in `src/version.ts`). A capability is meaningful only when
  the negotiated minor is at least the minor that defined it; a desktop MUST
  NOT advertise a capability the negotiated version doesn't include.
- The phone sends a command only when `isCommandAvailable(type,
  welcome.protocol, welcome.capabilities)` is true (`COMMAND_REQUIREMENTS`).
- The desktop answers a well-formed command that isn't available in the
  session (negotiated minor too low, or capability not advertised) with
  `error` `unknown_type`. This does not count as an invalid message.
- A **1.0** desktop doesn't know the 1.1 command types and answers them with
  `bad_message`, which counts toward the 3-invalid-messages close. This is why
  the phone MUST gate on the negotiated version, not just try a command.

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

The one field that depends on the negotiated version is `thumb` on `Track`
and `QueueItem` (1.1). Because 1.0 parsers reject unknown keys, the desktop
MUST omit `thumb` in sessions negotiated at 1.0. In sessions at 1.1 or later
it MAY include it (a thumbnail ref, or null), and phones MUST accept both
presence and absence (absent means null). The schema marks it optional for
this reason. The desktop sends non-null `thumb` only when it advertises
`catalog.thumbnails`. `thumbnailUrl` is unchanged.

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
| `catalog.*` (1.1)                                    | See section 10.2                  | `unknown_type` if not available in the session       |

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
| `catalog.page`, `catalog.suggestions`, `catalog.thumb` (1.1)          | Only in answer to a catalog command (section 10)                                   |

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
| `unknown_type`            | 1.1: a known command not available in this session (section 4.1)         | No                                            |
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
| `not_found`               | 1.1: ref or cursor unknown, expired, or not valid for that action        | No                                            |
| `signed_out`              | 1.1: needs a signed-in YouTube Music account on the desktop              | No                                            |
| `unavailable`             | 1.1: YouTube failed, or returned something the desktop can't read        | No                                            |

Command types the desktop doesn't know at all (and any malformed message)
still get `bad_message`, as in 1.0.

The desktop sends `not_found`, `signed_out` and `unavailable` **only** in
answer to catalog commands, which only 1.1 phones send. A 1.0 phone therefore
never sees them; its strict decoder would reject an unknown code as an invalid
message.

`message` is English text for logs. The phone shows its own localized text
based on `code`.

## 8. Limits

Defined in `src/limits.ts` (`LIMITS`, `CLOSE_CODES`):

- Handshake timeout 10 s. Pairing window 120 s, 5 failed attempts.
- Commands: token bucket, burst 20, refill 10 per second. Over the limit, the
  command gets `rate_limited`. Over the limit continuously for 10 s, close 4429.
- After `welcome`, the 3rd invalid message closes the connection with 4400.
- String fields: titles 500 chars, device names 64 chars.
- Catalog (1.1): 30 sections per page, 100 items per section, 20 chips per
  page, 10 suggestions of at most 150 chars, search queries 1-150 chars, refs
  16-32 base64url chars valid for 30 min, thumbnails at most 24 KiB decoded and
  160x160 px. Details in section 10.5.

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
- 1.1: catalog commands (section 10), gated with `isCommandAvailable` before
  any other handling; the per-device ref table; the thumbnail fetcher with the
  host allowlist and re-encoding; `thumb` on Track/QueueItem only for
  connections negotiated at 1.1 (so pushes that are broadcast today become
  per-connection, also because refs are per-device).

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
- 1.1: hello with minor 1; catalog commands gated on `welcome.protocol` and
  `welcome.capabilities`; decoding of the catalog messages and of the three new
  error codes; `thumb` optional on Track/QueueItem; thumbnail fetching paced
  under the rate limit with a local cache.

## 10. Catalog (1.1)

The catalog lets the phone browse what the desktop's YouTube Music shows: the
home feed with every recommendation shelf (Listen again, Quick picks, Mixed
for you, mood chips such as Relax or Energize, and so on), the signed-in
library, album, playlist and artist pages, and search with suggestions. The
phone can start playback of anything it sees and fetch cover thumbnails
through the desktop. The desktop does all YouTube requests with its own
session; the phone never talks to Google.

### 10.1 Capability gating

| Capability           | Commands                                         |
| -------------------- | ------------------------------------------------ |
| `catalog.browse`     | `catalog.browse`, `catalog.open`, `catalog.play` |
| `catalog.search`     | `catalog.search`, `catalog.suggest`              |
| `catalog.thumbnails` | `catalog.thumb`; non-null `thumb` refs           |

- The phone sends a catalog command only when the negotiated minor is at
  least 1 **and** the matching capability is in `welcome.capabilities`
  (`isCommandAvailable`). Otherwise the desktop answers `unknown_type`.
- The desktop MUST NOT advertise `catalog.search` or `catalog.thumbnails`
  without `catalog.browse` (search results and thumbnails need `open`/`play`).
- Without `catalog.thumbnails`, every `thumb` field the desktop sends is null.
- Capabilities don't change during a connection. A desktop that loses the
  ability (for example, the user signs out) answers with an error code rather
  than withdrawing the capability.

### 10.2 Refs

Everything the phone can act on is named by an opaque **ref** (`RefSchema`):
16-32 characters of base64url, no padding. Continuation cursors
(`CursorSchema`) and thumbnail refs (`ThumbRefSchema`) have the same shape.

- The desktop issues refs. Each one maps, in a table kept for **one paired
  device**, to a YouTube endpoint (browse ID plus params, a video or playlist
  ID plus params, a continuation token, or an image URL). The phone never
  sends YouTube IDs, params or URLs in catalog commands; the schemas have no
  field that could carry them.
- A ref is valid for 30 minutes (`LIMITS.refTtlMs`) after the desktop last
  sent it to that device. The desktop SHOULD return the same ref for the same
  target to the same device while it is valid, so the phone can cache by ref,
  and sending it again restarts its 30 minutes.
- The desktop MAY cap each device's table (a few tens of thousands of entries
  is plenty) and evict the least recently sent entries. The table is in memory
  only: it is cleared when the listener stops, and a device's table is deleted
  when the device is revoked.
- An unknown, expired, evicted or other device's ref gets `not_found`. The
  phone reloads the page it came from (with `continuation: null`) and tries
  again once.

### 10.3 Commands and answers

All catalog commands carry `id` like other commands, and the answer echoes it.

| Command           | Fields                                                     | Answer                |
| ----------------- | ---------------------------------------------------------- | --------------------- |
| `catalog.browse`  | `target`, `continuation` (cursor or null)                  | `catalog.page`        |
| `catalog.open`    | `ref`, `continuation`                                      | `catalog.page`        |
| `catalog.search`  | `query`, `filter`, `continuation`                          | `catalog.page`        |
| `catalog.suggest` | `query`                                                    | `catalog.suggestions` |
| `catalog.play`    | `ref`, `action` (`play`/`shuffle`/`playNext`/`addToQueue`) | `result`              |
| `catalog.thumb`   | `thumb` (thumbnail ref)                                    | `catalog.thumb`       |

- `catalog.browse` `target`: `home` (the home feed), `library.playlists`,
  `library.albums`, `library.artists`, `library.songs`, `library.liked` (liked
  songs). Library targets, and home when YouTube needs a session for it, get
  `signed_out` when the desktop's YouTube Music isn't signed in.
- `catalog.open` takes the `ref` of an item with the `open` action or of a
  chip: an album, single, playlist, artist, or mood/genre page.
- `catalog.search` `query` is trimmed, then must be 1-150 UTF-16 code units
  with no control characters (U+0000-U+001F, U+007F). The phone sends it
  already trimmed. `filter` is `top` (YouTube's mixed results) or one of
  `songs`, `albums`, `artists`, `playlists`, `videos`.
- `catalog.suggest` uses the same query rules. The phone SHOULD debounce
  keystrokes (about 250 ms) and drop answers for queries the user has since
  changed. At most 10 suggestions, each 1-150 chars.
- `catalog.play` takes the `ref` of an item or of a page header. The action
  MUST be one listed in that item's or header's `actions` (and not `open`);
  otherwise the desktop answers `not_found`. `play` and `shuffle` replace the
  queue and start playback; `playNext` and `addToQueue` insert into the queue.
  The desktop answers `result` once YouTube Music accepted the request; the
  usual `track.changed`, `playback.changed` and `queue.changed` pushes follow.
- `continuation` is null for the first page. To load more, the phone sends
  the same command with the same other fields and the `continuation` from the
  last `catalog.page` it got for that page. A cursor from another page, or
  with different other fields, gets `not_found`.

### 10.4 `catalog.page`

```
catalog.page { id, title, subtitle|null, header|null, chips[], sections[], append, continuation|null }
  header  { kind: album|playlist|artist, title, subtitle, thumb|null, ref, actions ⊆ [play, shuffle] }
  chip    { title, ref }                                   // home: mood/genre shortcuts
  section { title|null, layout: carousel|list|grid, items[1..100] }
  item    { ref, kind, title, subtitle, durationSeconds|null, explicit, actions, thumb|null, isCurrent }
```

- **Home** carries every shelf YouTube Music shows as its own section, in
  YouTube's order, with YouTube's title: Listen again, Quick picks, Mixed for
  you, Recommended music videos, Forgotten favourites, and whatever else the
  feed contains. The desktop MUST NOT drop shelves because they look like
  recommendations; it MAY drop shelves whose items it can't represent
  (for example ads or unknown renderers). Mood/genre chips go in `chips`.
- **Layout** is a hint: `carousel` for horizontal shelves, `list` for track
  lists (album, playlist, search songs), `grid` for tile grids (library
  albums, Listen again's square tiles).
- **Item kind**: `song`, `video`, `album`, `single`, `playlist`, `artist`,
  `episode`, `station` (radio/mix). `subtitle` is YouTube's secondary text
  (may be empty). `explicit` is the "E" badge.
- **Item actions** are what the phone may offer: non-empty, no duplicates,
  from `open`, `play`, `shuffle`, `playNext`, `addToQueue`. Typically songs
  and videos have `play`, `playNext`, `addToQueue`; albums and playlists have
  `open`, `play`, `shuffle`; artists have `open`.
- **`isCurrent`** marks the item that was playing when the desktop built the
  page. It is a snapshot; the phone may update the highlight itself from
  `track.changed`, but there is no catalog push.
- **Header** is set on album, playlist and artist pages. `header.ref` is the
  page's own ref for `catalog.play` with the header's actions.
- An empty result (empty library, no search results) is a page with
  `sections: []`.

**Continuation rule.** `append` says how an answer joins the page the phone
is showing:

| `append`     | When                                                          | Phone does                                                              |
| ------------ | ------------------------------------------------------------- | ----------------------------------------------------------------------- |
| `null`       | Answer to `continuation: null`                                | Shows the page as new                                                   |
| `'sections'` | Feed-like pages: home, mood pages, `top` search, artist pages | Appends `sections` after the existing ones                              |
| `'items'`    | List pages: library, album, playlist, filtered search         | `sections` has exactly one entry; appends its items to the last section |

A continuation answer has `header: null` and `chips: []` (the schema enforces
both, and that `'items'` comes with exactly one section). Its `title` and
`subtitle` repeat the first page's. `continuation: null` means there is no
more. The phone SHOULD stop loading more after about 2,000 items on one page.

### 10.5 Limits and message size

| Limit                    | Value                              | Constant                                   |
| ------------------------ | ---------------------------------- | ------------------------------------------ |
| Sections per page        | 30                                 | `catalogSectionsMax`                       |
| Items per section        | 100                                | `catalogItemsMax`                          |
| Chips per page           | 20                                 | `catalogChipsMax`                          |
| Suggestions              | 10, each 1-150 chars               | `catalogSuggestionsMax`, `catalogQueryMax` |
| Search query             | 1-150 chars after trimming         | `catalogQueryMax`                          |
| Ref / cursor / thumb ref | 16-32 base64url chars              | `refMinLength`, `refMaxLength`             |
| Ref lifetime             | 30 min after last sent             | `refTtlMs`                                 |
| Thumbnail                | 24 KiB decoded, 160x160 px at most | `thumbMaxBytes`, `thumbMaxPixels`          |

- Client messages stay under 4 KiB: the largest catalog command (a 150-char
  query, every character escaped, a 64-char id and a 32-char cursor) is under
  1.1 KB.
- Text fields keep the 500-char limit. A page at every count limit with
  500-char text is about 4 MB, over the 1 MiB server message limit, so the
  **desktop trims to fit**: before sending, if the serialized page is over
  1 MiB, it removes items from the end of the largest sections (dropping a
  section that becomes empty) until it fits. Real pages are far smaller (a
  home feed of 10 shelves of 20 items is around 100 KB). The phone's 1 MiB
  check stays as it is.
- A `catalog.thumb` is at most about 33 KB (24 KiB as base64).

### 10.6 Thumbnails

- `catalog.thumb { thumb }` answers `catalog.thumb { id, mime, data }`:
  `mime` is `image/jpeg`, `image/webp` or `image/png`; `data` is standard
  base64 with padding. The decoded image is at most 24 KiB and at most
  160x160 px. Phones MUST still decode it defensively (a bounded image decoder
  that rejects larger dimensions) and treat a failure like a missing cover.
- The desktop fetches the image itself, over HTTPS, only from these hosts:
  `i.ytimg.com`, `lh3.googleusercontent.com`, `yt3.ggpht.com`,
  `yt3.googleusercontent.com`. It does not follow redirects to other hosts,
  limits the download size and time, then decodes, resizes to fit 160x160 and
  re-encodes the image (so the phone never receives YouTube's original bytes
  or metadata). Anything else gets `unavailable`. The phone never contacts
  Google.
- The desktop SHOULD cache re-encoded thumbnails by source URL.
- Thumbnail requests count against the command rate limit (burst 20, 10 per
  second, and 4429 after 10 s continuously over). The phone MUST pace them:
  request only covers that are on or near the screen, keep at most a few in
  flight, stay under 10 per second, and cache decoded covers by ref.
- `Track.thumb` / `QueueItem.thumb` (section 7) are thumbnail refs for the
  now-playing cover and queue rows, fetched the same way.

### 10.7 Errors

| Code           | Catalog meaning                                                                |
| -------------- | ------------------------------------------------------------------------------ |
| `unknown_type` | Command not available in this session (section 10.1)                           |
| `not_found`    | Ref or cursor unknown, expired, from another page, or action not offered       |
| `signed_out`   | Needs a signed-in YouTube Music on the desktop (library, some home content)    |
| `unavailable`  | YouTube returned an error, timed out, or sent something the desktop can't read |
| `not_ready`    | The player window isn't loaded                                                 |
| `rate_limited` | Over the command rate                                                          |
| `internal`     | Any other desktop-side failure                                                 |

None of these close the connection. The desktop sends `not_found`,
`signed_out` and `unavailable` only in answer to catalog commands.

### 10.8 Security

- Every catalog command and answer is validated against its schema, like
  every other message. The desktop also validates what it builds from YouTube
  responses: anything that doesn't fit (too long, wrong type) is dropped or
  cut, never sent unvalidated.
- Refs are unguessable: at least 96 bits from a CSPRNG (16 base64url chars
  or more), never derived from the YouTube ID. They are scoped to one paired
  device: a ref issued to one device is `not_found` for every other device,
  and they never leave the desktop's memory.
- Because the phone only sends refs, it can't make the desktop request
  arbitrary YouTube endpoints or arbitrary URLs. The only free text it sends
  is the search query, which the desktop passes to YouTube's search as data.
- Catalog commands count against the same per-connection token bucket as
  every other command.
- Thumbnails come only from the host allowlist in 10.6, are re-encoded, and
  are size-capped; the phone never receives a URL to fetch.
- The catalog doesn't change pairing, authentication or listener exposure.
  Catalog answers may reveal the desktop user's listening history and library
  to the paired phone, which is the point of the feature and limited to
  phones the user paired.
