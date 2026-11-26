import {
  LIMITS,
  TrackSchema,
  type PlayerState,
  type ServerMessage,
  type Track,
} from '../../../../protocol/src/index.ts';

/** What the page bridge (renderer.ts) reports. Already plain data. */
export interface PageSnapshot {
  status: PlayerState['playback']['status'];
  positionSeconds: number;
  volume: number;
  muted: boolean;
  repeat: PlayerState['repeat'];
  shuffle: boolean;
  like: PlayerState['like'];
  /** Bumped by the page whenever the queue contents change. */
  queueRevision: number;
}

/** Raw track fields from the song-info provider, before sanitising. */
export interface RawTrack {
  videoId: string;
  title: string;
  artist: string;
  album?: string | null;
  songDuration?: number;
  imageSrc?: string | null;
}

type EventMessage = Extract<
  ServerMessage,
  {
    type:
      | 'track.changed'
      | 'playback.changed'
      | 'volume.changed'
      | 'repeat.changed'
      | 'shuffle.changed'
      | 'like.changed'
      | 'queue.changed';
  }
>;

const clampSeconds = (n: unknown) =>
  typeof n === 'number' && Number.isFinite(n)
    ? Math.min(Math.max(n, 0), 24 * 60 * 60)
    : 0;

const text = (s: unknown) =>
  typeof s === 'string' ? s.slice(0, LIMITS.textMax) : '';

/** Turns provider data into a schema-valid Track, or null if it can't. */
export const sanitizeTrack = (
  raw: RawTrack | null | undefined,
): Track | null => {
  if (!raw) return null;
  const duration =
    typeof raw.songDuration === 'number' &&
    raw.songDuration > 0 &&
    raw.songDuration <= 24 * 60 * 60
      ? raw.songDuration
      : null;
  const thumb = TrackSchema.shape.thumbnailUrl.safeParse(raw.imageSrc ?? null);
  const candidate = {
    videoId: raw.videoId,
    title: text(raw.title),
    artist: text(raw.artist),
    album: raw.album ? text(raw.album) : null,
    durationSeconds: duration,
    thumbnailUrl: thumb.success ? thumb.data : null,
  };
  const parsed = TrackSchema.safeParse(candidate);
  return parsed.success ? parsed.data : null;
};

export const sanitizeSnapshot = (s: Partial<PageSnapshot>): PageSnapshot => ({
  status: (['playing', 'paused', 'buffering', 'stopped'] as const).includes(
    s.status as never,
  )
    ? (s.status as PageSnapshot['status'])
    : 'stopped',
  positionSeconds: clampSeconds(s.positionSeconds),
  volume: Math.min(100, Math.max(0, Math.round(Number(s.volume) || 0))),
  muted: s.muted === true,
  repeat: (['NONE', 'ALL', 'ONE'] as const).includes(s.repeat as never)
    ? (s.repeat as PageSnapshot['repeat'])
    : 'NONE',
  shuffle: s.shuffle === true,
  like: (['LIKE', 'DISLIKE', 'INDIFFERENT'] as const).includes(s.like as never)
    ? (s.like as PageSnapshot['like'])
    : 'INDIFFERENT',
  queueRevision: Number.isSafeInteger(s.queueRevision)
    ? (s.queueRevision as number)
    : 0,
});

const MIN_INTERVAL_MS = 1000 / 10; // at most 10 events per second per type
const DRIFT_SECONDS = 2;

/**
 * Holds the current PlayerState and turns updates into change events
 * (SPEC 7.2): only on real changes, playback on status change or position
 * jump, bursts coalesced to 10/s per type.
 */
export class PlayerStateTracker {
  private track: Track | null = null;
  private page: PageSnapshot | null = null;
  /** For drift detection: last sent playback and when. */
  private sentPlayback: {
    status: string;
    position: number;
    at: number;
  } | null = null;

  private readonly lastEmit = new Map<string, number>();
  private readonly pending = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly emitFn: (msg: EventMessage) => void;
  private readonly now: () => number;

  constructor(emit: (msg: EventMessage) => void, now: () => number = Date.now) {
    this.emitFn = emit;
    this.now = now;
  }

  isReady(): boolean {
    return this.page !== null;
  }

  getState(): PlayerState | null {
    if (!this.page) return null;
    return {
      track: this.track,
      playback: {
        status: this.page.status,
        positionSeconds: this.page.positionSeconds,
      },
      volume: { volume: this.page.volume, muted: this.page.muted },
      repeat: this.page.repeat,
      shuffle: this.page.shuffle,
      like: this.page.like,
    };
  }

  getPage(): PageSnapshot | null {
    return this.page ? { ...this.page } : null;
  }

  setTrack(raw: RawTrack | null) {
    const next = sanitizeTrack(raw);
    if (JSON.stringify(next) === JSON.stringify(this.track)) return;
    this.track = next;
    this.schedule('track.changed');
  }

  setPage(raw: Partial<PageSnapshot>) {
    const next = sanitizeSnapshot(raw);
    const prev = this.page;
    this.page = next;
    if (!prev) {
      this.markPlaybackSent();
      return; // first snapshot: phones get it through `state`
    }
    if (prev.volume !== next.volume || prev.muted !== next.muted) {
      this.schedule('volume.changed');
    }
    if (prev.repeat !== next.repeat) this.schedule('repeat.changed');
    if (prev.shuffle !== next.shuffle) this.schedule('shuffle.changed');
    if (prev.like !== next.like) this.schedule('like.changed');
    if (prev.queueRevision !== next.queueRevision) {
      this.schedule('queue.changed');
    }
    if (this.playbackNeedsUpdate(next)) this.schedule('playback.changed');
  }

  /** Clears state when the page goes away. */
  reset() {
    for (const t of this.pending.values()) clearTimeout(t);
    this.pending.clear();
    this.page = null;
    this.sentPlayback = null;
  }

  dispose() {
    this.reset();
  }

  private playbackNeedsUpdate(next: PageSnapshot): boolean {
    const sent = this.sentPlayback;
    if (!sent || sent.status !== next.status) return true;
    const elapsed = (this.now() - sent.at) / 1000;
    const expected =
      sent.status === 'playing' ? sent.position + elapsed : sent.position;
    return Math.abs(next.positionSeconds - expected) > DRIFT_SECONDS;
  }

  private markPlaybackSent() {
    if (!this.page) return;
    this.sentPlayback = {
      status: this.page.status,
      position: this.page.positionSeconds,
      at: this.now(),
    };
  }

  private schedule(type: EventMessage['type']) {
    if (this.pending.has(type)) return; // trailing emit already queued
    const wait =
      (this.lastEmit.get(type) ?? -Infinity) + MIN_INTERVAL_MS - this.now();
    if (wait <= 0) {
      this.flush(type);
      return;
    }
    const timer = setTimeout(() => {
      this.pending.delete(type);
      this.flush(type);
    }, wait);
    timer.unref?.();
    this.pending.set(type, timer);
  }

  private flush(type: EventMessage['type']) {
    const state = this.getState();
    this.lastEmit.set(type, this.now());
    switch (type) {
      case 'track.changed':
        this.emitFn({ type, track: this.track });
        return;
      case 'queue.changed':
        this.emitFn({ type });
        return;
    }
    if (!state) return;
    switch (type) {
      case 'playback.changed':
        this.markPlaybackSent();
        this.emitFn({ type, playback: state.playback });
        return;
      case 'volume.changed':
        this.emitFn({ type, volume: state.volume });
        return;
      case 'repeat.changed':
        this.emitFn({ type, repeat: state.repeat });
        return;
      case 'shuffle.changed':
        this.emitFn({ type, shuffle: state.shuffle });
        return;
      case 'like.changed':
        this.emitFn({ type, like: state.like });
        return;
    }
  }
}

export type { EventMessage };
