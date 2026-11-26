import { randomUUID } from 'node:crypto';

import { type BrowserWindow, ipcMain, type IpcMainEvent } from 'electron';

import { getSongControls } from '@/providers/song-controls';
import { registerCallback, type SongInfo } from '@/providers/song-info';

import { PlayerStateTracker } from './player-state.ts';

import {
  LIMITS,
  QueueItemSchema,
  type ServerMessage,
} from '../../../../protocol/src/index.ts';
import { CHANNELS } from '../channels';

import type { ControlCommand, PlayerPort, QueueItem } from './server.ts';

const REPEAT_CYCLE = ['NONE', 'ALL', 'ONE'] as const;
const QUEUE_TIMEOUT_MS = 3000;

// song-info has no unregister, so register once and route to the live bridge.
let activeHandler: ((info: SongInfo) => void) | null = null;
let songInfoRegistered = false;
let lastSongInfo: SongInfo | null = null;
const ensureSongInfoCallback = () => {
  if (songInfoRegistered) return;
  songInfoRegistered = true;
  registerCallback((info) => {
    lastSongInfo = info;
    activeHandler?.(info);
  });
};

const toRawTrack = (info: SongInfo | null) =>
  info
    ? {
        videoId: info.videoId,
        title: info.title,
        artist: info.artist,
        album: info.album ?? null,
        songDuration: info.songDuration,
        imageSrc: info.imageSrc ?? null,
      }
    : null;

const sanitizeQueueItem = (raw: unknown): QueueItem | null => {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  const thumb = QueueItemSchema.shape.thumbnailUrl.safeParse(
    r.thumbnailUrl ?? null,
  );
  const duration =
    typeof r.durationSeconds === 'number' &&
    r.durationSeconds > 0 &&
    r.durationSeconds <= 86400
      ? r.durationSeconds
      : null;
  const parsed = QueueItemSchema.safeParse({
    index: r.index,
    videoId: r.videoId,
    title: typeof r.title === 'string' ? r.title.slice(0, LIMITS.textMax) : '',
    artist:
      typeof r.artist === 'string' ? r.artist.slice(0, LIMITS.textMax) : '',
    durationSeconds: duration,
    thumbnailUrl: thumb.success ? thumb.data : null,
    isCurrent: r.isCurrent === true,
  });
  return parsed.success ? parsed.data : null;
};

/**
 * PlayerPort on top of the app's own player controls (song-controls) and
 * song-info provider, plus this plugin's page bridge for state the app
 * doesn't expose (renderer.ts).
 */
export class PlayerBridge implements PlayerPort {
  private readonly win: BrowserWindow;
  private readonly controls: ReturnType<typeof getSongControls>;
  private readonly listeners = new Set<(msg: ServerMessage) => void>();
  private readonly tracker: PlayerStateTracker;
  private readonly pendingQueue = new Map<string, (items: unknown) => void>();

  constructor(win: BrowserWindow) {
    this.win = win;
    this.controls = getSongControls(win);
    this.tracker = new PlayerStateTracker((msg) => {
      for (const l of this.listeners) l(msg);
    });
  }

  start() {
    ensureSongInfoCallback();
    activeHandler = this.onSongInfo;
    this.tracker.setTrack(toRawTrack(lastSongInfo));
    ipcMain.on(CHANNELS.snapshot, this.onSnapshot);
    ipcMain.on(CHANNELS.queue, this.onQueue);
    this.win.webContents.on('did-navigate', this.onNavigate);
    this.win.webContents.send(CHANNELS.requestSnapshot);
  }

  stop() {
    if (activeHandler === this.onSongInfo) activeHandler = null;
    ipcMain.off(CHANNELS.snapshot, this.onSnapshot);
    ipcMain.off(CHANNELS.queue, this.onQueue);
    this.win.webContents.off('did-navigate', this.onNavigate);
    for (const resolve of this.pendingQueue.values()) resolve(null);
    this.pendingQueue.clear();
    this.tracker.dispose();
    this.listeners.clear();
  }

  private readonly onSongInfo = (info: SongInfo) => {
    this.tracker.setTrack(toRawTrack(info));
  };

  // ---- PlayerPort ----

  getState() {
    return this.tracker.getState();
  }

  subscribe(listener: (msg: ServerMessage) => void) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async getQueue(): Promise<{ items: QueueItem[]; truncated: boolean } | null> {
    if (!this.tracker.isReady()) return null;
    const requestId = randomUUID();
    const raw = await new Promise<unknown>((resolve) => {
      const timer = setTimeout(() => {
        this.pendingQueue.delete(requestId);
        resolve(null);
      }, QUEUE_TIMEOUT_MS);
      this.pendingQueue.set(requestId, (items) => {
        clearTimeout(timer);
        resolve(items);
      });
      this.win.webContents.send(CHANNELS.getQueue, requestId);
    });
    if (!Array.isArray(raw)) return null;
    const items = raw
      .slice(0, LIMITS.queueMaxItems)
      .map(sanitizeQueueItem)
      .filter((x): x is QueueItem => x !== null);
    return { items, truncated: raw.length > LIMITS.queueMaxItems };
  }

  async execute(
    cmd: ControlCommand,
  ): Promise<'ok' | 'not_ready' | 'stale_queue'> {
    const page = this.tracker.getPage();
    const state = this.tracker.getState();
    if (!page || !state) return 'not_ready';
    const c = this.controls;
    switch (cmd.type) {
      case 'player.play':
        c.play();
        break;
      case 'player.pause':
        c.pause();
        break;
      case 'player.togglePlay':
        c.playPause();
        break;
      case 'player.next':
        c.next();
        break;
      case 'player.previous':
        c.previous();
        break;
      case 'player.seek': {
        const duration = state.track?.durationSeconds;
        c.seekTo(
          duration != null
            ? Math.min(cmd.positionSeconds, duration)
            : cmd.positionSeconds,
        );
        break;
      }
      case 'player.setVolume':
        // Through the page's player API, not the player bar's updateVolume:
        // that path snaps to the bar slider's steps, so the reported volume
        // drifted away from what the phone set.
        this.win.webContents.send(CHANNELS.setVolume, cmd.volume);
        break;
      case 'player.setMuted':
        if (page.muted !== cmd.muted) c.muteUnmute();
        break;
      case 'player.toggleShuffle':
        c.shuffle();
        break;
      case 'player.setRepeat': {
        const from = REPEAT_CYCLE.indexOf(page.repeat);
        const to = REPEAT_CYCLE.indexOf(cmd.mode);
        const steps = (to - from + REPEAT_CYCLE.length) % REPEAT_CYCLE.length;
        if (steps > 0) c.switchRepeat(steps);
        break;
      }
      case 'track.setLike':
        if (page.like === cmd.status) break;
        if (cmd.status === 'LIKE') c.like();
        else if (cmd.status === 'DISLIKE') c.dislike();
        else this.win.webContents.send(CHANNELS.clearLike);
        break;
      case 'queue.play': {
        const queue = await this.getQueue();
        if (!queue) return 'not_ready';
        const item = queue.items.find((i) => i.index === cmd.index);
        if (!item || item.videoId !== cmd.videoId) return 'stale_queue';
        c.setQueueIndex(cmd.index);
        break;
      }
    }
    return 'ok';
  }

  // ---- IPC from the page bridge (only from our own window) ----

  private readonly onSnapshot = (event: IpcMainEvent, snapshot: unknown) => {
    if (event.sender !== this.win.webContents) return;
    if (!snapshot || typeof snapshot !== 'object') return;
    this.tracker.setPage(snapshot);
  };

  private readonly onQueue = (
    event: IpcMainEvent,
    requestId: unknown,
    items: unknown,
  ) => {
    if (event.sender !== this.win.webContents) return;
    if (typeof requestId !== 'string') return;
    const resolve = this.pendingQueue.get(requestId);
    if (!resolve) return;
    this.pendingQueue.delete(requestId);
    resolve(items);
  };

  /** Full page loads only (not in-app navigation): the bridge reattaches. */
  private readonly onNavigate = () => {
    this.tracker.reset();
  };
}
