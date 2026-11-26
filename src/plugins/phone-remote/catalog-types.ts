/**
 * Plain data exchanged between the catalog page bridge (catalog-page.ts, in
 * the YT Music page) and the backend (backend/catalog.ts). Types only.
 *
 * `Opaque` values are endpoint blobs built by catalog-page.ts. The backend
 * stores them per device behind random refs and hands them back unchanged;
 * it never reads or builds them. catalog-page.ts re-validates every blob it
 * gets back before using it.
 */
import type {
  CatalogBrowseTarget,
  CatalogPlayAction,
  CatalogSearchFilter,
} from '../../../protocol/src/index.ts';

export type Opaque = Record<string, unknown>;

export type RawItemKind =
  | 'song'
  | 'video'
  | 'album'
  | 'single'
  | 'playlist'
  | 'artist'
  | 'episode'
  | 'station';

export interface RawItem {
  kind: RawItemKind;
  title: string;
  subtitle: string;
  durationSeconds: number | null;
  explicit: boolean;
  /** Smallest suitable cover URL, or null. */
  thumbnailUrl: string | null;
  /** For songs, videos and episodes: the video ID (used only for `isCurrent`). */
  videoId: string | null;
  /** Endpoint blobs; each one present enables the matching actions. */
  open?: Opaque;
  play?: Opaque;
  shuffle?: Opaque;
  /** Enables playNext and addToQueue. */
  queue?: Opaque;
}

export interface RawSection {
  title: string | null;
  layout: 'carousel' | 'list' | 'grid';
  items: RawItem[];
}

export interface RawHeader {
  kind: 'album' | 'playlist' | 'artist';
  title: string;
  subtitle: string;
  thumbnailUrl: string | null;
  play?: Opaque;
  shuffle?: Opaque;
}

export interface RawPage {
  title: string | null;
  subtitle: string | null;
  header: RawHeader | null;
  chips: { title: string; open: Opaque }[];
  sections: RawSection[];
  /** Continuation blob for `continue`, or null when there is no more. */
  continuation: Opaque | null;
  /**
   * How continuations of this page join it (SPEC 10.4): 'sections' for
   * feed-like pages, 'items' for list pages (the list is the last section).
   */
  mode: 'sections' | 'items';
}

export type CatalogRequest =
  | { op: 'browse'; target: CatalogBrowseTarget }
  | { op: 'open'; endpoint: Opaque }
  | { op: 'search'; query: string; filter: CatalogSearchFilter }
  | { op: 'continue'; continuation: Opaque }
  | { op: 'suggest'; query: string }
  | { op: 'play'; action: CatalogPlayAction; endpoint: Opaque };

export type CatalogFailure =
  | 'signed_out'
  | 'unavailable'
  | 'not_found'
  | 'not_ready';

/** What catalog-page.ts answers. The backend treats it as untrusted. */
export type CatalogReply =
  | { ok: true; page: RawPage }
  | { ok: true; suggestions: string[] }
  | { ok: true; done: true }
  | { ok: false; code: CatalogFailure };
