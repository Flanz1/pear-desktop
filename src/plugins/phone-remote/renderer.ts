/**
 * Page bridge: with catalog-page.ts, the ONLY module of this plugin that
 * touches YT Music page internals (DOM selectors, Polymer element APIs, the
 * queue store). When YouTube changes the player page, this is the file to
 * fix; catalog-page.ts owns innertube browse/search/playback for the catalog.
 *
 * It reports plain-data snapshots to the backend and answers queue requests.
 * The backend sanitises and validates everything it gets from here.
 */
import { createRenderer } from '@/utils';

import { createPageEnv, runCatalogRequest } from './catalog-page';
import { CHANNELS } from './channels';

import type { CatalogRequest } from './catalog-types';
import type { PhoneRemoteConfig } from './config';
import type { RendererContext } from '@/types/contexts';
import type { GetState, QueueItem } from '@/types/datahost-get-state';
import type { MusicPlayer } from '@/types/music-player';
import type { QueueElement } from '@/types/queue';

type PlayerBar = HTMLElement & { getState?: () => GetState };

const playerBar = () => document.querySelector<PlayerBar>('ytmusic-player-bar');

const readRepeat = (): string | undefined =>
  playerBar()?.getState?.().queue.repeatMode;

const readShuffle = () => playerBar()?.hasAttribute('shuffle-on') ?? false;

const readLike = () =>
  document
    .querySelector('#like-button-renderer')
    ?.getAttribute('like-status') ?? 'INDIFFERENT';

const statusOf = (state: number) => {
  switch (state) {
    case 1:
      return 'playing';
    case 2:
      return 'paused';
    case 3:
      return 'buffering';
    default:
      return 'stopped';
  }
};

const queueElement = () => document.querySelector<QueueElement>('#queue');

const rendererOf = (item: QueueItem) =>
  item.playlistPanelVideoRenderer ??
  item.playlistPanelVideoWrapperRenderer?.primaryRenderer
    ?.playlistPanelVideoRenderer;

const parseLength = (text: string | undefined): number | null => {
  if (!text || !/^\d+(:\d{1,2}){0,2}$/.test(text)) return null;
  return text.split(':').reduce((acc, p) => {
    const minutes = acc * 60;
    return minutes + Number(p);
  }, 0);
};

const readQueue = () => {
  const items = queueElement()?.queue.getItems() ?? null;
  if (!items) return null;
  return items.map((item, index) => {
    const r = rendererOf(item);
    const runs = r?.title?.runs;
    const byline = r?.longBylineText?.runs;
    const thumbs = r?.thumbnail?.thumbnails;
    const length = r?.lengthText?.runs?.[0]?.text;
    return {
      index,
      videoId: r?.videoId ?? '',
      title: runs?.map((x) => x.text ?? '').join('') ?? '',
      artist: byline?.[0]?.text ?? '',
      durationSeconds: parseLength(length),
      thumbnailUrl: thumbs?.at(-1)?.url ?? null,
      isCurrent: r?.selected === true,
    };
  });
};

interface BridgeState {
  api: MusicPlayer | null;
  cleanups: (() => void)[];
  queueRevision: number;
  pending: boolean;
  lastTimeUpdate: number;
}

const bridge: BridgeState = {
  api: null,
  cleanups: [],
  queueRevision: 0,
  pending: false,
  lastTimeUpdate: 0,
};

let context: RendererContext<PhoneRemoteConfig> | null = null;

const sendSnapshot = () => {
  const api = bridge.api;
  if (!api || !context) return;
  context.ipc.send(CHANNELS.snapshot, {
    status: statusOf(api.getPlayerState()),
    positionSeconds: api.getCurrentTime(),
    volume: api.getVolume(),
    muted: api.isMuted(),
    repeat: readRepeat(),
    shuffle: readShuffle(),
    like: readLike(),
    queueRevision: bridge.queueRevision,
  });
};

const scheduleSnapshot = () => {
  if (bridge.pending) return;
  bridge.pending = true;
  setTimeout(() => {
    bridge.pending = false;
    sendSnapshot();
  }, 50);
};

const observe = (target: Element | null, filter: string[]) => {
  if (!target) return;
  const o = new MutationObserver(scheduleSnapshot);
  o.observe(target, { attributes: true, attributeFilter: filter });
  bridge.cleanups.push(() => o.disconnect());
};

const attach = (api: MusicPlayer) => {
  if (bridge.api) return;
  bridge.api = api;

  const video = document.querySelector('video');
  if (video) {
    const events = [
      'play',
      'pause',
      'playing',
      'waiting',
      'seeked',
      'volumechange',
      'ended',
      'emptied',
      'loadeddata',
    ];
    for (const e of events) video.addEventListener(e, scheduleSnapshot);
    // Lets the backend spot position drift; it only forwards real jumps.
    const onTime = () => {
      const now = Date.now();
      if (now - bridge.lastTimeUpdate < 1000) return;
      bridge.lastTimeUpdate = now;
      scheduleSnapshot();
    };
    video.addEventListener('timeupdate', onTime);
    bridge.cleanups.push(() => {
      for (const e of events) video.removeEventListener(e, scheduleSnapshot);
      video.removeEventListener('timeupdate', onTime);
    });
  }

  observe(playerBar(), ['shuffle-on']);
  observe(document.querySelector('#right-controls .repeat'), ['title']);
  observe(document.querySelector('#like-button-renderer'), ['like-status']);

  const store = queueElement()?.queue.store.store;
  if (store) {
    let lastItems = store.getState().queue.items;
    const unsubscribe = store.subscribe(() => {
      const items = store.getState().queue.items;
      if (items !== lastItems) {
        lastItems = items;
        bridge.queueRevision += 1;
        scheduleSnapshot();
      }
    });
    if (typeof unsubscribe === 'function') {
      bridge.cleanups.push(unsubscribe as () => void);
    }
  }

  sendSnapshot();
};

export const renderer = createRenderer<
  { onPlayerApiReady: (api: MusicPlayer) => void },
  PhoneRemoteConfig
>({
  start(ctx) {
    context = ctx;
    ctx.ipc.on(CHANNELS.requestSnapshot, () => sendSnapshot());
    ctx.ipc.on(CHANNELS.getQueue, (requestId: unknown) => {
      ctx.ipc.send(CHANNELS.queue, requestId, readQueue());
    });
    ctx.ipc.on(
      CHANNELS.catalogRequest,
      (requestId: unknown, request: CatalogRequest) => {
        if (typeof requestId !== 'string') return;
        runCatalogRequest(request, createPageEnv())
          .then((reply) =>
            ctx.ipc.send(CHANNELS.catalogReply, requestId, reply),
          )
          .catch(() =>
            ctx.ipc.send(CHANNELS.catalogReply, requestId, {
              ok: false,
              code: 'unavailable',
            }),
          );
      },
    );
    ctx.ipc.on(CHANNELS.setVolume, (volume: unknown) => {
      const api = bridge.api;
      if (!api || typeof volume !== 'number' || !Number.isFinite(volume)) {
        return;
      }
      const v = Math.min(100, Math.max(0, Math.round(volume)));
      api.setVolume(v);
      // Keep YT Music's own sliders in step, as the precise-volume plugin
      // does, so the page doesn't later reapply its old slider value.
      for (const sel of ['#volume-slider', '#expand-volume-slider']) {
        const slider = document.querySelector<HTMLInputElement>(sel);
        if (slider) slider.value = String(v > 0 && v < 5 ? 5 : v);
      }
      scheduleSnapshot();
    });
    ctx.ipc.on(CHANNELS.clearLike, () => {
      // Clicking the active button clears it (LIKE or DISLIKE -> INDIFFERENT).
      document
        .querySelector<HTMLElement>(
          '#like-button-renderer #button-shape-like > button[aria-pressed=true], ' +
            '#like-button-renderer #button-shape-dislike > button[aria-pressed=true]',
        )
        ?.click();
    });
    // Enabled after the player loaded: the app won't call onPlayerApiReady.
    const existing = document.querySelector('#movie_player');
    if (existing && 'getPlayerState' in existing) {
      attach(existing as unknown as MusicPlayer);
    }
  },
  onPlayerApiReady(api: MusicPlayer) {
    attach(api);
  },
  stop(ctx) {
    for (const c of bridge.cleanups.splice(0)) c();
    bridge.api = null;
    for (const ch of [
      CHANNELS.requestSnapshot,
      CHANNELS.getQueue,
      CHANNELS.clearLike,
      CHANNELS.catalogRequest,
    ]) {
      ctx.ipc.removeAllListeners(ch);
    }
    context = null;
  },
});
