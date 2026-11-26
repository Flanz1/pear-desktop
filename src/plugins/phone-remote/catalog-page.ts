/**
 * Catalog page bridge: together with renderer.ts, the ONLY module of this
 * plugin that touches YT Music page internals. This one owns everything the
 * catalog needs from YouTube: innertube request paths, browse IDs, search
 * params, response shapes, and how playback is started.
 *
 * It uses the page's own signed-in innertube client
 * (`ytmusic-app`.networkManager.fetch), so no cookie or token ever leaves the
 * page. It turns responses into plain data (catalog-types.ts). Anything it
 * can't parse is skipped, never guessed. It never logs responses.
 *
 * The parsing functions are pure and run under plain Node for tests; only
 * `createPageEnv` touches the DOM.
 */
import type {
  CatalogFailure,
  CatalogReply,
  CatalogRequest,
  Opaque,
  RawHeader,
  RawItem,
  RawItemKind,
  RawPage,
  RawSection,
} from './catalog-types.ts';

// ---- YouTube Music constants ----

/** Browse IDs for `catalog.browse` targets. */
export const BROWSE_IDS = {
  'home': 'FEmusic_home',
  'library.playlists': 'FEmusic_liked_playlists',
  'library.albums': 'FEmusic_liked_albums',
  'library.artists': 'FEmusic_library_corpus_track_artists',
  'library.songs': 'FEmusic_liked_videos',
  /** The "Liked music" auto playlist (LM). */
  'library.liked': 'VLLM',
} as const;

/** `/search` params per filter (as used by YT Music's own filter chips). */
export const SEARCH_PARAMS = {
  top: undefined,
  songs: 'EgWKAQIIAWoMEA4QChADEAQQCRAF',
  videos: 'EgWKAQIQAWoMEA4QChADEAQQCRAF',
  albums: 'EgWKAQIYAWoMEA4QChADEAQQCRAF',
  artists: 'EgWKAQIgAWoMEA4QChADEAQQCRAF',
  playlists: 'Eg-KAQwIABAAGAAgACgBMABqChAEEAMQCRAFEAo%3D',
} as const;

export const PATHS = {
  browse: '/browse',
  search: '/search',
  suggestions: '/music/get_search_suggestions',
  queue: '/music/get_queue',
} as const;

const TITLES: Record<string, string> = {
  'home': 'Home',
  'library.playlists': 'Playlists',
  'library.albums': 'Albums',
  'library.artists': 'Artists',
  'library.songs': 'Songs',
  'library.liked': 'Liked music',
};

// ---- small JSON helpers ----

type J = Record<string, unknown>;

const obj = (v: unknown): J | undefined =>
  v !== null && typeof v === 'object' && !Array.isArray(v)
    ? (v as J)
    : undefined;
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string | undefined =>
  typeof v === 'string' ? v : undefined;

/** Safe deep access: at(x, 'a', 0, 'b'). */
export const at = (v: unknown, ...path: (string | number)[]): unknown => {
  let cur = v;
  for (const p of path) {
    if (typeof p === 'number') cur = Array.isArray(cur) ? cur[p] : undefined;
    else cur = obj(cur)?.[p];
    if (cur === undefined) return undefined;
  }
  return cur;
};

/** Text of a `{runs}` or `{simpleText}` object. */
export const textOf = (t: unknown): string => {
  const o = obj(t);
  if (!o) return '';
  if (typeof o.simpleText === 'string') return o.simpleText;
  return arr(o.runs)
    .map((r) => str(obj(r)?.text) ?? '')
    .join('');
};

const ID_RE = /^[A-Za-z0-9_-]{1,200}$/;
const PARAMS_RE = /^[A-Za-z0-9_%=+/.-]{1,4000}$/;
const TOKEN_RE = /^[A-Za-z0-9_%=+/.-]{1,8000}$/;

const id = (v: unknown) => {
  const s = str(v);
  return s && ID_RE.test(s) ? s : undefined;
};
const params = (v: unknown) => {
  const s = str(v);
  return s && PARAMS_RE.test(s) ? s : undefined;
};
const token = (v: unknown) => {
  const s = str(v);
  return s && TOKEN_RE.test(s) ? s : undefined;
};

/** Drops undefined fields so blobs stay small and stable. */
const compact = <T extends J>(o: T): T => {
  const out: J = {};
  for (const [k, v] of Object.entries(o)) if (v !== undefined) out[k] = v;
  return out as T;
};

// ---- endpoint blobs (normalised, whitelisted fields only) ----

interface BrowseBlob {
  browse: { browseId: string; params?: string; pageType?: string };
}
interface WatchBlob {
  watch: {
    videoId?: string;
    playlistId?: string;
    params?: string;
    playerParams?: string;
    index?: number;
    musicVideoType?: string;
  };
}
interface WatchPlaylistBlob {
  watchPlaylist: { playlistId: string; params?: string };
}
interface QueueBlob {
  queue: { videoId: string } | { playlistId: string };
}
interface ContinuationBlob {
  continuation: {
    token: string;
    path: 'browse' | 'search';
    query?: string;
    params?: string;
    layout: RawSection['layout'];
    title: string | null;
  };
}

const PAGE_TYPE_RE = /^MUSIC_PAGE_TYPE_[A-Z_]{1,60}$/;
const VIDEO_TYPE_RE = /^MUSIC_VIDEO_TYPE_[A-Z_]{1,60}$/;

/** From an innertube navigation endpoint (`{browseEndpoint}`). */
export const browseOf = (nav: unknown): BrowseBlob | undefined => {
  const b = obj(at(nav, 'browseEndpoint'));
  const browseId = id(b?.browseId);
  if (!b || !browseId) return undefined;
  const pageType = str(
    at(
      b,
      'browseEndpointContextSupportedConfigs',
      'browseEndpointContextMusicConfig',
      'pageType',
    ),
  );
  return {
    browse: compact({
      browseId,
      params: params(b.params),
      pageType: pageType && PAGE_TYPE_RE.test(pageType) ? pageType : undefined,
    }),
  };
};

export const watchOf = (nav: unknown): WatchBlob | undefined => {
  const w = obj(at(nav, 'watchEndpoint'));
  if (!w) return undefined;
  const videoId = str(w.videoId);
  const validVideo =
    videoId && /^[A-Za-z0-9_-]{11}$/.test(videoId) ? videoId : undefined;
  const playlistId = id(w.playlistId);
  if (!validVideo && !playlistId) return undefined;
  const vt = str(
    at(
      w,
      'watchEndpointMusicSupportedConfigs',
      'watchEndpointMusicConfig',
      'musicVideoType',
    ),
  );
  return {
    watch: compact({
      videoId: validVideo,
      playlistId,
      params: params(w.params),
      playerParams: params(w.playerParams),
      index:
        Number.isSafeInteger(w.index) && (w.index as number) >= 0
          ? (w.index as number)
          : undefined,
      musicVideoType: vt && VIDEO_TYPE_RE.test(vt) ? vt : undefined,
    }),
  };
};

export const watchPlaylistOf = (
  nav: unknown,
): WatchPlaylistBlob | undefined => {
  const w = obj(at(nav, 'watchPlaylistEndpoint'));
  const playlistId = id(w?.playlistId);
  if (!w || !playlistId) return undefined;
  return { watchPlaylist: compact({ playlistId, params: params(w.params) }) };
};

const playOf = (nav: unknown) => watchOf(nav) ?? watchPlaylistOf(nav);

const queueOf = (target: unknown): QueueBlob | undefined => {
  const t = obj(target);
  const videoId = str(t?.videoId);
  if (videoId && /^[A-Za-z0-9_-]{11}$/.test(videoId)) {
    return { queue: { videoId } };
  }
  const playlistId = id(t?.playlistId);
  return playlistId ? { queue: { playlistId } } : undefined;
};

/** Re-validates a blob handed back by the backend and rebuilds the innertube endpoint. */
export const toNavigationEndpoint = (blob: unknown): J | undefined => {
  const b = obj(blob);
  if (!b) return undefined;
  if (b.browse) {
    const x = obj(b.browse);
    const browseId = id(x?.browseId);
    if (!browseId) return undefined;
    const pageType = str(x?.pageType);
    return {
      browseEndpoint: compact({
        browseId,
        params: params(x?.params),
        browseEndpointContextSupportedConfigs:
          pageType && PAGE_TYPE_RE.test(pageType)
            ? { browseEndpointContextMusicConfig: { pageType } }
            : undefined,
      }),
    };
  }
  if (b.watch) {
    const x = obj(b.watch);
    const videoId = str(x?.videoId);
    const validVideo =
      videoId && /^[A-Za-z0-9_-]{11}$/.test(videoId) ? videoId : undefined;
    const playlistId = id(x?.playlistId);
    if (!validVideo && !playlistId) return undefined;
    const vt = str(x?.musicVideoType);
    return {
      watchEndpoint: compact({
        videoId: validVideo,
        playlistId,
        params: params(x?.params),
        playerParams: params(x?.playerParams),
        index:
          Number.isSafeInteger(x?.index) && (x?.index as number) >= 0
            ? x?.index
            : undefined,
        watchEndpointMusicSupportedConfigs:
          vt && VIDEO_TYPE_RE.test(vt)
            ? { watchEndpointMusicConfig: { musicVideoType: vt } }
            : undefined,
      }),
    };
  }
  if (b.watchPlaylist) {
    const x = obj(b.watchPlaylist);
    const playlistId = id(x?.playlistId);
    if (!playlistId) return undefined;
    return {
      watchPlaylistEndpoint: compact({ playlistId, params: params(x?.params) }),
    };
  }
  return undefined;
};

// ---- item parsing ----

const MAX_TEXT = 500;
const clip = (s: string) => s.trim().slice(0, MAX_TEXT);

/** "3:33" / "1:02:03" -> seconds. */
export const parseDuration = (s: string | undefined): number | null => {
  if (!s || !/^\d{1,3}(:\d{2}){1,2}$/.test(s.trim())) return null;
  return s
    .trim()
    .split(':')
    .reduce((acc, p) => {
      const minutes = acc * 60;
      return minutes + Number(p);
    }, 0);
};

/** Picks the smallest thumbnail at least 160 px on its short side, else the largest. */
export const pickThumbnail = (thumbs: unknown): string | null => {
  const list = arr(thumbs)
    .map((t) => {
      const o = obj(t);
      let url = str(o?.url);
      if (url?.startsWith('//')) url = `https:${url}`;
      const w = typeof o?.width === 'number' ? o.width : 0;
      const h = typeof o?.height === 'number' ? o.height : 0;
      return url?.startsWith('https://') && url.length <= 2048
        ? { url, size: Math.min(w || h, h || w) }
        : null;
    })
    .filter((x): x is { url: string; size: number } => x !== null);
  if (list.length === 0) return null;
  const big = list.filter((t) => t.size >= 160).sort((a, b) => a.size - b.size);
  if (big.length) return big[0].url;
  return list.sort((a, b) => b.size - a.size)[0].url;
};

const thumbsOf = (renderer: unknown, key: string) =>
  at(renderer, key, 'musicThumbnailRenderer', 'thumbnail', 'thumbnails') ??
  at(
    renderer,
    key,
    'croppedSquareThumbnailRenderer',
    'thumbnail',
    'thumbnails',
  );

const hasExplicit = (badges: unknown) =>
  arr(badges).some(
    (b) =>
      at(b, 'musicInlineBadgeRenderer', 'icon', 'iconType') ===
      'MUSIC_EXPLICIT_BADGE',
  );

interface MenuEndpoints {
  shuffle?: WatchBlob | WatchPlaylistBlob;
  queue?: QueueBlob;
  play?: WatchBlob | WatchPlaylistBlob;
}

/** Shuffle / queue endpoints from a `menu.menuRenderer`. */
export const menuEndpoints = (menu: unknown): MenuEndpoints => {
  const out: MenuEndpoints = {};
  const m = at(menu, 'menuRenderer');
  for (const entry of arr(at(m, 'items'))) {
    const nav = obj(at(entry, 'menuNavigationItemRenderer'));
    if (nav) {
      const icon = at(nav, 'icon', 'iconType');
      if (icon === 'MUSIC_SHUFFLE' && !out.shuffle) {
        out.shuffle = playOf(nav.navigationEndpoint);
      }
      continue;
    }
    const svc = obj(at(entry, 'menuServiceItemRenderer'));
    const target = at(
      svc,
      'serviceEndpoint',
      'queueAddEndpoint',
      'queueTarget',
    );
    if (target && !out.queue) out.queue = queueOf(target);
  }
  for (const b of arr(at(m, 'topLevelButtons'))) {
    const br = obj(at(b, 'buttonRenderer'));
    const icon = at(br, 'icon', 'iconType');
    const ep = playOf(br?.navigationEndpoint);
    if (!ep) continue;
    if (icon === 'MUSIC_SHUFFLE') out.shuffle ??= ep;
    else if (icon === 'PLAY_ARROW') out.play ??= ep;
  }
  return out;
};

const kindFromPageType = (
  pageType: string | undefined,
  browseId: string,
  firstSubtitle: string,
): RawItemKind | undefined => {
  switch (pageType) {
    case 'MUSIC_PAGE_TYPE_ALBUM':
    case 'MUSIC_PAGE_TYPE_AUDIOBOOK':
      return firstSubtitle === 'Single' ? 'single' : 'album';
    case 'MUSIC_PAGE_TYPE_PLAYLIST':
    case 'MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE':
      return browseId.startsWith('VLRD') ? 'station' : 'playlist';
    case 'MUSIC_PAGE_TYPE_ARTIST':
    case 'MUSIC_PAGE_TYPE_USER_CHANNEL':
    case 'MUSIC_PAGE_TYPE_LIBRARY_ARTIST':
      return 'artist';
    case 'MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE':
      return 'episode';
    case undefined:
      break;
    default:
      return undefined;
  }
  // No page type: only well-known browse ID prefixes.
  if (browseId.startsWith('MPRE')) return 'album';
  if (browseId.startsWith('VLRD')) return 'station';
  if (browseId.startsWith('VL')) return 'playlist';
  if (browseId.startsWith('UC')) return 'artist';
  return undefined;
};

const kindFromWatch = (w: WatchBlob, wide: boolean): RawItemKind => {
  switch (w.watch.musicVideoType) {
    case 'MUSIC_VIDEO_TYPE_ATV':
      return 'song';
    case 'MUSIC_VIDEO_TYPE_PODCAST_EPISODE':
      return 'episode';
    case 'MUSIC_VIDEO_TYPE_OMV':
    case 'MUSIC_VIDEO_TYPE_UGC':
    case 'MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC':
      return 'video';
    default:
      return wide ? 'video' : 'song';
  }
};

interface ItemParts {
  nav: unknown;
  overlayPlay: unknown;
  menu: unknown;
  title: string;
  subtitle: string;
  firstSubtitle: string;
  thumbs: unknown;
  explicit: boolean;
  wide: boolean;
  videoId?: unknown;
  durationSeconds?: number | null;
}

/** Shared by every item renderer. Returns null when the item can't be acted on. */
const buildItem = (p: ItemParts): RawItem | null => {
  const title = clip(p.title);
  if (!title) return null;
  const browse = browseOf(p.nav);
  const menu = menuEndpoints(p.menu);
  const play = playOf(p.overlayPlay) ?? playOf(p.nav) ?? menu.play;
  let kind: RawItemKind | undefined;
  let open: BrowseBlob | undefined;
  let videoId: string | null = null;

  if (browse) {
    kind = kindFromPageType(
      browse.browse.pageType,
      browse.browse.browseId,
      p.firstSubtitle,
    );
    if (!kind) return null;
    if (kind !== 'episode') open = browse;
  }
  const directWatch = watchOf(p.overlayPlay) ?? watchOf(p.nav);
  const rowVideo = str(p.videoId);
  const vid =
    directWatch?.watch.videoId ??
    (rowVideo && /^[A-Za-z0-9_-]{11}$/.test(rowVideo) ? rowVideo : undefined);

  if (!kind) {
    if (directWatch?.watch.videoId) kind = kindFromWatch(directWatch, p.wide);
    else if (vid) kind = p.wide ? 'video' : 'song';
    else if (play) {
      const pid =
        'watch' in play ? play.watch.playlistId : play.watchPlaylist.playlistId;
      kind = pid?.startsWith('RD') ? 'station' : 'playlist';
    } else return null;
  }

  const single = kind === 'song' || kind === 'video' || kind === 'episode';
  if (single && vid) videoId = vid;
  const playEp: Opaque | undefined =
    (play as unknown as Opaque | undefined) ??
    (single && vid ? { watch: { videoId: vid } } : undefined);
  const queue: Opaque | undefined =
    (menu.queue as unknown as Opaque | undefined) ??
    (single && vid ? { queue: { videoId: vid } } : undefined);

  const item: RawItem = {
    kind,
    title,
    subtitle: clip(p.subtitle),
    durationSeconds: p.durationSeconds ?? null,
    explicit: p.explicit,
    thumbnailUrl: pickThumbnail(p.thumbs),
    videoId,
  };
  if (open) item.open = open as unknown as Opaque;
  if (playEp) item.play = playEp;
  if (menu.shuffle) item.shuffle = menu.shuffle as unknown as Opaque;
  if (queue && kind !== 'artist') item.queue = queue;
  if (!item.open && !item.play && !item.shuffle && !item.queue) return null;
  return item;
};

const firstRunText = (t: unknown) => str(at(t, 'runs', 0, 'text')) ?? '';

export const parseTwoRowItem = (r: unknown): RawItem | null =>
  buildItem({
    nav: at(r, 'navigationEndpoint'),
    overlayPlay: at(
      r,
      'thumbnailOverlay',
      'musicItemThumbnailOverlayRenderer',
      'content',
      'musicPlayButtonRenderer',
      'playNavigationEndpoint',
    ),
    menu: at(r, 'menu'),
    title: textOf(at(r, 'title')),
    subtitle: textOf(at(r, 'subtitle')),
    firstSubtitle: firstRunText(at(r, 'subtitle')),
    thumbs: thumbsOf(r, 'thumbnailRenderer'),
    explicit: hasExplicit(at(r, 'subtitleBadges')),
    wide:
      at(r, 'aspectRatio') ===
      'MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9',
  });

export const parseListItem = (r: unknown): RawItem | null => {
  if (
    at(r, 'musicItemRendererDisplayPolicy') ===
    'MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT'
  ) {
    return null; // unavailable track
  }
  const flex = arr(at(r, 'flexColumns')).map((c) =>
    at(c, 'musicResponsiveListItemFlexColumnRenderer', 'text'),
  );
  const fixed = arr(at(r, 'fixedColumns')).map((c) =>
    textOf(at(c, 'musicResponsiveListItemFixedColumnRenderer', 'text')),
  );
  const rest = flex.slice(1).map(textOf).filter(Boolean);
  let duration: number | null = null;
  for (const f of fixed) duration ??= parseDuration(f);
  if (duration === null) {
    const runs = arr(at(flex[1], 'runs'));
    duration = parseDuration(
      (str(at(runs.at(-1), 'text')) ?? '').split('•').at(-1),
    );
  }
  return buildItem({
    nav:
      at(r, 'navigationEndpoint') ??
      at(flex[0], 'runs', 0, 'navigationEndpoint'),
    overlayPlay: at(
      r,
      'overlay',
      'musicItemThumbnailOverlayRenderer',
      'content',
      'musicPlayButtonRenderer',
      'playNavigationEndpoint',
    ),
    menu: at(r, 'menu'),
    title: textOf(flex[0]),
    subtitle: rest.join(' • '),
    firstSubtitle: firstRunText(flex[1]),
    thumbs: thumbsOf(r, 'thumbnail'),
    explicit: hasExplicit(at(r, 'badges')),
    wide: false,
    videoId: at(r, 'playlistItemData', 'videoId'),
    durationSeconds: duration,
  });
};

const parseMultiRowItem = (r: unknown): RawItem | null =>
  buildItem({
    nav: at(r, 'onTap'),
    overlayPlay: at(
      r,
      'overlay',
      'musicItemThumbnailOverlayRenderer',
      'content',
      'musicPlayButtonRenderer',
      'playNavigationEndpoint',
    ),
    menu: at(r, 'menu'),
    title: textOf(at(r, 'title')),
    subtitle: textOf(at(r, 'subtitle')),
    firstSubtitle: firstRunText(at(r, 'subtitle')),
    thumbs: thumbsOf(r, 'thumbnail'),
    explicit: false,
    wide: false,
  });

/** Any item renderer, or null (unknown renderers, ads, "New playlist" tiles). */
export const parseItem = (content: unknown): RawItem | null => {
  const c = obj(content);
  if (!c) return null;
  if (c.musicTwoRowItemRenderer)
    return parseTwoRowItem(c.musicTwoRowItemRenderer);
  if (c.musicResponsiveListItemRenderer) {
    return parseListItem(c.musicResponsiveListItemRenderer);
  }
  if (c.musicMultiRowListItemRenderer) {
    return parseMultiRowItem(c.musicMultiRowListItemRenderer);
  }
  return null;
};

const parseItems = (contents: unknown) =>
  arr(contents)
    .map(parseItem)
    .filter((x): x is RawItem => x !== null);

/** Continuation token of a shelf: old `continuations` or a trailing continuationItemRenderer. */
const continuationOf = (
  shelf: unknown,
  contents: unknown,
): string | undefined =>
  token(
    at(shelf, 'continuations', 0, 'nextContinuationData', 'continuation'),
  ) ??
  token(
    at(
      arr(contents).at(-1),
      'continuationItemRenderer',
      'continuationEndpoint',
      'continuationCommand',
      'token',
    ),
  );

// ---- shelves ----

export interface ParsedShelf {
  section: RawSection;
  /** True for track/tile lists that can be continued item by item. */
  list: boolean;
  continuation?: string;
}

const parseCardShelf = (c: J): ParsedShelf | null => {
  const main = buildItem({
    nav: c.onTap ?? at(c, 'title', 'runs', 0, 'navigationEndpoint'),
    overlayPlay: at(
      c,
      'thumbnailOverlay',
      'musicItemThumbnailOverlayRenderer',
      'content',
      'musicPlayButtonRenderer',
      'playNavigationEndpoint',
    ),
    menu: c.menu,
    title: textOf(c.title),
    subtitle: textOf(c.subtitle),
    firstSubtitle: firstRunText(c.subtitle),
    thumbs: thumbsOf(c, 'thumbnail'),
    explicit: hasExplicit(c.subtitleBadges),
    wide: false,
  });
  const items = [...(main ? [main] : []), ...parseItems(c.contents)];
  if (!items.length) return null;
  const title = clip(
    textOf(at(c, 'header', 'musicCardShelfHeaderBasicRenderer', 'title')),
  );
  return {
    section: { title: title || null, layout: 'list', items },
    list: false,
  };
};

/** One entry of a sectionListRenderer. Returns [] for anything not representable. */
export const parseShelf = (content: unknown): ParsedShelf[] => {
  const c = obj(content);
  if (!c) return [];
  const carousel = obj(
    c.musicCarouselShelfRenderer ?? c.musicImmersiveCarouselShelfRenderer,
  );
  if (carousel) {
    const header = at(
      carousel,
      'header',
      'musicCarouselShelfBasicHeaderRenderer',
    );
    const items = parseItems(carousel.contents);
    if (!items.length) return [];
    const title = clip(textOf(at(header, 'title')));
    // Personal tile shelves (Listen again) carry the user's avatar in the header.
    const tiles =
      at(header, 'thumbnail') !== undefined &&
      arr(carousel.contents).every((x) => obj(x)?.musicTwoRowItemRenderer);
    return [
      {
        section: {
          title: title || null,
          layout: tiles ? 'grid' : 'carousel',
          items,
        },
        list: false,
      },
    ];
  }
  const shelf = obj(c.musicShelfRenderer ?? c.musicPlaylistShelfRenderer);
  if (shelf) {
    const items = parseItems(shelf.contents);
    const cont = continuationOf(shelf, shelf.contents);
    if (!items.length && !cont) return [];
    const title = clip(textOf(shelf.title));
    return [
      {
        section: { title: title || null, layout: 'list', items },
        list: true,
        continuation: cont,
      },
    ];
  }
  const grid = obj(c.gridRenderer);
  if (grid) {
    const items = parseItems(grid.items);
    const cont = continuationOf(grid, grid.items);
    if (!items.length && !cont) return [];
    const title = clip(
      textOf(at(grid, 'header', 'gridHeaderRenderer', 'title')),
    );
    return [
      {
        section: { title: title || null, layout: 'grid', items },
        list: true,
        continuation: cont,
      },
    ];
  }
  if (c.musicCardShelfRenderer) {
    const card = parseCardShelf(obj(c.musicCardShelfRenderer)!);
    return card ? [card] : [];
  }
  const itemSection = obj(c.itemSectionRenderer);
  if (itemSection) return arr(itemSection.contents).flatMap(parseShelf);
  return [];
};

// ---- headers ----

const joinText = (...parts: unknown[]) =>
  parts
    .map((p) => textOf(p).trim())
    .filter(Boolean)
    .join(' • ');

const parseResponsiveHeader = (
  h: J,
  kind: 'album' | 'playlist',
): RawHeader | null => {
  const title = clip(textOf(h.title));
  if (!title) return null;
  const header: RawHeader = {
    kind,
    title,
    subtitle: clip(joinText(h.straplineTextOne, h.subtitle, h.secondSubtitle)),
    thumbnailUrl: pickThumbnail(thumbsOf(h, 'thumbnail')),
  };
  for (const b of arr(h.buttons)) {
    const play = playOf(
      at(b, 'musicPlayButtonRenderer', 'playNavigationEndpoint'),
    );
    if (play && !header.play) header.play = play as unknown as Opaque;
    const menu = obj(at(b, 'menuRenderer'));
    if (menu) {
      const m = menuEndpoints({ menuRenderer: menu });
      if (m.shuffle && !header.shuffle) {
        header.shuffle = m.shuffle as unknown as Opaque;
      }
    }
  }
  return header;
};

const parseDetailHeader = (
  h: J,
  kind: 'album' | 'playlist',
): RawHeader | null => {
  const title = clip(textOf(h.title));
  if (!title) return null;
  const m = menuEndpoints(h.menu);
  const header: RawHeader = {
    kind,
    title,
    subtitle: clip(joinText(h.subtitle, h.secondSubtitle)),
    thumbnailUrl: pickThumbnail(thumbsOf(h, 'thumbnail')),
  };
  if (m.play) header.play = m.play as unknown as Opaque;
  if (m.shuffle) header.shuffle = m.shuffle as unknown as Opaque;
  return header;
};

const parseArtistHeader = (h: J): RawHeader | null => {
  const title = clip(textOf(h.title));
  if (!title) return null;
  const header: RawHeader = {
    kind: 'artist',
    title,
    subtitle: clip(joinText(h.monthlyListenerCount ?? h.subscriptionButton)),
    thumbnailUrl: pickThumbnail(
      thumbsOf(h, 'thumbnail') ?? thumbsOf(h, 'foregroundThumbnail'),
    ),
  };
  const button = obj(at(h, 'playButton', 'buttonRenderer'));
  const ep = playOf(button?.navigationEndpoint);
  if (ep) {
    // Artist pages: the main button is "Shuffle" (it plays the artist's songs shuffled).
    if (at(button, 'icon', 'iconType') === 'PLAY_ARROW') {
      header.play = ep as unknown as Opaque;
    } else header.shuffle = ep as unknown as Opaque;
  }
  if (!header.subtitle) header.subtitle = '';
  return header;
};

const listKind = (
  pageType: string | undefined,
  browseId: string | undefined,
): 'album' | 'playlist' =>
  pageType === 'MUSIC_PAGE_TYPE_ALBUM' ||
  pageType === 'MUSIC_PAGE_TYPE_AUDIOBOOK' ||
  browseId?.startsWith('MPRE')
    ? 'album'
    : 'playlist';

/** Finds the page header in any of the shapes YT Music uses. */
export const parseHeader = (
  resp: unknown,
  pageType?: string,
  browseId?: string,
): { header: RawHeader | null; title: string | null } => {
  const kind = listKind(pageType, browseId);
  const tabContents = arr(
    at(
      resp,
      'contents',
      'twoColumnBrowseResultsRenderer',
      'tabs',
      0,
      'tabRenderer',
      'content',
      'sectionListRenderer',
      'contents',
    ),
  );
  for (const c of tabContents) {
    const r =
      obj(at(c, 'musicResponsiveHeaderRenderer')) ??
      obj(
        at(
          c,
          'musicEditablePlaylistDetailHeaderRenderer',
          'header',
          'musicResponsiveHeaderRenderer',
        ),
      );
    if (r) {
      const header = parseResponsiveHeader(r, kind);
      return { header, title: header?.title ?? null };
    }
  }
  const h = obj(at(resp, 'header'));
  if (!h) return { header: null, title: null };
  const immersive = obj(
    h.musicImmersiveHeaderRenderer ?? h.musicVisualHeaderRenderer,
  );
  if (immersive) {
    const header = parseArtistHeader(immersive);
    return { header, title: header?.title ?? null };
  }
  const detail =
    obj(h.musicDetailHeaderRenderer) ??
    obj(
      at(
        h,
        'musicEditablePlaylistDetailHeaderRenderer',
        'header',
        'musicDetailHeaderRenderer',
      ),
    );
  if (detail) {
    const header = parseDetailHeader(detail, kind);
    return { header, title: header?.title ?? null };
  }
  const plain = clip(textOf(at(h, 'musicHeaderRenderer', 'title')));
  return { header: null, title: plain || null };
};

// ---- pages ----

/** Every sectionListRenderer of a browse/search response, in page order. */
const sectionLists = (resp: unknown): J[] => {
  const out: J[] = [];
  const push = (v: unknown) => {
    const o = obj(v);
    if (o) out.push(o);
  };
  push(
    at(
      resp,
      'contents',
      'singleColumnBrowseResultsRenderer',
      'tabs',
      0,
      'tabRenderer',
      'content',
      'sectionListRenderer',
    ),
  );
  push(
    at(
      resp,
      'contents',
      'twoColumnBrowseResultsRenderer',
      'tabs',
      0,
      'tabRenderer',
      'content',
      'sectionListRenderer',
    ),
  );
  push(
    at(
      resp,
      'contents',
      'twoColumnBrowseResultsRenderer',
      'secondaryContents',
      'sectionListRenderer',
    ),
  );
  push(
    at(
      resp,
      'contents',
      'tabbedSearchResultsRenderer',
      'tabs',
      0,
      'tabRenderer',
      'content',
      'sectionListRenderer',
    ),
  );
  return out;
};

const parseChips = (resp: unknown): RawPage['chips'] => {
  const chips: RawPage['chips'] = [];
  for (const sl of sectionLists(resp)) {
    for (const chip of arr(at(sl, 'header', 'chipCloudRenderer', 'chips'))) {
      const c = obj(at(chip, 'chipCloudChipRenderer'));
      const title = clip(textOf(c?.text));
      const open = browseOf(c?.navigationEndpoint);
      if (title && open && c?.isSelected !== true) {
        chips.push({ title, open: open as unknown as Opaque });
      }
    }
  }
  return chips;
};

const contBlob = (
  tok: string | undefined,
  base: Omit<ContinuationBlob['continuation'], 'token'>,
): Opaque | null => (tok ? { continuation: { token: tok, ...base } } : null);

interface PageContext {
  path: 'browse' | 'search';
  query?: string;
  params?: string;
  /** 'items' pages keep only their main list; 'sections' keep every shelf. */
  mode: 'sections' | 'items';
  title: string | null;
  subtitle?: string | null;
  pageType?: string;
  browseId?: string;
  withHeader?: boolean;
  withChips?: boolean;
}

/** A first page (browse, open or search response). */
export const parsePage = (resp: unknown, ctx: PageContext): RawPage => {
  const head = ctx.withHeader
    ? parseHeader(resp, ctx.pageType, ctx.browseId)
    : { header: null, title: null };
  const lists = sectionLists(resp);
  const shelves = lists.flatMap((sl) => arr(sl.contents).flatMap(parseShelf));
  let mode = ctx.mode;
  if (head.header?.kind === 'album' || head.header?.kind === 'playlist') {
    mode = 'items';
  } else if (head.header?.kind === 'artist') mode = 'sections';

  let sections: RawSection[];
  let continuation: Opaque | null;
  if (mode === 'items') {
    const main = shelves.find((s) => s.list) ?? shelves[0];
    sections = main && main.section.items.length ? [main.section] : [];
    continuation = contBlob(main?.continuation, {
      path: ctx.path,
      query: ctx.query,
      params: ctx.params,
      layout: main?.section.layout ?? 'list',
      title: main?.section.title ?? null,
    });
  } else {
    sections = shelves.map((s) => s.section).filter((s) => s.items.length > 0);
    const tok = lists
      .map((sl) => continuationOf(sl, sl.contents))
      .find(Boolean);
    continuation = contBlob(tok, {
      path: ctx.path,
      query: ctx.query,
      params: ctx.params,
      layout: 'carousel',
      title: null,
    });
  }
  return {
    title: head.title ?? ctx.title,
    subtitle: ctx.subtitle ?? null,
    header: head.header,
    chips: ctx.withChips ? parseChips(resp) : [],
    sections,
    continuation,
    mode,
  };
};

/** A continuation response, in either the old or the new innertube shape. */
export const parseContinuation = (
  resp: unknown,
  blob: ContinuationBlob['continuation'],
): RawPage | null => {
  const base = {
    path: blob.path,
    query: blob.query,
    params: blob.params,
    layout: blob.layout,
    title: blob.title,
  };
  const page = (sections: RawSection[], tok: string | undefined): RawPage => ({
    title: null,
    subtitle: null,
    header: null,
    chips: [],
    sections,
    continuation: contBlob(tok, base),
    mode: 'sections',
  });
  const cc = obj(at(resp, 'continuationContents'));
  if (cc) {
    const sl = obj(cc.sectionListContinuation);
    if (sl) {
      const sections = arr(sl.contents)
        .flatMap(parseShelf)
        .map((s) => s.section)
        .filter((s) => s.items.length);
      return page(sections, continuationOf(sl, []));
    }
    const shelf = obj(
      cc.musicShelfContinuation ??
        cc.musicPlaylistShelfContinuation ??
        cc.gridContinuation,
    );
    if (shelf) {
      const contents = shelf.contents ?? shelf.items;
      const items = parseItems(contents);
      return page(
        items.length ? [{ title: blob.title, layout: blob.layout, items }] : [],
        continuationOf(shelf, contents),
      );
    }
    return null;
  }
  const actions = arr(at(resp, 'onResponseReceivedActions')).concat(
    arr(at(resp, 'onResponseReceivedCommands')),
  );
  for (const a of actions) {
    const list = at(a, 'appendContinuationItemsAction', 'continuationItems');
    if (!Array.isArray(list)) continue;
    const items = parseItems(list);
    const sections = list
      .flatMap(parseShelf)
      .map((s) => s.section)
      .filter((s) => s.items.length);
    if (items.length) {
      sections.unshift({ title: blob.title, layout: blob.layout, items });
    }
    return page(sections, continuationOf({}, list));
  }
  return null;
};

export const parseSuggestions = (resp: unknown): string[] => {
  const out: string[] = [];
  for (const section of arr(at(resp, 'contents'))) {
    for (const c of arr(
      at(section, 'searchSuggestionsSectionRenderer', 'contents'),
    )) {
      const s =
        at(c, 'searchSuggestionRenderer', 'suggestion') ??
        at(c, 'historySuggestionRenderer', 'suggestion');
      const text = textOf(s).trim();
      if (text && !out.includes(text)) out.push(text);
    }
  }
  return out;
};

// ---- running requests ----

/** What the page gives us. createPageEnv implements it on the real page. */
export interface PageEnv {
  fetch(path: string, body: Record<string, unknown>): Promise<unknown>;
  /** Replays a navigation endpoint as if the user clicked it. */
  navigate(endpoint: Record<string, unknown>): boolean;
  /** Inserts the get_queue result; false if there is no queue to insert into. */
  insertIntoQueue(
    target: { videoId: string } | { playlistId: string },
    position: 'INSERT_AFTER_CURRENT_VIDEO' | 'INSERT_AT_END',
  ): Promise<'ok' | 'empty' | 'failed'>;
  /** true / false when known, null when the page doesn't say. */
  loggedIn(): boolean | null;
}

const fail = (code: CatalogFailure): CatalogReply => ({ ok: false, code });

const readContinuationBlob = (
  b: unknown,
): ContinuationBlob['continuation'] | undefined => {
  const c = obj(at(b, 'continuation'));
  const tok = token(c?.token);
  const path: 'browse' | 'search' | undefined =
    c?.path === 'browse' || c?.path === 'search' ? c.path : undefined;
  const layout: RawSection['layout'] | undefined =
    c?.layout === 'carousel' || c?.layout === 'list' || c?.layout === 'grid'
      ? c.layout
      : undefined;
  if (!c || !tok || !path || !layout) return undefined;
  const title = typeof c.title === 'string' ? clip(c.title) : null;
  const query =
    typeof c.query === 'string' && c.query.length <= 150 ? c.query : undefined;
  return compact({
    token: tok,
    path,
    layout,
    title,
    query,
    params: params(c.params),
  });
};

export const runCatalogRequest = async (
  req: CatalogRequest,
  env: PageEnv,
): Promise<CatalogReply> => {
  try {
    switch (req.op) {
      case 'browse': {
        const library = req.target !== 'home';
        if (library && env.loggedIn() === false) return fail('signed_out');
        const browseId = BROWSE_IDS[req.target];
        const resp = await env.fetch(PATHS.browse, { browseId });
        const page = parsePage(resp, {
          path: 'browse',
          mode: library ? 'items' : 'sections',
          title: TITLES[req.target],
          withChips: !library,
          withHeader: false,
        });
        page.title = TITLES[req.target];
        if (!sectionLists(resp).length) {
          return fail(env.loggedIn() === false ? 'signed_out' : 'unavailable');
        }
        return { ok: true, page };
      }
      case 'open': {
        const nav = toNavigationEndpoint(req.endpoint);
        const b = obj(nav?.browseEndpoint);
        if (!b) return fail('not_found');
        const pageType = str(
          at(
            b,
            'browseEndpointContextSupportedConfigs',
            'browseEndpointContextMusicConfig',
            'pageType',
          ),
        );
        const resp = await env.fetch(
          PATHS.browse,
          compact({ browseId: b.browseId, params: b.params }),
        );
        if (!sectionLists(resp).length) return fail('unavailable');
        const page = parsePage(resp, {
          path: 'browse',
          mode:
            pageType === 'MUSIC_PAGE_TYPE_LIBRARY_ARTIST'
              ? 'items'
              : 'sections',
          title: null,
          pageType,
          browseId: str(b.browseId),
          withHeader: true,
          withChips: false,
        });
        return { ok: true, page };
      }
      case 'search': {
        const p = SEARCH_PARAMS[req.filter];
        const resp = await env.fetch(
          PATHS.search,
          compact({ query: req.query, params: p }),
        );
        if (!sectionLists(resp).length) return fail('unavailable');
        const page = parsePage(resp, {
          path: 'search',
          query: req.query,
          params: p,
          mode: req.filter === 'top' ? 'sections' : 'items',
          title: req.query,
          withHeader: false,
          withChips: false,
        });
        return { ok: true, page };
      }
      case 'continue': {
        const blob = readContinuationBlob(req.continuation);
        if (!blob) return fail('not_found');
        const body: Record<string, unknown> =
          blob.path === 'search'
            ? compact({
                query: blob.query,
                params: blob.params,
                continuation: blob.token,
              })
            : { continuation: blob.token };
        const path = blob.path === 'search' ? PATHS.search : PATHS.browse;
        let page = parseContinuation(await env.fetch(path, body), blob);
        if (!page) {
          // Older tokens are only accepted as URL parameters.
          const q = `?ctoken=${encodeURIComponent(blob.token)}&continuation=${encodeURIComponent(blob.token)}&type=next`;
          const retryBody =
            blob.path === 'search'
              ? compact({ query: blob.query, params: blob.params })
              : {};
          page = parseContinuation(await env.fetch(path + q, retryBody), blob);
        }
        return page ? { ok: true, page } : fail('unavailable');
      }
      case 'suggest': {
        const resp = await env.fetch(PATHS.suggestions, { input: req.query });
        return { ok: true, suggestions: parseSuggestions(resp) };
      }
      case 'play': {
        if (req.action === 'play' || req.action === 'shuffle') {
          const nav = toNavigationEndpoint(req.endpoint);
          if (!nav || nav.browseEndpoint) return fail('not_found');
          return env.navigate(nav)
            ? { ok: true, done: true }
            : fail('not_ready');
        }
        const q = obj(at(req.endpoint, 'queue'));
        const target = queueOf(q)?.queue;
        if (!target) return fail('not_found');
        const outcome = await env.insertIntoQueue(
          target,
          req.action === 'playNext'
            ? 'INSERT_AFTER_CURRENT_VIDEO'
            : 'INSERT_AT_END',
        );
        if (outcome === 'empty') {
          // Nothing playing: YT Music has no queue to insert into, so play it.
          const nav =
            'videoId' in target
              ? { watchEndpoint: { videoId: target.videoId } }
              : { watchPlaylistEndpoint: { playlistId: target.playlistId } };
          return env.navigate(nav)
            ? { ok: true, done: true }
            : fail('not_ready');
        }
        return outcome === 'ok'
          ? { ok: true, done: true }
          : fail('unavailable');
      }
    }
  } catch {
    // Never log the error: it may carry response data.
    return fail(
      env.loggedIn() === false && req.op === 'browse'
        ? 'signed_out'
        : 'unavailable',
    );
  }
  return fail('unavailable');
};

// ---- the real page ----

interface AppElement extends HTMLElement {
  networkManager?: {
    fetch: (path: string, body: unknown) => Promise<unknown>;
  };
}

interface QueueLike extends HTMLElement {
  dispatch(action: { type: string; payload?: unknown }): void;
  queue?: {
    store?: {
      store?: {
        getState(): {
          queue: {
            items: unknown[];
            queueContextParams?: string;
            nextQueueItemId?: number;
          };
        };
      };
    };
  };
}

const selectedIndex = (items: unknown[]) =>
  items.findIndex(
    (it) =>
      at(it, 'playlistPanelVideoRenderer', 'selected') === true ||
      at(
        it,
        'playlistPanelVideoWrapperRenderer',
        'primaryRenderer',
        'playlistPanelVideoRenderer',
        'selected',
      ) === true,
  );

export const createPageEnv = (): PageEnv => {
  const app = () => document.querySelector<AppElement>('ytmusic-app');
  return {
    async fetch(path, body) {
      const nm = app()?.networkManager;
      if (!nm) throw new Error('no client');
      return nm.fetch(path, body);
    },
    navigate(endpoint) {
      const a = app();
      if (!a) return false;
      // The same event YT Music's own links fire, so it builds the queue /
      // radio exactly as for a click.
      a.dispatchEvent(
        new CustomEvent('yt-navigate', {
          detail: { endpoint },
          bubbles: true,
          composed: true,
        }),
      );
      return true;
    },
    async insertIntoQueue(target, position) {
      const queue = document.querySelector<QueueLike>('#queue');
      const store = queue?.queue?.store?.store;
      const nm = app()?.networkManager;
      if (!queue || !store || !nm) return 'empty';
      const state = store.getState().queue;
      if (!state.items.length) return 'empty';
      const result = await nm.fetch(PATHS.queue, {
        queueContextParams: state.queueContextParams,
        queueInsertPosition: position,
        ...('videoId' in target
          ? { videoIds: [target.videoId] }
          : { playlistId: target.playlistId }),
      });
      const datas = arr(at(result, 'queueDatas'))
        .map((d) => at(d, 'content'))
        .filter((x) => x !== undefined);
      if (!datas.length) return 'failed';
      const now = store.getState().queue;
      const length = now.items.length;
      const index =
        position === 'INSERT_AFTER_CURRENT_VIDEO'
          ? selectedIndex(now.items) + 1 || length
          : length;
      queue.dispatch({
        type: 'ADD_ITEMS',
        payload: {
          nextQueueItemId: now.nextQueueItemId,
          index,
          items: datas,
          shuffleEnabled: false,
          shouldAssignIds: true,
        },
      });
      return 'ok';
    },
    loggedIn() {
      const cfg = (
        window as unknown as { ytcfg?: { get?: (k: string) => unknown } }
      ).ytcfg;
      const v = cfg?.get?.('LOGGED_IN');
      return typeof v === 'boolean' ? v : null;
    },
  };
};
