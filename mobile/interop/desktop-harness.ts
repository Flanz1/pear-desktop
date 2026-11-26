/**
 * Runs the real desktop listener (src/plugins/phone-remote/backend) on
 * loopback with a fake player, for the Kotlin interop test
 * (core/src/test/.../DesktopInteropTest.kt). Not part of the app.
 *
 * Talks JSON lines: prints {"ready":...} on start, then reads one command
 * per line on stdin and prints one reply line per command. Pairing secrets
 * printed here are generated per run for the test and only travel over the
 * pipe to the test process.
 *
 * With `--catalog`, the listener gets the real catalog service
 * (backend/catalog.ts) over a synthetic page bridge: no YouTube page and no
 * network. Every title, ID and image below is made up for the test.
 *
 * Run from the repository root: node mobile/interop/desktop-harness.ts [--catalog]
 */
import { createInterface } from 'node:readline';

import { CatalogService } from '../../src/plugins/phone-remote/backend/catalog.ts';
import { isAllowedThumbUrl } from '../../src/plugins/phone-remote/backend/thumbnails.ts';
import { startHarness } from '../../src/plugins/phone-remote/tests/helpers.ts';

import type { CatalogRequest } from '../../src/plugins/phone-remote/catalog-types.ts';

// A 1x1 PNG, base64.
const PNG =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

const song = (n: number) => ({
  kind: 'song' as const,
  title: `Song ${n}`,
  subtitle: 'Artist',
  durationSeconds: 200,
  explicit: false,
  thumbnailUrl: `https://lh3.googleusercontent.com/synthetic-${n}=w120-h120`,
  videoId: 'dQw4w9WgXcQ',
  play: { watch: { videoId: 'dQw4w9WgXcQ', n } },
  queue: { queue: { videoId: 'dQw4w9WgXcQ', n } },
});

const catalogRequests: CatalogRequest[] = [];
const fakeCatalog = () =>
  new CatalogService({
    page: {
      request(req) {
        catalogRequests.push(req);
        if (req.op === 'browse' || req.op === 'search') {
          return Promise.resolve({
            ok: true,
            page: {
              title: req.op === 'browse' ? 'Home' : req.query,
              subtitle: null,
              header: null,
              chips: [{ title: 'Relax', open: { browse: { id: 'relax' } } }],
              mode: 'sections',
              continuation: null,
              sections: [
                { title: 'Listen again', layout: 'grid', items: [song(1), song(2)] },
                {
                  title: 'Mixed for you',
                  layout: 'carousel',
                  items: [
                    {
                      kind: 'playlist',
                      title: 'Mix',
                      subtitle: 'Playlist',
                      durationSeconds: null,
                      explicit: false,
                      thumbnailUrl: null,
                      videoId: null,
                      open: { browse: { id: 'mix' } },
                      play: { watch: { playlistId: 'mix' } },
                    },
                  ],
                },
              ],
            },
          });
        }
        if (req.op === 'suggest') {
          return Promise.resolve({ ok: true, suggestions: [`${req.query} one`] });
        }
        return Promise.resolve({ ok: true, done: true });
      },
    },
    thumbs: {
      isAllowed: isAllowedThumbUrl,
      get: () => Promise.resolve({ mime: 'image/png', data: PNG }),
    },
  });

const h = await startHarness(
  process.argv.includes('--catalog') ? { catalog: fakeCatalog() } : {},
);
const out = (o: unknown) => process.stdout.write(JSON.stringify(o) + '\n');

out({
  ready: true,
  port: h.port,
  fingerprint: h.identity.fingerprint,
  instanceId: '6f1c2c1e-6c1d-4c47-9a39-0e9b3c8a1d2f',
});

type Cmd =
  | { cmd: 'openPairing' }
  | { cmd: 'answer'; value: boolean | 'hang' }
  | { cmd: 'emit'; msg: Parameters<typeof h.player.emit>[0] }
  | { cmd: 'setState'; state: typeof h.player.state }
  | { cmd: 'executed' }
  | { cmd: 'catalogRequests' }
  | { cmd: 'devices' }
  | { cmd: 'revoke'; deviceId: string }
  | { cmd: 'stop' };

for await (const line of createInterface({ input: process.stdin })) {
  if (!line.trim()) continue;
  const c = JSON.parse(line) as Cmd;
  switch (c.cmd) {
    case 'openPairing': {
      const { secret, code } = h.pairing.open();
      out({ secret, code });
      break;
    }
    case 'answer':
      h.answer.value = c.value;
      out({ ok: true });
      break;
    case 'emit':
      h.player.emit(c.msg);
      out({ ok: true });
      break;
    case 'setState':
      h.player.state = c.state;
      out({ ok: true });
      break;
    case 'executed':
      out({ executed: h.player.executed });
      break;
    case 'catalogRequests':
      out({ ops: catalogRequests.map((r) => r.op) });
      break;
    case 'devices':
      out({ devices: h.devices.list().map((d) => ({ deviceId: d.deviceId, name: d.name, model: d.model })) });
      break;
    case 'revoke':
      h.devices.revoke(c.deviceId);
      h.server.disconnectDevice(c.deviceId);
      out({ ok: true });
      break;
    case 'stop':
      await h.stop();
      out({ ok: true });
      process.exit(0);
  }
}
await h.stop();
