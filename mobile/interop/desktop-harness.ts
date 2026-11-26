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
 * Run from the repository root: node mobile/interop/desktop-harness.ts
 */
import { createInterface } from 'node:readline';

import { startHarness } from '../../src/plugins/phone-remote/tests/helpers.ts';

const h = await startHarness();
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
