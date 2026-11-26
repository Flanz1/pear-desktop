import { createServer, type Server } from 'node:https';

import { WebSocketServer, type RawData, type WebSocket } from 'ws';

import { isCatalogCommand, type CatalogHandler } from './catalog.ts';
import { FailureLimiter, TokenBucket } from './rate-limit.ts';

import {
  CAPABILITIES,
  CLOSE_CODES,
  LIMITS,
  isCommandAvailable,
  WS_PATH,
  isLanAddress,
  negotiate,
  parseClientCommand,
  parseClientHandshake,
  parseHandshakeEnvelope,
  RequestIdSchema,
  ServerMessageSchema,
  type ClientCommand,
  type ErrorCode,
  type Hello,
  type PairRequest,
  type PlayerState,
  type ProtocolVersion,
  type QueueItemSchema,
  type ServerMessage,
} from '../../../../protocol/src/index.ts';

import type { DeviceStore } from './devices.ts';
import type { PairingController } from './pairing.ts';
import type { z } from 'zod';

export type QueueItem = z.infer<typeof QueueItemSchema>;

/** Commands the player executes: everything but state/queue reads and the catalog. */
export type ControlCommand = Exclude<
  ClientCommand,
  { type: 'state.get' | 'queue.get' | `catalog.${string}` }
>;

/** What the server needs from the player. Implemented by player-bridge.ts. */
export interface PlayerPort {
  getState(): PlayerState | null;
  getQueue(): Promise<{ items: QueueItem[]; truncated: boolean } | null>;
  execute(cmd: ControlCommand): Promise<'ok' | 'not_ready' | 'stale_queue'>;
  subscribe(listener: (msg: ServerMessage) => void): () => void;
}

export interface Logger {
  info(...args: unknown[]): void;
  warn(...args: unknown[]): void;
}

export interface RemoteServerOptions {
  host: string;
  port: number;
  key: string;
  cert: string;
  instanceId: string;
  name: string;
  appVersion: string;
  devices: DeviceStore;
  pairing: PairingController;
  player: PlayerPort;
  /** 1.1 catalog. Without it, no catalog capability is advertised. */
  catalog?: CatalogHandler;
  /** Called after any device record changes (pairing, last seen, revoke). */
  onDevicesChanged?: () => void;
  logger?: Logger;
  /** For tests: allow loopback peers. Never set in the app. */
  allowLoopbackForTests?: boolean;
  idleTimeoutMs?: number;
}

const SILENT_LIMIT_MS = 90_000;

const appVersion = (v: string) =>
  /^[0-9A-Za-z.+-]{1,32}$/.test(v) ? v : '0.0.0';

const isLoopback = (a: string) =>
  a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';

interface Conn {
  ws: WebSocket;
  address: string;
  /** Set after `welcome`. */
  deviceId: string | null;
  protocol: ProtocolVersion | null;
  /** Advertised in `welcome`; fixed for the connection. */
  capabilities: string[];
  invalidCount: number;
  bucket: TokenBucket;
  lastActivity: number;
  handshakeTimer: ReturnType<typeof setTimeout> | null;
  closed: AbortController;
  handshakeSeen: boolean;
}

/**
 * The Pear Remote listener (SPEC 2, 4, 5, 6, 7, 8). TLS + WebSocket, bound to
 * one LAN address, LAN peers only. Every inbound message is validated, every
 * outbound message is checked against ServerMessageSchema before sending.
 *
 * Never logs tokens, pairing secrets or codes: log lines carry only
 * message types, close codes and peer addresses.
 */
export class RemoteServer {
  private readonly opts: RemoteServerOptions;
  private https: Server | null = null;
  private wss: WebSocketServer | null = null;
  private readonly conns = new Set<Conn>();
  private readonly failures = new FailureLimiter();
  private unsubscribe: (() => void) | null = null;
  private sweeper: ReturnType<typeof setInterval> | null = null;
  private readonly log: Logger;

  constructor(opts: RemoteServerOptions) {
    if (!opts.allowLoopbackForTests && !isLanAddress(opts.host)) {
      throw new Error('phone-remote: refusing to bind to a non-LAN address');
    }
    this.opts = opts;
    this.log = opts.logger ?? { info() {}, warn() {} };
  }

  get address() {
    const a = this.https?.address();
    return a && typeof a === 'object' ? a : null;
  }

  async start(): Promise<void> {
    const https = createServer({
      key: this.opts.key,
      cert: this.opts.cert,
      minVersion: 'TLSv1.2',
    });
    const wss = new WebSocketServer({
      noServer: true,
      maxPayload: LIMITS.clientMessageBytes,
      perMessageDeflate: false,
    });
    this.https = https;
    this.wss = wss;

    https.on('request', (_req, res) => {
      res.writeHead(404).end();
    });
    https.on('upgrade', (req, socket, head) => {
      const address = req.socket.remoteAddress ?? '';
      const allowed =
        isLanAddress(address) ||
        (this.opts.allowLoopbackForTests === true && isLoopback(address));
      const path = (req.url ?? '').split('?')[0];
      if (!allowed) {
        socket.destroy();
        return;
      }
      if (path !== WS_PATH) {
        socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
        return;
      }
      if (this.failures.isBlocked(address)) {
        socket.end(
          'HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\n\r\n',
        );
        return;
      }
      wss.handleUpgrade(req, socket, head, (ws) => this.accept(ws, address));
    });
    https.on('tlsClientError', () => {
      /* bad handshakes from scanners: ignore */
    });

    this.unsubscribe = this.opts.player.subscribe((msg) => this.broadcast(msg));
    this.sweeper = setInterval(
      () => this.sweep(),
      Math.min(15_000, this.opts.idleTimeoutMs ?? SILENT_LIMIT_MS),
    );
    this.sweeper.unref?.();

    await new Promise<void>((resolve, reject) => {
      https.once('error', reject);
      https.listen(this.opts.port, this.opts.host, () => {
        https.off('error', reject);
        resolve();
      });
    });
    this.log.info(
      'phone-remote: listening on',
      this.opts.host,
      this.address?.port,
    );
  }

  /** Closes every connection (with `server.closing`) and the listener. */
  async stop(reason: 'shutdown' | 'disabled' = 'disabled'): Promise<void> {
    this.unsubscribe?.();
    this.unsubscribe = null;
    if (this.sweeper) clearInterval(this.sweeper);
    this.sweeper = null;
    this.opts.catalog?.clear();
    for (const c of [...this.conns]) {
      if (c.deviceId) this.send(c, { type: 'server.closing', reason });
      this.close(c, CLOSE_CODES.goingAway);
    }
    this.wss?.close();
    const https = this.https;
    this.https = null;
    this.wss = null;
    if (https) {
      https.closeAllConnections?.();
      await new Promise<void>((r) => https.close(() => r()));
    }
  }

  /** SPEC 6.4: notify and close a revoked device's live connection. */
  disconnectDevice(deviceId: string) {
    this.opts.catalog?.forgetDevice(deviceId);
    for (const c of this.conns) {
      if (c.deviceId === deviceId) {
        this.send(c, { type: 'server.closing', reason: 'revoked' });
        this.close(c, CLOSE_CODES.revoked);
      }
    }
  }

  connectedDeviceIds(): Set<string> {
    const ids = new Set<string>();
    for (const c of this.conns) if (c.deviceId) ids.add(c.deviceId);
    return ids;
  }

  // ---- connection handling ----

  private accept(ws: WebSocket, address: string) {
    const conn: Conn = {
      ws,
      address,
      deviceId: null,
      protocol: null,
      capabilities: [],
      invalidCount: 0,
      bucket: new TokenBucket(),
      lastActivity: Date.now(),
      handshakeTimer: null,
      closed: new AbortController(),
      handshakeSeen: false,
    };
    this.conns.add(conn);
    conn.handshakeTimer = setTimeout(() => {
      if (!conn.handshakeSeen) this.close(conn, CLOSE_CODES.handshakeTimeout);
    }, LIMITS.handshakeTimeoutMs);

    ws.on('message', (data, isBinary) => {
      conn.lastActivity = Date.now();
      this.onMessage(conn, data, isBinary).catch(() => {
        this.sendError(conn, null, 'internal', 'Internal error');
      });
    });
    ws.on('ping', () => (conn.lastActivity = Date.now()));
    ws.on('pong', () => (conn.lastActivity = Date.now()));
    ws.on('error', () => {
      /* ws closes with 1009 on oversize frames by itself */
    });
    ws.on('close', () => {
      if (conn.handshakeTimer) clearTimeout(conn.handshakeTimer);
      conn.closed.abort();
      this.conns.delete(conn);
    });
  }

  private async onMessage(conn: Conn, data: RawData, isBinary: boolean) {
    if (conn.deviceId === null) {
      if (conn.handshakeSeen) {
        // Only one handshake message per connection; pairing in progress.
        this.rejectBeforeWelcome(conn);
        return;
      }
      conn.handshakeSeen = true;
      if (conn.handshakeTimer) clearTimeout(conn.handshakeTimer);
      conn.handshakeTimer = null;
      if (isBinary) {
        this.rejectBeforeWelcome(conn);
        return;
      }
      await this.onHandshake(conn, rawToString(data));
      return;
    }
    await this.onCommand(conn, isBinary ? null : rawToString(data));
  }

  private rejectBeforeWelcome(conn: Conn) {
    this.sendError(conn, null, 'bad_message', 'Invalid handshake');
    this.close(conn, CLOSE_CODES.badMessage);
  }

  private async onHandshake(conn: Conn, raw: string | null) {
    if (raw === null) return this.rejectBeforeWelcome(conn);
    const envelope = parseHandshakeEnvelope(raw);
    if (!envelope.ok) {
      if (envelope.reason === 'too_large') {
        this.close(conn, CLOSE_CODES.messageTooBig);
        return;
      }
      return this.rejectBeforeWelcome(conn);
    }
    const negotiated = negotiate(envelope.value.protocol);
    if (!negotiated.ok) {
      this.send(conn, {
        type: 'error',
        id: null,
        code: negotiated.code,
        message:
          negotiated.code === 'update_required'
            ? 'Phone app protocol is too old'
            : 'Desktop protocol is too old',
        supported: negotiated.supported,
      });
      this.close(conn, CLOSE_CODES.versionUnsupported);
      return;
    }
    const parsed = parseClientHandshake(raw);
    if (!parsed.ok) return this.rejectBeforeWelcome(conn);
    conn.protocol = negotiated.version;

    if (parsed.value.type === 'hello') {
      this.onHello(conn, parsed.value);
    } else {
      await this.onPairRequest(conn, parsed.value);
    }
  }

  private onHello(conn: Conn, hello: Hello) {
    const record = this.opts.devices.verify(hello.deviceId, hello.token);
    if (!record) {
      this.failures.recordFailure(conn.address);
      this.log.warn('phone-remote: rejected hello from', conn.address);
      this.sendError(conn, null, 'unauthorized', 'Unauthorized');
      this.close(conn, CLOSE_CODES.unauthorized);
      return;
    }
    // One connection per device.
    for (const other of this.conns) {
      if (other !== conn && other.deviceId === record.deviceId) {
        this.close(other, CLOSE_CODES.replaced);
      }
    }
    conn.deviceId = record.deviceId;
    conn.capabilities = this.capabilitiesFor(conn.protocol!);
    this.opts.devices.touch(record.deviceId);
    this.opts.onDevicesChanged?.();
    this.send(conn, {
      type: 'welcome',
      protocol: conn.protocol!,
      server: {
        name: 'pear-desktop',
        version: appVersion(this.opts.appVersion),
      },
      instanceId: this.opts.instanceId,
      name: this.opts.name.slice(0, LIMITS.textMax),
      capabilities: conn.capabilities,
    });
    this.sendState(conn, null);
  }

  private async onPairRequest(conn: Conn, req: PairRequest) {
    const pairing = this.opts.pairing;
    const credential =
      'secret' in req ? { secret: req.secret } : { code: req.code };
    const result = pairing.attempt(credential);
    if (result === 'closed') {
      this.sendError(conn, null, 'pairing_closed', 'No pairing window open');
      this.close(conn, CLOSE_CODES.unauthorized);
      return;
    }
    if (result === 'invalid') {
      this.failures.recordFailure(conn.address);
      this.sendError(conn, null, 'pairing_invalid', 'Wrong pairing code');
      this.close(conn, CLOSE_CODES.unauthorized);
      return;
    }

    const outcome = await pairing.confirm(
      { name: req.device.name, model: req.device.model, address: conn.address },
      conn.closed.signal,
    );
    if (conn.ws.readyState !== conn.ws.OPEN) return;
    switch (outcome) {
      case 'allowed': {
        const { record, token } = this.opts.devices.create(
          req.device.name,
          req.device.model,
        );
        this.opts.onDevicesChanged?.();
        this.log.info('phone-remote: paired a new device from', conn.address);
        this.send(conn, {
          type: 'pair.result',
          deviceId: record.deviceId,
          token,
          instanceId: this.opts.instanceId,
        });
        this.close(conn, CLOSE_CODES.normal);
        return;
      }
      case 'denied':
        this.sendError(conn, null, 'pairing_denied', 'Pairing was denied');
        break;
      case 'timeout':
        this.sendError(conn, null, 'pairing_timeout', 'Pairing window expired');
        break;
      case 'closed':
        this.sendError(conn, null, 'pairing_closed', 'Pairing window closed');
        break;
    }
    this.close(conn, CLOSE_CODES.unauthorized);
  }

  private async onCommand(conn: Conn, raw: string | null) {
    const parsed = raw === null ? null : parseClientCommand(raw);
    if (!parsed || !parsed.ok) {
      if (parsed && parsed.reason === 'too_large') {
        this.close(conn, CLOSE_CODES.messageTooBig);
        return;
      }
      conn.invalidCount += 1;
      this.sendError(conn, extractId(raw), 'bad_message', 'Invalid message');
      if (conn.invalidCount >= LIMITS.maxInvalidMessages) {
        this.close(conn, CLOSE_CODES.badMessage);
      }
      return;
    }
    const cmd = parsed.value;

    const rate = conn.bucket.take();
    if (rate === 'abusive') {
      this.close(conn, CLOSE_CODES.rateLimited);
      return;
    }
    if (rate === 'limited') {
      this.sendError(conn, cmd.id, 'rate_limited', 'Too many commands');
      return;
    }

    // SPEC 4.1 / 10.1: a known command not available in this session.
    // Not an invalid message.
    if (!isCommandAvailable(cmd.type, conn.protocol!, conn.capabilities)) {
      this.sendError(conn, cmd.id, 'unknown_type', 'Not available');
      return;
    }

    const player = this.opts.player;
    if (isCatalogCommand(cmd)) {
      const catalog = this.opts.catalog;
      if (!catalog) {
        this.sendError(conn, cmd.id, 'unknown_type', 'Not available');
        return;
      }
      // Covers don't need the page; everything else does.
      if (cmd.type !== 'catalog.thumb' && !player.getState()) {
        this.sendError(conn, cmd.id, 'not_ready', 'Player not loaded');
        return;
      }
      const answer = await catalog
        .handle(conn.deviceId!, cmd, {
          currentVideoId: player.getState()?.track?.videoId ?? null,
        })
        .catch(() => ({
          error: 'internal' as const,
          message: 'Internal error',
        }));
      if ('error' in answer) {
        this.sendError(conn, cmd.id, answer.error, answer.message);
      } else if (!this.send(conn, answer)) {
        this.sendError(conn, cmd.id, 'internal', 'Answer could not be sent');
      }
      return;
    }
    if (cmd.type === 'state.get') {
      if (!player.getState()) {
        this.sendError(conn, cmd.id, 'not_ready', 'Player not loaded');
        return;
      }
      this.sendState(conn, cmd.id);
      return;
    }
    if (cmd.type === 'queue.get') {
      const queue = await player.getQueue();
      if (!queue) {
        this.sendError(conn, cmd.id, 'not_ready', 'Player not loaded');
        return;
      }
      this.send(
        conn,
        this.decorate(conn, { type: 'queue', id: cmd.id, ...queue }),
      );
      return;
    }
    if (!player.getState()) {
      this.sendError(conn, cmd.id, 'not_ready', 'Player not loaded');
      return;
    }
    const outcome = await player.execute(cmd);
    if (outcome === 'ok') this.send(conn, { type: 'result', id: cmd.id });
    else if (outcome === 'stale_queue')
      this.sendError(conn, cmd.id, 'stale_queue', 'Queue changed');
    else this.sendError(conn, cmd.id, 'not_ready', 'Player not loaded');
  }

  // ---- output ----

  private sendState(conn: Conn, id: string | null) {
    const state = this.opts.player.getState();
    if (!state) return;
    this.send(conn, this.decorate(conn, { type: 'state', id, state }));
  }

  /** Per connection, because `thumb` depends on the session and refs are per device. */
  private broadcast(msg: ServerMessage) {
    for (const c of this.conns)
      if (c.deviceId) this.send(c, this.decorate(c, msg));
  }

  private capabilitiesFor(version: ProtocolVersion): string[] {
    if (!this.opts.catalog || version.minor < 1) return [];
    return [
      CAPABILITIES.catalogBrowse,
      CAPABILITIES.catalogSearch,
      CAPABILITIES.catalogThumbnails,
    ];
  }

  /**
   * SPEC 7: `thumb` on Track / QueueItem is omitted in 1.0 sessions; in 1.1
   * sessions with a catalog it is a ref for this device, or null.
   */
  private decorate(conn: Conn, msg: ServerMessage): ServerMessage {
    const withThumb =
      (conn.protocol?.minor ?? 0) >= 1 && this.opts.catalog !== undefined;
    const thumbs = conn.capabilities.includes(CAPABILITIES.catalogThumbnails);
    const track = <T extends { thumbnailUrl: string | null; thumb?: unknown }>(
      t: T,
    ): T => {
      const rest: T = { ...t };
      delete rest.thumb;
      if (!withThumb) return rest;
      rest.thumb =
        thumbs && conn.deviceId
          ? this.opts.catalog!.thumbFor(conn.deviceId, t.thumbnailUrl)
          : null;
      return rest;
    };
    switch (msg.type) {
      case 'track.changed':
        return { ...msg, track: msg.track ? track(msg.track) : null };
      case 'state':
        return {
          ...msg,
          state: {
            ...msg.state,
            track: msg.state.track ? track(msg.state.track) : null,
          },
        };
      case 'queue':
        return { ...msg, items: msg.items.map(track) };
      default:
        return msg;
    }
  }

  private sendError(
    conn: Conn,
    id: string | null,
    code: ErrorCode,
    message: string,
  ) {
    this.send(conn, { type: 'error', id, code, message, supported: null });
  }

  /** False if the message was dropped (closed socket, invalid or oversize). */
  private send(conn: Conn, msg: ServerMessage): boolean {
    if (conn.ws.readyState !== conn.ws.OPEN) return false;
    const checked = ServerMessageSchema.safeParse(msg);
    if (!checked.success) {
      // Log only the type and issue paths, never the message itself.
      this.log.warn(
        'phone-remote: dropped invalid outgoing',
        msg.type,
        checked.error.issues.map((i) => i.path.join('.')),
      );
      return false;
    }
    const json = JSON.stringify(checked.data);
    if (Buffer.byteLength(json, 'utf8') > LIMITS.serverMessageBytes) {
      this.log.warn('phone-remote: dropped oversize outgoing', msg.type);
      return false;
    }
    conn.ws.send(json);
    return true;
  }

  private close(conn: Conn, code: number) {
    if (conn.handshakeTimer) clearTimeout(conn.handshakeTimer);
    conn.handshakeTimer = null;
    const ws = conn.ws;
    if (ws.readyState === ws.OPEN || ws.readyState === ws.CONNECTING) {
      ws.close(code);
      // Don't wait forever for the peer's close frame.
      setTimeout(() => ws.terminate(), 2000).unref?.();
    }
  }

  private sweep() {
    const limit = this.opts.idleTimeoutMs ?? SILENT_LIMIT_MS;
    const now = Date.now();
    for (const c of this.conns) {
      if (now - c.lastActivity > limit) c.ws.terminate();
    }
  }
}

const rawToString = (data: RawData): string | null => {
  const buf = Array.isArray(data)
    ? Buffer.concat(data)
    : Buffer.isBuffer(data)
      ? data
      : Buffer.from(data);
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buf);
  } catch {
    return null;
  }
};

/** Best effort: echo a well-formed `id` from an invalid message. */
const extractId = (raw: string | null): string | null => {
  if (raw === null) return null;
  try {
    const v: unknown = JSON.parse(raw);
    if (v && typeof v === 'object' && 'id' in v) {
      const id = RequestIdSchema.safeParse(v.id);
      return id.success ? id.data : null;
    }
  } catch {
    /* not JSON */
  }
  return null;
};
