# Pear Remote for Android

Native Android (Kotlin, Jetpack Compose) remote for the Pear Desktop
`phone-remote` plugin. It implements protocol v1 as specified in
[`protocol/SPEC.md`](../protocol/SPEC.md).

No Google Play services, Firebase, ML Kit or other Google services: the app
works on de-Googled phones. QR codes are decoded on-device with ZXing.

## Modules

| Module  | What                                                                                                                                   | Needs Android SDK |
| ------- | -------------------------------------------------------------------------------------------------------------------------------------- | ----------------- |
| `core/` | Protocol models and strict codec, pairing URI, SPKI pinning, fingerprint probe, session, reconnecting controller. Plain Kotlin/JVM.    | No                |
| `app/`  | UI, Keystore credential storage, mDNS discovery (NsdManager), media session and notification, CameraX + ZXing scanner, lifecycle policy. | Yes               |

## Build and test

Requirements: JDK 17 or newer. For `app/`: Android SDK with platform 35 and
build tools (set `ANDROID_HOME` or `sdk.dir` in `local.properties`).

```sh
cd mobile
./gradlew :core:test                      # contract tests + unit tests
./gradlew :core:test -Pinterop            # also run against the real desktop listener (see below)
./gradlew :app:assembleDebug :app:lintDebug
./gradlew -PcoreOnly :core:test           # without the Android SDK / Google Maven
```

`-PcoreOnly` leaves out `app/` and the Android Gradle plugin, for machines that
can't reach Google's Maven repository.

### Contract tests

`core/src/test/.../ContractTest.kt` loads every file in `protocol/fixtures/`
and checks that the Kotlin decoder accepts every `valid` entry, rejects every
`invalid` one, and that encoding a decoded value gives back the same JSON
(SPEC 9). It fails if a fixture file is added without being covered.

### Interop test

`DesktopInteropTest` starts `interop/desktop-harness.ts`, which runs the real
listener from `src/plugins/phone-remote/backend` on loopback with a fake
player, and drives it with the Kotlin client: fingerprint probe, wrong pin,
wrong code, Deny, QR pairing, hello, commands, pushed events, `not_ready`,
`stale_queue`, revoke, and listener shutdown. It needs Node 22+ and
`pnpm install` in the repository root, so it only runs with `-Pinterop`.

## Permissions

Approved by the project owner. Any new permission needs approval first.

| Permission                                                   | Why                                                                         |
| ------------------------------------------------------------ | --------------------------------------------------------------------------- |
| `INTERNET`                                                   | The wss connection to the desktop, and album art.                           |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`    | Media notification and lock-screen controls while music plays in the background. |
| `CAMERA`                                                     | In-app QR scanner. Requested only when the user taps Scan.                 |

## Behaviour notes

- **Security.** The phone trusts only the SPKI pin from the QR code (or the
  one the user confirmed on the manual path). No system trust fallback. For
  the manual path the phone first does a TLS handshake only, shows the short
  fingerprint, and sends the code on a new pinned connection only after the
  user confirms it. Tokens are encrypted with an AES-GCM key in the Android
  Keystore, in `noBackupFilesDir`, with backups disabled. Secrets are
  redacted from every `toString()` and never logged.
- **Connections.** Connected while the app is visible. In the background the
  connection stays only while the desktop is playing (media notification);
  once nothing plays it closes after 60 s. Events are pushed by the desktop;
  the position is interpolated locally; the queue is fetched only while the
  queue screen is open and refreshed on `queue.changed`. WebSocket pings
  every 30 s.
- **Failures.** Every error and close code maps to a state with its own text
  (unreachable, identity changed, access removed, update the app, update
  Pear Desktop, pairing closed/invalid/denied/timeout). Retryable failures
  reconnect with backoff (1, 2, 5, 10, 30 s) and re-resolve the desktop's
  address over mDNS; the others wait for the user.
- **Discovery.** mDNS finds the current address of a paired desktop by its
  `id`, and lists desktops on the pairing screen to fill in the address. The
  `fp` TXT record is never trusted.
