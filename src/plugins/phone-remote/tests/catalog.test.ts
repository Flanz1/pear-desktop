/**
 * Catalog (1.1): page-bridge parsing on synthetic innertube-shaped responses,
 * the ref store, the catalog service (trimming, append semantics, actions),
 * and the thumbnail fetcher. No real YouTube data, no network.
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  albumResponse,
  artistResponse,
  homeContinuation,
  homeResponse,
  libraryPlaylistsResponse,
  searchSongsContinuation,
  searchSongsResponse,
  searchTopResponse,
  suggestionsResponse,
} from './catalog-fixtures.ts';

import {
  CatalogPageSchema,
  LIMITS,
  type CatalogPage,
} from '../../../../protocol/src/index.ts';
import {
  CatalogService,
  RefStore,
  trimToFit,
  type CatalogAnswer,
} from '../backend/catalog.ts';
import { ThumbnailFetcher, isAllowedThumbUrl } from '../backend/thumbnails.ts';
import {
  pickThumbnail,
  runCatalogRequest,
  toNavigationEndpoint,
  type PageEnv,
} from '../catalog-page.ts';

import type { CatalogRequest, RawItem, RawPage } from '../catalog-types.ts';

// ---- page bridge ----

const fakeEnv = (
  responses: Record<string, unknown>,
  opts: { loggedIn?: boolean | null; queue?: 'ok' | 'empty' | 'failed' } = {},
) => {
  const calls: { path: string; body: Record<string, unknown> }[] = [];
  const navigations: Record<string, unknown>[] = [];
  const inserts: unknown[] = [];
  const env: PageEnv = {
    fetch(path, body) {
      calls.push({ path, body });
      const key =
        (body.browseId as string | undefined) ??
        (body.continuation as string | undefined) ??
        (body.params as string | undefined) ??
        path;
      return Promise.resolve(responses[key] ?? responses[path] ?? {});
    },
    navigate(endpoint) {
      navigations.push(endpoint);
      return true;
    },
    insertIntoQueue(target, position) {
      inserts.push({ target, position });
      return Promise.resolve(opts.queue ?? 'ok');
    },
    loggedIn: () => (opts.loggedIn === undefined ? true : opts.loggedIn),
  };
  return { env, calls, navigations, inserts };
};

const pageOf = async (req: CatalogRequest, env: PageEnv): Promise<RawPage> => {
  const reply = await runCatalogRequest(req, env);
  assert.ok(reply.ok && 'page' in reply, JSON.stringify(reply));
  return reply.page;
};

describe('catalog page bridge: parsing', () => {
  it('keeps every home shelf in order with its title, plus mood chips', async () => {
    const { env, calls } = fakeEnv({ FEmusic_home: homeResponse });
    const page = await pageOf({ op: 'browse', target: 'home' }, env);
    assert.deepEqual(calls[0], {
      path: '/browse',
      body: { browseId: 'FEmusic_home' },
    });
    assert.equal(page.mode, 'sections');
    assert.deepEqual(
      page.sections.map((s) => [s.title, s.layout, s.items.length]),
      [
        ['Listen again', 'grid', 3],
        ['Quick picks', 'carousel', 2],
        ['Mixed for you', 'carousel', 2],
        ['Recommended music videos', 'carousel', 1],
      ],
    );
    assert.deepEqual(
      page.chips.map((c) => c.title),
      ['Relax', 'Energize'],
    );
    assert.ok(page.continuation);

    const [album, song, artist] = page.sections[0].items;
    assert.equal(album.kind, 'album');
    assert.equal(album.explicit, true);
    assert.ok(album.open && album.play && album.shuffle && album.queue);
    assert.equal(album.videoId, null);
    assert.equal(
      album.thumbnailUrl,
      'https://lh3.googleusercontent.com/SyntheticAlbum=w226-h226',
    );
    assert.equal(song.kind, 'song');
    assert.equal(song.videoId, 'AAAAAAAAAA1');
    assert.ok(song.play && song.queue && !song.open);
    assert.equal(artist.kind, 'artist');
    assert.ok(artist.open && !artist.play && !artist.queue);

    const [pick, video] = page.sections[1].items;
    assert.equal(pick.kind, 'song');
    assert.equal(pick.subtitle, 'Test Artist • Some Album');
    assert.equal(video.kind, 'video');

    const [mix, radio] = page.sections[2].items;
    assert.equal(mix.kind, 'station');
    assert.ok(mix.open && mix.play);
    assert.equal(radio.kind, 'station');
    assert.ok(radio.play && !radio.open);
    assert.equal(page.sections[3].items[0].kind, 'video');
  });

  it('parses an album page: header actions, durations, greyed-out tracks skipped', async () => {
    const { env } = fakeEnv({ MPREb_synthetic01: albumResponse });
    const open = homeOpen();
    const page = await pageOf({ op: 'open', endpoint: open }, env);
    assert.equal(page.mode, 'items');
    assert.equal(page.header?.kind, 'album');
    assert.equal(page.header?.title, 'Synthetic Album');
    assert.equal(
      page.header?.subtitle,
      'Test Artist • Album • 2024 • 3 songs • 10 minutes',
    );
    assert.ok(page.header?.play && page.header.shuffle);
    // List pages keep only their track list (the list is the last section).
    assert.equal(page.sections.length, 1);
    const tracks = page.sections[0].items;
    assert.deepEqual(
      tracks.map((t: RawItem) => [t.title, t.durationSeconds, t.explicit]),
      [
        ['Track One', 213, true],
        ['Track Three', 3723, false],
      ],
    );
    assert.deepEqual(tracks[0].play, {
      watch: {
        videoId: 'AAAAAAAAAB1',
        playlistId: 'OLAK5uy_synthetic01',
        index: 0,
        musicVideoType: 'MUSIC_VIDEO_TYPE_ATV',
      },
    });
  });

  it('parses an artist page as a feed with a shuffle header', async () => {
    const { env } = fakeEnv({ UCsynthetic000000000001: artistResponse });
    const page = await pageOf(
      {
        op: 'open',
        endpoint: {
          browse: {
            browseId: 'UCsynthetic000000000001',
            pageType: 'MUSIC_PAGE_TYPE_ARTIST',
          },
        },
      },
      env,
    );
    assert.equal(page.mode, 'sections');
    assert.equal(page.header?.kind, 'artist');
    assert.equal(page.header?.subtitle, '1.2M monthly audience');
    assert.ok(page.header?.shuffle && !page.header.play);
    assert.deepEqual(
      page.sections.map((s) => s.title),
      ['Top songs', 'Albums'],
    );
  });

  it('parses top search (card shelf) and filtered search with continuations', async () => {
    const { env, calls } = fakeEnv({
      '/search': searchTopResponse,
      'EgWKAQIIAWoMEA4QChADEAQQCRAF': searchSongsResponse,
      'SEARCHCONT1': searchSongsContinuation,
    });
    const top = await pageOf(
      { op: 'search', query: 'test', filter: 'top' },
      env,
    );
    assert.equal(top.mode, 'sections');
    assert.deepEqual(
      top.sections.map((s) => [s.title, s.items.map((i) => i.kind)]),
      [
        ['Top result', ['artist', 'song']],
        ['Songs', ['song']],
      ],
    );
    assert.equal(top.sections[1].items[0].durationSeconds, 245);

    const songs = await pageOf(
      { op: 'search', query: 'test', filter: 'songs' },
      env,
    );
    assert.equal(songs.mode, 'items');
    assert.deepEqual(calls.at(-1), {
      path: '/search',
      body: { query: 'test', params: 'EgWKAQIIAWoMEA4QChADEAQQCRAF' },
    });
    assert.ok(songs.continuation);
    const more = await pageOf(
      { op: 'continue', continuation: songs.continuation },
      env,
    );
    assert.deepEqual(calls.at(-1)?.body, {
      query: 'test',
      params: 'EgWKAQIIAWoMEA4QChADEAQQCRAF',
      continuation: 'SEARCHCONT1',
    });
    assert.deepEqual(
      more.sections.flatMap((s) => s.items.map((i) => i.title)),
      ['S2'],
    );
    assert.equal(
      (more.continuation as { continuation: { token: string } }).continuation
        .token,
      'SEARCHCONT2',
    );
  });

  it('parses old-style home continuations', async () => {
    const { env } = fakeEnv({
      FEmusic_home: homeResponse,
      HOMECONT1: homeContinuation,
    });
    const first = await pageOf({ op: 'browse', target: 'home' }, env);
    const next = await pageOf(
      { op: 'continue', continuation: first.continuation! },
      env,
    );
    assert.deepEqual(
      next.sections.map((s) => s.title),
      ['Forgotten favourites'],
    );
    assert.equal(next.continuation, null);
  });

  it('library: skips tiles it cannot act on; signed out gives signed_out', async () => {
    const { env } = fakeEnv({
      FEmusic_liked_playlists: libraryPlaylistsResponse,
    });
    const page = await pageOf(
      { op: 'browse', target: 'library.playlists' },
      env,
    );
    assert.equal(page.mode, 'items');
    assert.deepEqual(
      page.sections[0].items.map((i) => i.title),
      ['Liked music'],
    );
    assert.equal(page.sections[0].layout, 'grid');

    const out = fakeEnv({}, { loggedIn: false });
    assert.deepEqual(
      await runCatalogRequest(
        { op: 'browse', target: 'library.liked' },
        out.env,
      ),
      { ok: false, code: 'signed_out' },
    );
    assert.equal(out.calls.length, 0);
  });

  it('suggestions: text only, deduplicated', async () => {
    const { env, calls } = fakeEnv({
      '/music/get_search_suggestions': suggestionsResponse,
    });
    assert.deepEqual(
      await runCatalogRequest({ op: 'suggest', query: 'te' }, env),
      {
        ok: true,
        suggestions: ['test song', 'test artist'],
      },
    );
    assert.deepEqual(calls[0].body, { input: 'te' });
  });

  it('play replays the endpoint; queue actions insert, or play when nothing is queued', async () => {
    const a = fakeEnv({});
    await runCatalogRequest(
      {
        op: 'play',
        action: 'shuffle',
        endpoint: {
          watchPlaylist: { playlistId: 'OLAK5uy_x', params: 'wAEB' },
        },
      },
      a.env,
    );
    assert.deepEqual(a.navigations, [
      { watchPlaylistEndpoint: { playlistId: 'OLAK5uy_x', params: 'wAEB' } },
    ]);
    await runCatalogRequest(
      {
        op: 'play',
        action: 'playNext',
        endpoint: { queue: { videoId: 'AAAAAAAAAA1' } },
      },
      a.env,
    );
    assert.deepEqual(a.inserts, [
      {
        target: { videoId: 'AAAAAAAAAA1' },
        position: 'INSERT_AFTER_CURRENT_VIDEO',
      },
    ]);

    const empty = fakeEnv({}, { queue: 'empty' });
    assert.deepEqual(
      await runCatalogRequest(
        {
          op: 'play',
          action: 'addToQueue',
          endpoint: { queue: { playlistId: 'PLx' } },
        },
        empty.env,
      ),
      { ok: true, done: true },
    );
    assert.deepEqual(empty.navigations, [
      { watchPlaylistEndpoint: { playlistId: 'PLx' } },
    ]);

    // A browse endpoint is not playable.
    assert.deepEqual(
      await runCatalogRequest(
        {
          op: 'play',
          action: 'play',
          endpoint: { browse: { browseId: 'VLLM' } },
        },
        a.env,
      ),
      { ok: false, code: 'not_found' },
    );
  });

  it('re-validates blobs: unknown fields and bad IDs are dropped', () => {
    assert.deepEqual(
      toNavigationEndpoint({
        watch: { videoId: 'AAAAAAAAAA1', evil: 'x', params: 'bad param!' },
      }),
      { watchEndpoint: { videoId: 'AAAAAAAAAA1' } },
    );
    assert.equal(
      toNavigationEndpoint({ browse: { browseId: '../x' } }),
      undefined,
    );
    assert.equal(
      toNavigationEndpoint({ urlEndpoint: { url: 'https://x' } }),
      undefined,
    );
  });

  it('picks the smallest thumbnail of at least 160 px, else the largest', () => {
    assert.equal(
      pickThumbnail([
        { url: 'https://i.ytimg.com/a', width: 60, height: 60 },
        { url: 'https://i.ytimg.com/c', width: 544, height: 544 },
        { url: 'https://i.ytimg.com/b', width: 226, height: 226 },
      ]),
      'https://i.ytimg.com/b',
    );
    assert.equal(
      pickThumbnail([
        { url: '//yt3.ggpht.com/a', width: 48, height: 48 },
        { url: 'http://insecure/b', width: 88, height: 88 },
        { url: 'https://yt3.ggpht.com/c', width: 88, height: 88 },
      ]),
      'https://yt3.ggpht.com/c',
    );
  });
});

const homeOpen = () => ({
  browse: { browseId: 'MPREb_synthetic01', pageType: 'MUSIC_PAGE_TYPE_ALBUM' },
});

// ---- ref store ----

describe('RefStore', () => {
  it('issues random refs within RefSchema, dedupes by key, expires after the TTL', () => {
    let now = 0;
    const store = new RefStore(() => now, 1000, 100);
    const a = store.issue('dev1', 'k', () => ({ type: 'thumb', url: 'u' }));
    assert.match(a, /^[A-Za-z0-9_-]{16,32}$/);
    assert.equal(
      store.issue('dev1', 'k', () => ({ type: 'thumb', url: 'v' })),
      a,
    );
    now = 900;
    store.issue('dev1', 'k', () => ({ type: 'thumb', url: 'u' })); // re-sent: TTL restarts
    now = 1800;
    assert.deepEqual(store.get('dev1', a), { type: 'thumb', url: 'u' });
    now = 1900;
    assert.equal(store.get('dev1', a), null);
    const b = store.issue('dev1', 'k', () => ({ type: 'thumb', url: 'u' }));
    assert.notEqual(b, a);
  });

  it('scopes refs to one device', () => {
    const store = new RefStore();
    const a = store.issue('dev1', 'k', () => ({ type: 'thumb', url: 'u' }));
    assert.equal(store.get('dev2', a), null);
    assert.notEqual(
      store.issue('dev2', 'k', () => ({ type: 'thumb', url: 'u' })),
      a,
    );
    store.forget('dev1');
    assert.equal(store.get('dev1', a), null);
  });

  it('evicts the least recently sent entry over the cap', () => {
    const store = new RefStore(Date.now, 60_000, 3);
    const refs = ['a', 'b', 'c'].map((k) =>
      store.issue('d', k, () => ({ type: 'thumb', url: k })),
    );
    store.issue('d', 'a', () => ({ type: 'thumb', url: 'a' })); // 'a' is now the newest
    store.issue('d', 'z', () => ({ type: 'thumb', url: 'z' }));
    assert.equal(store.size('d'), 3);
    assert.equal(store.get('d', refs[1]), null, 'b evicted');
    assert.ok(store.get('d', refs[0]));
    assert.ok(store.get('d', refs[2]));
  });
});

// ---- catalog service ----

const rawItem = (n: number, extra: Partial<RawItem> = {}): RawItem => ({
  kind: 'song',
  title: `Song ${n}`,
  subtitle: 'Artist',
  durationSeconds: 180,
  explicit: false,
  thumbnailUrl: 'https://i.ytimg.com/vi/x/default.jpg',
  videoId: 'AAAAAAAAAA1',
  play: { watch: { videoId: 'AAAAAAAAAA1', n } },
  queue: { queue: { videoId: 'AAAAAAAAAA1' } },
  ...extra,
});

const rawPage = (p: Partial<RawPage>): RawPage => ({
  title: null,
  subtitle: null,
  header: null,
  chips: [],
  sections: [],
  continuation: null,
  mode: 'sections',
  ...p,
});

const service = (handler: (req: CatalogRequest) => unknown) => {
  const requests: CatalogRequest[] = [];
  const svc = new CatalogService({
    page: {
      request(req) {
        requests.push(req);
        return Promise.resolve(handler(req));
      },
    },
    thumbs: {
      isAllowed: isAllowedThumbUrl,
      get: () => Promise.resolve({ mime: 'image/jpeg', data: 'AAAA' }),
    },
  });
  return { svc, requests };
};

const ctx = { currentVideoId: 'AAAAAAAAAA1' };
const asPage = (a: CatalogAnswer): CatalogPage => {
  assert.ok(!('error' in a), JSON.stringify(a));
  assert.equal(a.type, 'catalog.page');
  CatalogPageSchema.parse(a);
  return a;
};

describe('CatalogService', () => {
  it('list pages: buffers past 100 items and appends them as items', async () => {
    const items = Array.from({ length: 130 }, (_, i) => rawItem(i));
    const { svc, requests } = service((req) =>
      req.op === 'browse'
        ? {
            ok: true,
            page: rawPage({
              mode: 'items',
              sections: [{ title: null, layout: 'list', items }],
              continuation: { continuation: { token: 'T1' } },
            }),
          }
        : {
            ok: true,
            page: rawPage({
              sections: [
                { title: null, layout: 'list', items: [rawItem(999)] },
              ],
            }),
          },
    );
    const cmd = {
      type: 'catalog.browse',
      id: 'b1',
      target: 'library.songs',
    } as const;
    const first = asPage(
      await svc.handle('dev', { ...cmd, continuation: null }, ctx),
    );
    assert.equal(first.append, null);
    assert.equal(first.title, 'Songs');
    assert.equal(first.sections[0].items.length, 100);
    assert.equal(first.sections[0].items[0].isCurrent, true);
    assert.deepEqual(first.sections[0].items[0].actions, [
      'play',
      'playNext',
      'addToQueue',
    ]);
    assert.ok(first.sections[0].items[0].thumb);

    const second = asPage(
      await svc.handle(
        'dev',
        { ...cmd, continuation: first.continuation },
        ctx,
      ),
    );
    assert.equal(second.append, 'items');
    assert.equal(second.sections.length, 1);
    assert.equal(second.sections[0].items.length, 30);
    assert.equal(second.title, 'Songs');
    assert.equal(requests.length, 1, 'served from the buffer');

    const third = asPage(
      await svc.handle(
        'dev',
        { ...cmd, continuation: second.continuation },
        ctx,
      ),
    );
    assert.equal(third.append, 'items');
    assert.deepEqual(
      third.sections[0].items.map((i) => i.title),
      ['Song 999'],
    );
    assert.equal(third.continuation, null);
    assert.deepEqual(requests[1], {
      op: 'continue',
      continuation: { continuation: { token: 'T1' } },
    });
  });

  it('feed pages append sections; an empty continuation is an empty sections append', async () => {
    const { svc } = service((req) =>
      req.op === 'browse'
        ? {
            ok: true,
            page: rawPage({
              sections: [
                { title: 'Listen again', layout: 'grid', items: [rawItem(1)] },
              ],
              chips: [
                {
                  title: 'Relax',
                  open: { browse: { browseId: 'FEmusic_home' } },
                },
              ],
              continuation: { continuation: { token: 'H' } },
            }),
          }
        : { ok: true, page: rawPage({ sections: [] }) },
    );
    const cmd = { type: 'catalog.browse', id: 'h', target: 'home' } as const;
    const first = asPage(
      await svc.handle('dev', { ...cmd, continuation: null }, ctx),
    );
    assert.equal(first.chips[0].title, 'Relax');
    const next = asPage(
      await svc.handle(
        'dev',
        { ...cmd, continuation: first.continuation },
        ctx,
      ),
    );
    assert.equal(next.append, 'sections');
    assert.deepEqual(next.sections, []);
    assert.deepEqual(next.chips, []);
    assert.equal(next.header, null);
    assert.equal(next.continuation, null);
  });

  it('items-mode continuation that comes back empty is an empty sections append', async () => {
    const { svc } = service((req) =>
      req.op === 'browse'
        ? {
            ok: true,
            page: rawPage({
              mode: 'items',
              sections: [{ title: null, layout: 'list', items: [rawItem(1)] }],
              continuation: { continuation: { token: 'X' } },
            }),
          }
        : { ok: true, page: rawPage({ sections: [] }) },
    );
    const cmd = {
      type: 'catalog.browse',
      id: 'x',
      target: 'library.liked',
    } as const;
    const first = asPage(
      await svc.handle('dev', { ...cmd, continuation: null }, ctx),
    );
    const next = asPage(
      await svc.handle(
        'dev',
        { ...cmd, continuation: first.continuation },
        ctx,
      ),
    );
    assert.equal(next.append, 'sections');
    assert.deepEqual(next.sections, []);
  });

  it('a cursor from another page, another device or with other fields is not_found', async () => {
    const { svc } = service(() => ({
      ok: true,
      page: rawPage({
        mode: 'items',
        sections: [{ title: null, layout: 'list', items: [rawItem(1)] }],
        continuation: { continuation: { token: 'X' } },
      }),
    }));
    const first = asPage(
      await svc.handle(
        'dev',
        {
          type: 'catalog.search',
          id: 's',
          query: 'a',
          filter: 'songs',
          continuation: null,
        },
        ctx,
      ),
    );
    const cursor = first.continuation!;
    for (const [dev, cmd] of [
      [
        'dev',
        {
          type: 'catalog.search',
          id: 's',
          query: 'b',
          filter: 'songs',
          continuation: cursor,
        },
      ],
      [
        'dev',
        {
          type: 'catalog.search',
          id: 's',
          query: 'a',
          filter: 'albums',
          continuation: cursor,
        },
      ],
      [
        'dev',
        {
          type: 'catalog.browse',
          id: 's',
          target: 'home',
          continuation: cursor,
        },
      ],
      [
        'other',
        {
          type: 'catalog.search',
          id: 's',
          query: 'a',
          filter: 'songs',
          continuation: cursor,
        },
      ],
    ] as const) {
      const a = await svc.handle(dev, cmd, ctx);
      assert.deepEqual('error' in a && a.error, 'not_found');
    }
  });

  it('open: the header ref is the page ref and plays the header endpoints; actions are enforced', async () => {
    const { svc, requests } = service((req) => {
      if (req.op === 'browse') {
        return {
          ok: true,
          page: rawPage({
            sections: [
              {
                title: 'Albums',
                layout: 'carousel',
                items: [
                  {
                    ...rawItem(1),
                    kind: 'album',
                    videoId: null,
                    play: undefined,
                    queue: undefined,
                    open: { browse: { browseId: 'MPREb_x' } },
                  },
                ],
              },
            ],
          }),
        };
      }
      if (req.op === 'open') {
        return {
          ok: true,
          page: rawPage({
            mode: 'items',
            header: {
              kind: 'album',
              title: 'Album',
              subtitle: 'Artist',
              thumbnailUrl: 'https://evil.example/x.jpg',
              play: { watchPlaylist: { playlistId: 'OLAK' } },
              shuffle: { watchPlaylist: { playlistId: 'OLAK', params: 's' } },
            },
            sections: [{ title: null, layout: 'list', items: [rawItem(1)] }],
          }),
        };
      }
      return { ok: true, done: true };
    });
    const home = asPage(
      await svc.handle(
        'dev',
        { type: 'catalog.browse', id: 'h', target: 'home', continuation: null },
        ctx,
      ),
    );
    const album = home.sections[0].items[0];
    assert.deepEqual(album.actions, ['open']);
    assert.equal(album.isCurrent, false, 'only songs/videos are current');

    const notOffered = await svc.handle(
      'dev',
      { type: 'catalog.play', id: 'p', ref: album.ref, action: 'play' },
      ctx,
    );
    assert.equal('error' in notOffered && notOffered.error, 'not_found');

    const page = asPage(
      await svc.handle(
        'dev',
        { type: 'catalog.open', id: 'o', ref: album.ref, continuation: null },
        ctx,
      ),
    );
    assert.equal(page.title, 'Album');
    assert.equal(page.header?.ref, album.ref);
    assert.deepEqual(page.header?.actions, ['play', 'shuffle']);
    assert.equal(
      page.header?.thumb,
      null,
      'non-allowlisted cover host gives no thumb',
    );

    const ok = await svc.handle(
      'dev',
      { type: 'catalog.play', id: 'p2', ref: album.ref, action: 'shuffle' },
      ctx,
    );
    assert.deepEqual(ok, { type: 'result', id: 'p2' });
    assert.deepEqual(requests.at(-1), {
      op: 'play',
      action: 'shuffle',
      endpoint: { watchPlaylist: { playlistId: 'OLAK', params: 's' } },
    });

    // A cursor or thumb ref is not an openable ref.
    const song = page.sections[0].items[0];
    const bad = await svc.handle(
      'dev',
      { type: 'catalog.open', id: 'o2', ref: song.thumb!, continuation: null },
      ctx,
    );
    assert.equal('error' in bad && bad.error, 'not_found');
  });

  it('treats page bridge output as untrusted', async () => {
    const { svc } = service(() => ({
      ok: true,
      page: {
        title: 'T'.repeat(2000),
        sections: [
          {
            title: 'x\u0000y',
            layout: 'weird',
            items: [
              { ...rawItem(1), kind: 'podcast' },
              { ...rawItem(2), play: undefined, queue: undefined },
              {
                ...rawItem(3),
                durationSeconds: -5,
                videoId: 'nope',
                title: 42,
              },
              {
                ...rawItem(4),
                play: { big: 'x'.repeat(10_000) },
                queue: undefined,
              },
            ],
          },
          'garbage',
        ],
      },
    }));
    const page = asPage(
      await svc.handle(
        'dev',
        { type: 'catalog.browse', id: 'o', target: 'home', continuation: null },
        ctx,
      ),
    );
    assert.equal(page.title, 'Home');
    assert.equal(page.sections.length, 1);
    const [section] = page.sections;
    assert.equal(section.title, 'x y');
    assert.equal(section.layout, 'carousel');
    assert.equal(section.items.length, 1);
    assert.equal(section.items[0].title, '');
    assert.equal(section.items[0].durationSeconds, null);
    assert.equal(section.items[0].isCurrent, false);
  });

  it('maps page bridge failures and garbage', async () => {
    for (const [reply, code] of [
      [{ ok: false, code: 'signed_out' }, 'signed_out'],
      [{ ok: false, code: 'whatever' }, 'unavailable'],
      [null, 'unavailable'],
      [{ ok: true, page: 'nope' }, 'unavailable'],
    ] as const) {
      const { svc } = service(() => reply);
      const a = await svc.handle(
        'dev',
        {
          type: 'catalog.browse',
          id: 'b',
          target: 'library.albums',
          continuation: null,
        },
        ctx,
      );
      assert.equal('error' in a && a.error, code);
    }
    const { svc } = service(() => ({
      ok: true,
      suggestions: [
        'a',
        'a',
        '',
        'x'.repeat(151),
        'b\u0007',
        ...'cdefghijklmn'.split(''),
      ],
    }));
    const s = await svc.handle(
      'dev',
      { type: 'catalog.suggest', id: 's', query: 'q' },
      ctx,
    );
    assert.ok(!('error' in s) && s.type === 'catalog.suggestions');
    assert.deepEqual(s.items, [
      'a',
      'c',
      'd',
      'e',
      'f',
      'g',
      'h',
      'i',
      'j',
      'k',
    ]);
  });
});

describe('trimToFit', () => {
  it('removes items from the end of the largest sections until the page fits', () => {
    const item = (n: number) => ({
      ref: 'A'.repeat(24),
      kind: 'song' as const,
      title: `${n}`.padEnd(500, 't'),
      subtitle: 's'.repeat(500),
      durationSeconds: null,
      explicit: false,
      actions: ['play' as const],
      thumb: null,
      isCurrent: false,
    });
    const section = (count: number) => ({
      title: 'x',
      layout: 'list' as const,
      items: Array.from({ length: count }, (_, i) => item(i)),
    });
    const page: CatalogPage = {
      type: 'catalog.page',
      id: 'p',
      title: 't',
      subtitle: null,
      header: null,
      chips: [],
      sections: Array.from({ length: LIMITS.catalogSectionsMax }, (_, i) =>
        section(i === 0 ? 1 : LIMITS.catalogItemsMax),
      ),
      append: null,
      continuation: null,
    };
    assert.ok(
      Buffer.byteLength(JSON.stringify(page)) > LIMITS.serverMessageBytes,
    );
    trimToFit(page);
    const size = Buffer.byteLength(JSON.stringify(page));
    assert.ok(size <= LIMITS.serverMessageBytes, `${size}`);
    assert.ok(
      size > LIMITS.serverMessageBytes - 5000,
      'trims only what it must',
    );
    assert.equal(
      page.sections[0].items.length,
      1,
      'smallest section untouched',
    );
    assert.equal(
      page.sections[1].items[0].title.startsWith('0'),
      true,
      'keeps the start',
    );
    CatalogPageSchema.parse(page);

    const tiny = { ...page, sections: [section(3)] };
    trimToFit(tiny, 1500);
    assert.equal(tiny.sections[0].items.length, 1);
  });
});

// ---- thumbnails ----

const response = (
  status: number,
  headers: Record<string, string>,
  body: Uint8Array<ArrayBuffer> | null = null,
) => new Response(body, { status, headers });

describe('ThumbnailFetcher', () => {
  const encode = (bytes: Buffer) => ({
    mime: 'image/jpeg' as const,
    bytes: Buffer.from(`enc:${bytes.length}`),
  });

  it('allows only the exact https hosts', () => {
    for (const ok of [
      'https://i.ytimg.com/vi/x/hqdefault.jpg',
      'https://lh3.googleusercontent.com/abc=w120',
      'https://yt3.ggpht.com/a',
      'https://yt3.googleusercontent.com/a',
    ]) {
      assert.ok(isAllowedThumbUrl(ok), ok);
    }
    for (const bad of [
      'http://i.ytimg.com/a',
      'https://i.ytimg.com.evil.com/a',
      'https://evil.com/i.ytimg.com',
      'https://x.i.ytimg.com/a',
      'https://user@i.ytimg.com/a',
      'https://i.ytimg.com:8443/a',
      'https://192.168.1.1/a',
      'file:///etc/passwd',
      'not a url',
    ]) {
      assert.ok(!isAllowedThumbUrl(bad), bad);
    }
  });

  it('fetches, re-encodes and caches; follows redirects only to allowed hosts', async () => {
    const calls: string[] = [];
    const f = new ThumbnailFetcher({
      encode,
      fetch: (url, init) => {
        calls.push(url);
        assert.equal(init.redirect, 'manual');
        if (url.endsWith('/hop')) {
          return Promise.resolve(
            response(302, { location: 'https://yt3.ggpht.com/final' }),
          );
        }
        if (url.endsWith('/away')) {
          return Promise.resolve(
            response(302, { location: 'https://evil.example/x' }),
          );
        }
        return Promise.resolve(
          response(200, { 'content-type': 'image/jpeg' }, new Uint8Array(100)),
        );
      },
    });
    assert.deepEqual(await f.get('https://i.ytimg.com/hop'), {
      mime: 'image/jpeg',
      data: Buffer.from('enc:100').toString('base64'),
    });
    assert.deepEqual(calls, [
      'https://i.ytimg.com/hop',
      'https://yt3.ggpht.com/final',
    ]);
    await f.get('https://i.ytimg.com/hop');
    assert.equal(calls.length, 2, 'cached');
    assert.equal(await f.get('https://i.ytimg.com/away'), null);
    assert.ok(!calls.includes('https://evil.example/x'));
    assert.equal(await f.get('https://evil.example/x'), null);
  });

  it('enforces the download size cap, content type and the encoded size', async () => {
    const big = new ThumbnailFetcher({
      encode,
      maxDownloadBytes: 1000,
      fetch: (url) =>
        Promise.resolve(
          url.endsWith('declared')
            ? response(
                200,
                { 'content-type': 'image/jpeg', 'content-length': '5000' },
                new Uint8Array(10),
              )
            : url.endsWith('html')
              ? response(
                  200,
                  { 'content-type': 'text/html' },
                  new Uint8Array(10),
                )
              : response(
                  200,
                  { 'content-type': 'image/jpeg' },
                  new Uint8Array(5000),
                ),
        ),
    });
    assert.equal(await big.get('https://i.ytimg.com/declared'), null);
    assert.equal(await big.get('https://i.ytimg.com/streamed'), null);
    assert.equal(await big.get('https://i.ytimg.com/html'), null);

    const tooBig = new ThumbnailFetcher({
      encode: () => ({
        mime: 'image/jpeg',
        bytes: Buffer.alloc(LIMITS.thumbMaxBytes + 1),
      }),
      fetch: () =>
        Promise.resolve(
          response(200, { 'content-type': 'image/png' }, new Uint8Array(10)),
        ),
    });
    assert.equal(await tooBig.get('https://i.ytimg.com/a'), null);
  });
});
