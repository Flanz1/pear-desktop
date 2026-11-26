# Phone Remote plugin

Desktop side of the Pear Remote protocol (`protocol/SPEC.md`). Lets a paired
phone control playback over the local network and, from protocol 1.1, browse
the desktop's YouTube Music (home feed with every shelf and mood chip,
library, album / playlist / artist pages, search with suggestions), start
playback and fetch covers through the desktop.

- Off by default. Enabling the plugin starts the listener; disabling it stops
  the listener, closes every connection and stops mDNS.
- Binds to one private LAN address (menu: Network interface), TLS with a
  self-signed P-256 certificate whose SPKI pin travels in the pairing QR code.
- Pairing: menu > Pair new phone opens a 120 s window with a QR code and a
  6-digit code. Nothing is paired until you click Allow.
- Paired phones are listed in the menu, each with Revoke access.
- Secrets (TLS key, device token hashes, instance ID) live in
  `<userData>/phone-remote/state.json` (mode 0600, key encrypted with
  Electron safeStorage where available), not in the app config, because the
  app config is mirrored into the YT Music page.

## Layout

| File                       | Role                                                        |
| -------------------------- | ----------------------------------------------------------- |
| `backend/server.ts`        | TLS + WebSocket listener, handshake, auth, commands, limits |
| `backend/pairing.ts`       | Pairing window state machine                                |
| `backend/devices.ts`       | Device records, hashed tokens, constant-time checks         |
| `backend/identity.ts`      | Key pair and certificate, SPKI fingerprint                  |
| `backend/player-state.ts`  | Player state, change detection, event coalescing            |
| `backend/player-bridge.ts` | App player controls + song-info + page bridge as PlayerPort |
| `backend/catalog.ts`       | 1.1 catalog: per-device refs, validation, pages, trimming   |
| `backend/thumbnails.ts`    | Cover fetcher: host allowlist, size/time caps, cache        |
| `backend/catalog-bridge.ts`| Catalog IPC to the page, cookie-less fetch, JPEG re-encode  |
| `renderer.ts`              | **Page bridge: player DOM, queue store** (page internals)   |
| `catalog-page.ts`          | **Page bridge: innertube browse/search/playback** (internals) |
| `backend/service.ts`       | Wires everything together; used by the backend and the menu |

`renderer.ts` and `catalog-page.ts` are **the only two modules that touch YT
Music page internals**. `catalog-page.ts` is the only file that knows
innertube paths, browse IDs, search params and response shapes; when YouTube
changes them, that is the file to fix. Everything it returns is plain data
(`catalog-types.ts`), and the backend validates it again as untrusted input.

`server.ts`, `pairing.ts`, `devices.ts`, `identity.ts`, `player-state.ts`,
`rate-limit.ts`, `catalog.ts` and `thumbnails.ts` don't import Electron, so
they run under plain Node.

## Catalog (protocol 1.1)

- Advertised (`catalog.browse`, `catalog.search`, `catalog.thumbnails`) only
  to sessions negotiated at 1.1. Every command is checked with
  `isCommandAvailable` before dispatch; unavailable ones get `unknown_type`.
  Catalog commands share the per-connection token bucket.
- Requests go through the page's own signed-in innertube client
  (`ytmusic-app`.networkManager.fetch): `/browse` (home `FEmusic_home`;
  library `FEmusic_liked_playlists`, `FEmusic_liked_albums`,
  `FEmusic_library_corpus_track_artists`, `FEmusic_liked_videos`, liked
  songs `VLLM`), `/search` with the per-filter params, and
  `/music/get_search_suggestions`. No cookie or token leaves the page and
  responses are never logged.
- Play / shuffle replay the item's own watch endpoint as a `yt-navigate`
  event on `ytmusic-app`, so YT Music builds its normal queue or radio. Play
  next / add to queue use `/music/get_queue` plus the queue store's
  `ADD_ITEMS` (with nothing queued, they start playback instead).
- Refs, cursors and thumbnail refs are 144-bit random, per device, in memory
  only, valid 30 min after last sent, capped at 20,000 per device (LRU), and
  dropped when the listener stops or the device is revoked.
- Covers are fetched in the main process through a separate in-memory
  session (no Google cookies), https only from the four allowlisted hosts,
  redirects only within them, 2 MB / 8 s caps, re-encoded as JPEG at most
  160 px and 24 KiB, 500 cached. `thumb` on Track / QueueItem is sent only
  in 1.1 sessions, so pushes are built per connection.

## Tests

```sh
node --test 'src/plugins/phone-remote/tests/*.test.ts'
node --test 'protocol/tests/*.test.ts'
```

The integration tests run the real listener on loopback (allowed only in
tests) and validate every received message with `ServerMessageSchema`.
