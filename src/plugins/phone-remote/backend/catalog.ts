/**
 * Catalog (SPEC 10): per-device refs, validation of what the page bridge
 * returns, page assembly, trimming and continuation semantics.
 *
 * The page bridge (catalog-page.ts) is treated as untrusted: every value it
 * returns is type-checked, cut to the protocol limits and dropped if it
 * doesn't fit. Endpoint blobs are opaque here: stored behind random refs
 * and handed back unchanged. Nothing from a response is logged.
 *
 * No Electron imports: runs under plain Node for tests.
 */
import { randomBytes } from 'node:crypto';

import {
  CatalogPageSchema,
  LIMITS,
  type CatalogBrowseTarget,
  type CatalogItem,
  type CatalogPage,
  type CatalogPlayAction,
  type ClientCommand,
  type ErrorCode,
  type ServerMessage,
} from '../../../../protocol/src/index.ts';

import type {
  CatalogReply,
  CatalogRequest,
  Opaque,
  RawItemKind,
} from '../catalog-types.ts';
import type { Thumb } from './thumbnails.ts';

export type CatalogCommand = Extract<
  ClientCommand,
  {
    type:
      | 'catalog.browse'
      | 'catalog.open'
      | 'catalog.search'
      | 'catalog.suggest'
      | 'catalog.play'
      | 'catalog.thumb';
  }
>;

export const isCatalogCommand = (cmd: ClientCommand): cmd is CatalogCommand =>
  cmd.type.startsWith('catalog.');

/** Talks to the page bridge. Its answers are untrusted. */
export interface CatalogPagePort {
  request(req: CatalogRequest): Promise<unknown>;
}

export interface ThumbSource {
  isAllowed(url: string): boolean;
  get(url: string): Promise<Thumb | null>;
}

export interface CatalogError {
  error: ErrorCode;
  message: string;
}

export type CatalogAnswer =
  | Extract<
      ServerMessage,
      {
        type:
          | 'catalog.page'
          | 'catalog.suggestions'
          | 'catalog.thumb'
          | 'result';
      }
    >
  | CatalogError;

/** What the listener needs; implemented by CatalogService. */
export interface CatalogHandler {
  handle(
    deviceId: string,
    cmd: CatalogCommand,
    ctx: { currentVideoId: string | null },
  ): Promise<CatalogAnswer>;
  /** A thumbnail ref for this device, or null if the URL isn't fetchable. */
  thumbFor(deviceId: string, url: string | null): string | null;
  forgetDevice(deviceId: string): void;
  clear(): void;
}

// ---- ref store ----

interface ItemTarget {
  open?: Opaque;
  play?: Opaque;
  shuffle?: Opaque;
  queue?: Opaque;
}

interface CursorState {
  scope: string;
  mode: 'sections' | 'items';
  title: string;
  subtitle: string | null;
  /** Continuation blob for the page bridge, or null when YouTube has no more. */
  next: Opaque | null;
  /** Items (items mode) or sections (sections mode) not sent yet. */
  bufferedItems: SafeItem[];
  bufferedSections: SafeSection[];
  layout: 'carousel' | 'list' | 'grid';
  sectionTitle: string | null;
}

export type RefEntry =
  | { type: 'item'; target: ItemTarget }
  | { type: 'cursor'; state: CursorState }
  | { type: 'thumb'; url: string };

interface Slot {
  entry: RefEntry;
  key: string | null;
  expiresAt: number;
}

const newRef = () => randomBytes(18).toString('base64url'); // 24 chars, 144 bits

/**
 * Per-device ref tables (SPEC 10.2). In memory only. A ref is valid for
 * refTtlMs after it was last sent; the same key gets the same ref while it
 * is valid. Each table is capped, evicting the least recently sent entry.
 */
export class RefStore {
  private readonly tables = new Map<
    string,
    { slots: Map<string, Slot>; byKey: Map<string, string> }
  >();
  private readonly now: () => number;
  private readonly ttl: number;
  private readonly cap: number;

  constructor(
    now: () => number = Date.now,
    ttlMs: number = LIMITS.refTtlMs,
    capPerDevice = 20_000,
  ) {
    this.now = now;
    this.ttl = ttlMs;
    this.cap = capPerDevice;
  }

  private table(deviceId: string) {
    let t = this.tables.get(deviceId);
    if (!t) {
      t = { slots: new Map(), byKey: new Map() };
      this.tables.set(deviceId, t);
    }
    return t;
  }

  /** Issues (or re-sends) a ref. `key` dedupes; null always makes a new one. */
  issue(deviceId: string, key: string | null, make: () => RefEntry): string {
    const t = this.table(deviceId);
    this.sweep(t);
    const now = this.now();
    if (key !== null) {
      const existing = t.byKey.get(key);
      const slot = existing ? t.slots.get(existing) : undefined;
      if (existing && slot && slot.expiresAt > now) {
        slot.expiresAt = now + this.ttl;
        t.slots.delete(existing);
        t.slots.set(existing, slot);
        return existing;
      }
    }
    let ref = newRef();
    while (t.slots.has(ref)) ref = newRef();
    t.slots.set(ref, { entry: make(), key, expiresAt: now + this.ttl });
    if (key !== null) t.byKey.set(key, ref);
    while (t.slots.size > this.cap) {
      const oldest = t.slots.keys().next().value;
      if (oldest === undefined) break;
      this.drop(t, oldest);
    }
    return ref;
  }

  get(deviceId: string, ref: string): RefEntry | null {
    const t = this.tables.get(deviceId);
    const slot = t?.slots.get(ref);
    if (!t || !slot) return null;
    if (slot.expiresAt <= this.now()) {
      this.drop(t, ref);
      return null;
    }
    return slot.entry;
  }

  /** Re-sends a ref: restarts its lifetime. False if it's unknown or expired. */
  touch(deviceId: string, ref: string): boolean {
    const t = this.tables.get(deviceId);
    const slot = t?.slots.get(ref);
    if (!t || !slot || slot.expiresAt <= this.now()) return false;
    slot.expiresAt = this.now() + this.ttl;
    t.slots.delete(ref);
    t.slots.set(ref, slot);
    return true;
  }

  size(deviceId: string) {
    return this.tables.get(deviceId)?.slots.size ?? 0;
  }

  forget(deviceId: string) {
    this.tables.delete(deviceId);
  }

  clear() {
    this.tables.clear();
  }

  private drop(
    t: { slots: Map<string, Slot>; byKey: Map<string, string> },
    ref: string,
  ) {
    const slot = t.slots.get(ref);
    t.slots.delete(ref);
    if (slot?.key !== null && slot?.key !== undefined) {
      if (t.byKey.get(slot.key) === ref) t.byKey.delete(slot.key);
    }
  }

  /** Slots are in last-sent order, so expired ones are at the front. */
  private sweep(t: { slots: Map<string, Slot>; byKey: Map<string, string> }) {
    const now = this.now();
    for (const [ref, slot] of t.slots) {
      if (slot.expiresAt > now) break;
      this.drop(t, ref);
    }
  }
}

// ---- validation of page bridge output ----

const KINDS: readonly RawItemKind[] = [
  'song',
  'video',
  'album',
  'single',
  'playlist',
  'artist',
  'episode',
  'station',
];
const LAYOUTS = ['carousel', 'list', 'grid'] as const;
const MAX_BLOB_CHARS = 8 * 1024;
const MAX_RAW_ITEMS = 5000;
const MAX_RAW_SECTIONS = 200;

type J = Record<string, unknown>;
const obj = (v: unknown): J | undefined =>
  v !== null && typeof v === 'object' && !Array.isArray(v)
    ? (v as J)
    : undefined;

const isControl = (code: number) => code <= 0x1f || code === 0x7f;
const hasControl = (s: string) => {
  for (let i = 0; i < s.length; i++)
    if (isControl(s.charCodeAt(i))) return true;
  return false;
};

/** Strips control characters and cuts to the text limit. */
export const cleanText = (v: unknown, max: number = LIMITS.textMax): string => {
  if (typeof v !== 'string') return '';
  let out = '';
  for (const ch of v) out += isControl(ch.charCodeAt(0)) ? ' ' : ch;
  return out.trim().slice(0, max);
};

const blob = (v: unknown): Opaque | undefined => {
  const o = obj(v);
  if (!o) return undefined;
  try {
    const s = JSON.stringify(o);
    if (s.length > MAX_BLOB_CHARS) return undefined;
    return JSON.parse(s) as Opaque; // plain-data copy
  } catch {
    return undefined;
  }
};

interface SafeItem {
  kind: RawItemKind;
  title: string;
  subtitle: string;
  durationSeconds: number | null;
  explicit: boolean;
  thumbnailUrl: string | null;
  videoId: string | null;
  target: ItemTarget;
}

interface SafeSection {
  title: string | null;
  layout: (typeof LAYOUTS)[number];
  items: SafeItem[];
}

interface SafePage {
  title: string | null;
  subtitle: string | null;
  header: {
    kind: 'album' | 'playlist' | 'artist';
    title: string;
    subtitle: string;
    thumbnailUrl: string | null;
    play?: Opaque;
    shuffle?: Opaque;
  } | null;
  chips: { title: string; open: Opaque }[];
  sections: SafeSection[];
  continuation: Opaque | null;
  mode: 'sections' | 'items';
}

const url = (v: unknown, isAllowed: (u: string) => boolean): string | null =>
  typeof v === 'string' && isAllowed(v) ? v : null;

const sanitizeItem = (
  v: unknown,
  isAllowed: (u: string) => boolean,
): SafeItem | null => {
  const o = obj(v);
  if (!o || !KINDS.includes(o.kind as RawItemKind)) return null;
  const target: ItemTarget = {};
  for (const k of ['open', 'play', 'shuffle', 'queue'] as const) {
    const b = blob(o[k]);
    if (b) target[k] = b;
  }
  if (!target.open && !target.play && !target.shuffle && !target.queue) {
    return null;
  }
  const d = o.durationSeconds;
  const videoId =
    typeof o.videoId === 'string' && /^[A-Za-z0-9_-]{11}$/.test(o.videoId)
      ? o.videoId
      : null;
  return {
    kind: o.kind as RawItemKind,
    title: cleanText(o.title),
    subtitle: cleanText(o.subtitle),
    durationSeconds:
      typeof d === 'number' && Number.isFinite(d) && d > 0 && d <= 86400
        ? d
        : null,
    explicit: o.explicit === true,
    thumbnailUrl: url(o.thumbnailUrl, isAllowed),
    videoId,
    target,
  };
};

const sanitizeSection = (
  v: unknown,
  isAllowed: (u: string) => boolean,
): SafeSection | null => {
  const o = obj(v);
  if (!o || !Array.isArray(o.items)) return null;
  const items = o.items
    .slice(0, MAX_RAW_ITEMS)
    .map((i) => sanitizeItem(i, isAllowed))
    .filter((i): i is SafeItem => i !== null);
  return {
    title: typeof o.title === 'string' ? cleanText(o.title) || null : null,
    layout: LAYOUTS.includes(o.layout as never)
      ? (o.layout as SafeSection['layout'])
      : 'carousel',
    items,
  };
};

export const sanitizePage = (
  v: unknown,
  isAllowed: (u: string) => boolean,
): SafePage | null => {
  const o = obj(v);
  if (!o || !Array.isArray(o.sections)) return null;
  const h = obj(o.header);
  const header =
    h && ['album', 'playlist', 'artist'].includes(h.kind as string)
      ? {
          kind: h.kind as 'album' | 'playlist' | 'artist',
          title: cleanText(h.title),
          subtitle: cleanText(h.subtitle),
          thumbnailUrl: url(h.thumbnailUrl, isAllowed),
          play: blob(h.play),
          shuffle: blob(h.shuffle),
        }
      : null;
  const chips = (Array.isArray(o.chips) ? o.chips : [])
    .map((c) => {
      const co = obj(c);
      const open = blob(co?.open);
      const title = cleanText(co?.title);
      return open && title ? { title, open } : null;
    })
    .filter((c): c is { title: string; open: Opaque } => c !== null)
    .slice(0, LIMITS.catalogChipsMax);
  const sections = o.sections
    .slice(0, MAX_RAW_SECTIONS)
    .map((s) => sanitizeSection(s, isAllowed))
    .filter((s): s is SafeSection => s !== null && s.items.length > 0);
  return {
    title: typeof o.title === 'string' ? cleanText(o.title) || null : null,
    subtitle:
      typeof o.subtitle === 'string' ? cleanText(o.subtitle) || null : null,
    header,
    chips,
    sections,
    continuation: blob(o.continuation) ?? null,
    mode: o.mode === 'items' ? 'items' : 'sections',
  };
};

export const sanitizeSuggestions = (v: unknown): string[] => {
  const out: string[] = [];
  for (const s of Array.isArray(v) ? v : []) {
    if (typeof s !== 'string') continue;
    const t = s.trim();
    if (
      t.length < 1 ||
      t.length > LIMITS.catalogQueryMax ||
      hasControl(t) ||
      out.includes(t)
    ) {
      continue;
    }
    out.push(t);
    if (out.length >= LIMITS.catalogSuggestionsMax) break;
  }
  return out;
};

type Failure = 'signed_out' | 'unavailable' | 'not_found' | 'not_ready';
const FAILURES: readonly Failure[] = [
  'signed_out',
  'unavailable',
  'not_found',
  'not_ready',
];

/** Untrusted reply -> one of the shapes we expect, or a failure code. */
const readReply = (v: unknown): CatalogReply => {
  const o = obj(v);
  if (!o) return { ok: false, code: 'unavailable' };
  if (o.ok === false) {
    return {
      ok: false,
      code: FAILURES.includes(o.code as Failure)
        ? (o.code as Failure)
        : 'unavailable',
    };
  }
  if (o.ok !== true) return { ok: false, code: 'unavailable' };
  if ('page' in o) return { ok: true, page: o.page as never };
  if ('suggestions' in o)
    return { ok: true, suggestions: o.suggestions as never };
  if (o.done === true) return { ok: true, done: true };
  return { ok: false, code: 'unavailable' };
};

// ---- page trimming ----

const bytes = (v: unknown) => Buffer.byteLength(JSON.stringify(v), 'utf8');

/**
 * SPEC 10.5: if the serialized page is over the server message limit, remove
 * items from the end of the largest sections (dropping sections that become
 * empty) until it fits. Mutates and returns the page.
 */
export const trimToFit = (
  page: CatalogPage,
  limit: number = LIMITS.serverMessageBytes,
): CatalogPage => {
  let total = bytes(page);
  if (total <= limit) return page;
  const sizes = page.sections.map((s) => s.items.map((i) => bytes(i) + 1));
  while (total > limit && page.sections.length > 0) {
    let largest = 0;
    for (let i = 1; i < page.sections.length; i++) {
      if (page.sections[i].items.length > page.sections[largest].items.length) {
        largest = i;
      }
    }
    const section = page.sections[largest];
    if (section.items.length > 1) {
      section.items.pop();
      total -= sizes[largest].pop() ?? 0;
    } else {
      total -= bytes(section) + 1;
      page.sections.splice(largest, 1);
      sizes.splice(largest, 1);
    }
    if (total <= limit) total = bytes(page); // confirm with the exact size
  }
  return page;
};

// ---- service ----

const BROWSE_TITLES: Record<CatalogBrowseTarget, string> = {
  'home': 'Home',
  'library.playlists': 'Playlists',
  'library.albums': 'Albums',
  'library.artists': 'Artists',
  'library.songs': 'Songs',
  'library.liked': 'Liked music',
};

const SINGLE_KINDS: ReadonlySet<RawItemKind> = new Set<RawItemKind>([
  'song',
  'video',
  'episode',
]);

const err = (error: ErrorCode, message: string): CatalogError => ({
  error,
  message,
});

const FAILURE_MESSAGES: Record<Failure, string> = {
  signed_out: 'YouTube Music is not signed in',
  unavailable: 'YouTube Music did not answer',
  not_found: 'Unknown or expired ref',
  not_ready: 'Player not loaded',
};

export interface CatalogServiceOptions {
  page: CatalogPagePort;
  thumbs: ThumbSource;
  refs?: RefStore;
}

export class CatalogService implements CatalogHandler {
  private readonly page: CatalogPagePort;
  private readonly thumbs: ThumbSource;
  readonly refs: RefStore;

  constructor(opts: CatalogServiceOptions) {
    this.page = opts.page;
    this.thumbs = opts.thumbs;
    this.refs = opts.refs ?? new RefStore();
  }

  forgetDevice(deviceId: string) {
    this.refs.forget(deviceId);
  }

  clear() {
    this.refs.clear();
  }

  thumbFor(deviceId: string, url: string | null): string | null {
    if (!url || !this.thumbs.isAllowed(url)) return null;
    return this.refs.issue(deviceId, `thumb:${url}`, () => ({
      type: 'thumb',
      url,
    }));
  }

  async handle(
    deviceId: string,
    cmd: CatalogCommand,
    ctx: { currentVideoId: string | null },
  ): Promise<CatalogAnswer> {
    switch (cmd.type) {
      case 'catalog.browse': {
        const scope = `browse:${cmd.target}`;
        if (cmd.continuation) {
          return this.continuePage(
            deviceId,
            cmd.id,
            scope,
            cmd.continuation,
            ctx,
          );
        }
        return this.firstPage(
          deviceId,
          cmd.id,
          scope,
          { op: 'browse', target: cmd.target },
          {
            title: BROWSE_TITLES[cmd.target],
            mode: cmd.target === 'home' ? 'sections' : 'items',
          },
          ctx,
        );
      }
      case 'catalog.open': {
        const scope = `open:${cmd.ref}`;
        if (cmd.continuation) {
          return this.continuePage(
            deviceId,
            cmd.id,
            scope,
            cmd.continuation,
            ctx,
          );
        }
        const entry = this.refs.get(deviceId, cmd.ref);
        if (entry?.type !== 'item' || !entry.target.open) {
          return err('not_found', FAILURE_MESSAGES.not_found);
        }
        return this.firstPage(
          deviceId,
          cmd.id,
          scope,
          { op: 'open', endpoint: entry.target.open },
          { title: '', mode: null, pageRef: cmd.ref, pageTarget: entry.target },
          ctx,
        );
      }
      case 'catalog.search': {
        const scope = `search:${cmd.filter}:${cmd.query}`;
        if (cmd.continuation) {
          return this.continuePage(
            deviceId,
            cmd.id,
            scope,
            cmd.continuation,
            ctx,
          );
        }
        return this.firstPage(
          deviceId,
          cmd.id,
          scope,
          { op: 'search', query: cmd.query, filter: cmd.filter },
          {
            title: cleanText(cmd.query),
            mode: cmd.filter === 'top' ? 'sections' : 'items',
          },
          ctx,
        );
      }
      case 'catalog.suggest': {
        const reply = readReply(
          await this.page.request({ op: 'suggest', query: cmd.query }),
        );
        if (!reply.ok) return err(reply.code, FAILURE_MESSAGES[reply.code]);
        if (!('suggestions' in reply)) {
          return err('unavailable', FAILURE_MESSAGES.unavailable);
        }
        return {
          type: 'catalog.suggestions',
          id: cmd.id,
          items: sanitizeSuggestions(reply.suggestions),
        };
      }
      case 'catalog.play': {
        const entry = this.refs.get(deviceId, cmd.ref);
        const endpoint =
          entry?.type === 'item' ? endpointFor(entry.target, cmd.action) : null;
        if (!endpoint) return err('not_found', 'Action not offered');
        const reply = readReply(
          await this.page.request({ op: 'play', action: cmd.action, endpoint }),
        );
        if (!reply.ok) return err(reply.code, FAILURE_MESSAGES[reply.code]);
        return { type: 'result', id: cmd.id };
      }
      case 'catalog.thumb': {
        const entry = this.refs.get(deviceId, cmd.thumb);
        if (entry?.type !== 'thumb') {
          return err('not_found', FAILURE_MESSAGES.not_found);
        }
        const thumb = await this.thumbs.get(entry.url);
        if (!thumb) return err('unavailable', 'Thumbnail unavailable');
        return {
          type: 'catalog.thumb',
          id: cmd.id,
          mime: thumb.mime,
          data: thumb.data,
        };
      }
    }
  }

  // ---- pages ----

  private async firstPage(
    deviceId: string,
    id: string,
    scope: string,
    req: CatalogRequest,
    opts: {
      title: string;
      /** Forced mode, or null to take the page bridge's. */
      mode: 'sections' | 'items' | null;
      pageRef?: string;
      pageTarget?: ItemTarget;
    },
    ctx: { currentVideoId: string | null },
  ): Promise<CatalogAnswer> {
    const reply = readReply(await this.page.request(req));
    if (!reply.ok) return err(reply.code, FAILURE_MESSAGES[reply.code]);
    if (!('page' in reply))
      return err('unavailable', FAILURE_MESSAGES.unavailable);
    const raw = sanitizePage(reply.page, (u) => this.thumbs.isAllowed(u));
    if (!raw) return err('unavailable', FAILURE_MESSAGES.unavailable);

    const mode = opts.mode ?? raw.mode;
    const title = opts.title || raw.title || raw.header?.title || '';
    const subtitle = raw.subtitle;

    // Header: the page's own ref gets the header's play/shuffle endpoints.
    let header: CatalogPage['header'] = null;
    if (raw.header && opts.pageRef && opts.pageTarget) {
      const target = opts.pageTarget;
      if (raw.header.play) target.play = raw.header.play;
      if (raw.header.shuffle) target.shuffle = raw.header.shuffle;
      // Re-send the page ref so its 30 minutes restart.
      const ref = this.reissue(deviceId, opts.pageRef, target);
      header = {
        kind: raw.header.kind,
        title: raw.header.title,
        subtitle: raw.header.subtitle,
        thumb: this.thumbFor(deviceId, raw.header.thumbnailUrl),
        ref,
        actions: (['play', 'shuffle'] as const).filter((a) => target[a]),
      };
    }

    const state: CursorState = {
      scope,
      mode,
      title,
      subtitle,
      next: raw.continuation,
      bufferedItems: [],
      bufferedSections: [],
      layout: 'list',
      sectionTitle: null,
    };

    let sections: SafeSection[];
    if (mode === 'items') {
      sections = raw.sections.slice(-LIMITS.catalogSectionsMax);
      const last = sections.at(-1);
      if (last) {
        state.layout = last.layout;
        state.sectionTitle = last.title;
        if (last.items.length > LIMITS.catalogItemsMax) {
          state.bufferedItems = last.items.slice(LIMITS.catalogItemsMax);
          sections[sections.length - 1] = {
            ...last,
            items: last.items.slice(0, LIMITS.catalogItemsMax),
          };
        }
      }
    } else {
      sections = raw.sections.slice(0, LIMITS.catalogSectionsMax);
      state.bufferedSections = raw.sections.slice(LIMITS.catalogSectionsMax);
    }

    const page: CatalogPage = {
      type: 'catalog.page',
      id,
      title,
      subtitle,
      header,
      chips: raw.chips.map((c) => ({
        title: c.title,
        ref: this.itemRef(deviceId, { open: c.open }),
      })),
      sections: sections.map((s) => this.toSection(deviceId, s, ctx)),
      append: null,
      continuation: this.cursorFor(deviceId, state),
    };
    return this.finish(page);
  }

  private async continuePage(
    deviceId: string,
    id: string,
    scope: string,
    cursor: string,
    ctx: { currentVideoId: string | null },
  ): Promise<CatalogAnswer> {
    const entry = this.refs.get(deviceId, cursor);
    if (entry?.type !== 'cursor' || entry.state.scope !== scope) {
      return err('not_found', 'Unknown or expired cursor');
    }
    const prev = entry.state;
    const state: CursorState = {
      ...prev,
      bufferedItems: [...prev.bufferedItems],
      bufferedSections: [...prev.bufferedSections],
    };

    // Refill from YouTube when the buffer is empty (skip empty continuations, bounded).
    for (
      let hops = 0;
      hops < 3 &&
      state.next &&
      state.bufferedItems.length === 0 &&
      state.bufferedSections.length === 0;
      hops++
    ) {
      const reply = readReply(
        await this.page.request({ op: 'continue', continuation: state.next }),
      );
      if (!reply.ok) return err(reply.code, FAILURE_MESSAGES[reply.code]);
      if (!('page' in reply)) {
        return err('unavailable', FAILURE_MESSAGES.unavailable);
      }
      const raw = sanitizePage(reply.page, (u) => this.thumbs.isAllowed(u));
      if (!raw) return err('unavailable', FAILURE_MESSAGES.unavailable);
      state.next = raw.continuation;
      if (state.mode === 'items') {
        state.bufferedItems = raw.sections.flatMap((s) => s.items);
      } else state.bufferedSections = raw.sections;
    }

    let sections: CatalogPage['sections'];
    let append: CatalogPage['append'];
    if (state.mode === 'items' && state.bufferedItems.length > 0) {
      const items = state.bufferedItems.splice(0, LIMITS.catalogItemsMax);
      append = 'items';
      sections = [
        this.toSection(
          deviceId,
          { title: state.sectionTitle, layout: state.layout, items },
          ctx,
        ),
      ];
    } else {
      // Sections mode, or an items page whose continuation came back empty:
      // 'items' needs exactly one non-empty section, so an empty answer is
      // sent as an empty 'sections' append.
      append = 'sections';
      sections = state.bufferedSections
        .splice(0, LIMITS.catalogSectionsMax)
        .map((s) => this.toSection(deviceId, s, ctx));
    }

    const page: CatalogPage = {
      type: 'catalog.page',
      id,
      title: state.title,
      subtitle: state.subtitle,
      header: null,
      chips: [],
      sections,
      append,
      continuation: this.cursorFor(deviceId, state),
    };
    return this.finish(page);
  }

  private finish(page: CatalogPage): CatalogAnswer {
    trimToFit(page);
    if (page.append === 'items' && page.sections.length !== 1) {
      return err('internal', 'Page could not be built');
    }
    const checked = CatalogPageSchema.safeParse(page);
    if (!checked.success) return err('internal', 'Page could not be built');
    return page;
  }

  private cursorFor(deviceId: string, state: CursorState): string | null {
    const more =
      state.next !== null ||
      state.bufferedItems.length > 0 ||
      state.bufferedSections.length > 0;
    if (!more) return null;
    return this.refs.issue(deviceId, null, () => ({ type: 'cursor', state }));
  }

  private itemRef(deviceId: string, target: ItemTarget): string {
    return this.refs.issue(deviceId, `item:${JSON.stringify(target)}`, () => ({
      type: 'item',
      target,
    }));
  }

  /** Keeps the page's ref (restarting its lifetime); a fresh one if it expired meanwhile. */
  private reissue(deviceId: string, ref: string, target: ItemTarget): string {
    const entry = this.refs.get(deviceId, ref);
    if (entry?.type === 'item' && this.refs.touch(deviceId, ref)) {
      entry.target = target;
      return ref;
    }
    return this.itemRef(deviceId, target);
  }

  private toSection(
    deviceId: string,
    s: SafeSection,
    ctx: { currentVideoId: string | null },
  ): CatalogPage['sections'][number] {
    return {
      title: s.title,
      layout: s.layout,
      items: s.items
        .slice(0, LIMITS.catalogItemsMax)
        .map((i) => this.toItem(deviceId, i, ctx)),
    };
  }

  private toItem(
    deviceId: string,
    i: SafeItem,
    ctx: { currentVideoId: string | null },
  ): CatalogItem {
    const actions: CatalogItem['actions'] = [];
    if (i.target.open) actions.push('open');
    if (i.target.play) actions.push('play');
    if (i.target.shuffle) actions.push('shuffle');
    if (i.target.queue) actions.push('playNext', 'addToQueue');
    return {
      ref: this.itemRef(deviceId, i.target),
      kind: i.kind,
      title: i.title,
      subtitle: i.subtitle,
      durationSeconds: i.durationSeconds,
      explicit: i.explicit,
      actions,
      thumb: this.thumbFor(deviceId, i.thumbnailUrl),
      isCurrent:
        SINGLE_KINDS.has(i.kind) &&
        i.videoId !== null &&
        i.videoId === ctx.currentVideoId,
    };
  }
}

const endpointFor = (
  t: ItemTarget,
  action: CatalogPlayAction,
): Opaque | null => {
  switch (action) {
    case 'play':
      return t.play ?? null;
    case 'shuffle':
      return t.shuffle ?? null;
    case 'playNext':
    case 'addToQueue':
      return t.queue ?? null;
  }
};
