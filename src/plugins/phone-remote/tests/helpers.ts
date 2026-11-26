import { X509Certificate, createHash } from 'node:crypto';
import { once } from 'node:events';

import WebSocket from 'ws';

import {
  LIMITS,
  ServerMessageSchema,
  WS_PATH,
  type PlayerState,
  type ServerMessage,
} from '../../../../protocol/src/index.ts';
import { DeviceStore } from '../backend/devices.ts';
import { loadOrCreateIdentity, type TlsIdentity } from '../backend/identity.ts';
import { PairingController } from '../backend/pairing.ts';
import {
  RemoteServer,
  type ControlCommand,
  type PlayerPort,
  type QueueItem,
} from '../backend/server.ts';

export const plainProtector = {
  available: () => false,
  encrypt: (s: string) => Buffer.from(s),
  decrypt: (b: Buffer) => b.toString(),
};

let cachedIdentity: TlsIdentity | null = null;
export const testIdentity = async () => {
  cachedIdentity ??= (await loadOrCreateIdentity(null, plainProtector))
    .identity;
  return cachedIdentity;
};

export const sampleState = (): PlayerState => ({
  track: {
    videoId: 'dQw4w9WgXcQ',
    title: 'Song',
    artist: 'Artist',
    album: null,
    durationSeconds: 200,
    thumbnailUrl: 'https://lh3.googleusercontent.com/x.jpg',
  },
  playback: { status: 'playing', positionSeconds: 12 },
  volume: { volume: 50, muted: false },
  repeat: 'NONE',
  shuffle: false,
  like: 'INDIFFERENT',
});

export class FakePlayer implements PlayerPort {
  state: PlayerState | null = sampleState();
  queue: QueueItem[] = [
    {
      index: 0,
      videoId: 'dQw4w9WgXcQ',
      title: 'Song',
      artist: 'Artist',
      durationSeconds: 200,
      thumbnailUrl: null,
      isCurrent: true,
    },
  ];
  executed: ControlCommand[] = [];
  private listeners = new Set<(m: ServerMessage) => void>();

  getState() {
    return this.state;
  }
  async getQueue() {
    return this.state ? { items: this.queue, truncated: false } : null;
  }
  async execute(cmd: ControlCommand) {
    if (!this.state) return 'not_ready' as const;
    if (cmd.type === 'queue.play') {
      const item = this.queue.find((i) => i.index === cmd.index);
      if (!item || item.videoId !== cmd.videoId) return 'stale_queue' as const;
    }
    this.executed.push(cmd);
    return 'ok' as const;
  }
  subscribe(l: (m: ServerMessage) => void) {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }
  emit(m: ServerMessage) {
    for (const l of this.listeners) l(m);
  }
}

export interface Harness {
  server: RemoteServer;
  devices: DeviceStore;
  pairing: PairingController;
  player: FakePlayer;
  identity: TlsIdentity;
  port: number;
  /** Set what the confirm dialog answers. */
  answer: { value: boolean | 'hang' };
  stop: () => Promise<void>;
}

export const startHarness = async (): Promise<Harness> => {
  const identity = await testIdentity();
  const devices = new DeviceStore([], () => {});
  const answer: Harness['answer'] = { value: true };
  const pairing = new PairingController((_req, signal) =>
    answer.value === 'hang'
      ? new Promise<boolean>((resolve) =>
          signal.addEventListener('abort', () => resolve(false)),
        )
      : Promise.resolve(answer.value),
  );
  const player = new FakePlayer();
  const server = new RemoteServer({
    host: '127.0.0.1',
    port: 0,
    key: identity.keyPem,
    cert: identity.certPem,
    instanceId: '6f1c2c1e-6c1d-4c47-9a39-0e9b3c8a1d2f',
    name: 'Test Desktop',
    appVersion: '3.12.1',
    devices,
    pairing,
    player,
    allowLoopbackForTests: true,
  });
  await server.start();
  return {
    server,
    devices,
    pairing,
    player,
    identity,
    port: server.address!.port,
    answer,
    stop: async () => {
      pairing.close('disabled');
      await server.stop();
    },
  };
};

/** A client that pins the SPKI fingerprint like the phone does. */
export class TestClient {
  readonly ws: WebSocket;
  readonly received: ServerMessage[] = [];
  readonly invalid: unknown[] = [];
  closeCode: number | null = null;
  peerFingerprint: string | null = null;
  private waiters: (() => void)[] = [];

  constructor(port: number, path = WS_PATH) {
    this.ws = new WebSocket(`wss://127.0.0.1:${port}${path}`, {
      rejectUnauthorized: false, // pinning below replaces CA trust
      maxPayload: LIMITS.serverMessageBytes,
    });
    this.ws.on('upgrade', (res) => {
      const socket = res.socket as import('node:tls').TLSSocket;
      const raw = socket.getPeerCertificate(false).raw;
      const spki = new X509Certificate(raw).publicKey.export({
        type: 'spki',
        format: 'der',
      });
      this.peerFingerprint = createHash('sha256')
        .update(spki)
        .digest('base64url');
    });
    this.ws.on('message', (data) => {
      const parsed = ServerMessageSchema.safeParse(JSON.parse(String(data)));
      if (parsed.success) this.received.push(parsed.data);
      else this.invalid.push(JSON.parse(String(data)));
      this.wake();
    });
    this.ws.on('close', (code) => {
      this.closeCode = code;
      this.wake();
    });
    this.ws.on('error', () => this.wake());
  }

  async open() {
    if (this.ws.readyState !== this.ws.OPEN) await once(this.ws, 'open');
  }

  send(obj: unknown) {
    this.ws.send(typeof obj === 'string' ? obj : JSON.stringify(obj));
  }

  private wake() {
    for (const w of this.waiters.splice(0)) w();
  }

  async waitFor<T extends ServerMessage['type']>(
    type: T,
    pred: (m: Extract<ServerMessage, { type: T }>) => boolean = () => true,
    timeoutMs = 3000,
  ): Promise<Extract<ServerMessage, { type: T }>> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const found = this.received.find(
        (m) =>
          m.type === type && pred(m as Extract<ServerMessage, { type: T }>),
      );
      if (found) return found as Extract<ServerMessage, { type: T }>;
      if (Date.now() > deadline) throw new Error(`timeout waiting for ${type}`);
      await new Promise<void>((r) => {
        this.waiters.push(r);
        setTimeout(r, 50);
      });
    }
  }

  async waitClose(timeoutMs = 3000): Promise<number> {
    const deadline = Date.now() + timeoutMs;
    while (this.closeCode === null) {
      if (Date.now() > deadline) throw new Error('timeout waiting for close');
      await new Promise((r) => setTimeout(r, 20));
    }
    return this.closeCode;
  }
}

export const client = { name: 'pear-remote-android', version: '1.0.0' };
export const v1 = { major: 1, minor: 0 };

export const pairWithSecret = async (h: Harness) => {
  const { secret } = h.pairing.open();
  const c = new TestClient(h.port);
  await c.open();
  c.send({
    type: 'pair.request',
    protocol: v1,
    client,
    device: { name: 'Pixel', model: 'Pixel 8' },
    secret,
  });
  const result = await c.waitFor('pair.result');
  await c.waitClose();
  return { c, result };
};

export const connectHello = async (
  h: Harness,
  deviceId: string,
  token: string,
) => {
  const c = new TestClient(h.port);
  await c.open();
  c.send({ type: 'hello', protocol: v1, client, deviceId, token });
  return c;
};
