# Phone Remote plugin

Desktop side of the Pear Remote protocol (`protocol/SPEC.md`). Lets a paired
phone control playback over the local network.

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
| `renderer.ts`              | **The only module that touches YT Music page internals**    |
| `backend/service.ts`       | Wires everything together; used by the backend and the menu |

`server.ts`, `pairing.ts`, `devices.ts`, `identity.ts`, `player-state.ts` and
`rate-limit.ts` don't import Electron, so they run under plain Node.

## Tests

```sh
node --test 'src/plugins/phone-remote/tests/*.test.ts'
node --test 'protocol/tests/*.test.ts'
```

The integration tests run the real listener on loopback (allowed only in
tests) and validate every received message with `ServerMessageSchema`.
