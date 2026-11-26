import { randomUUID } from 'node:crypto';

import {
  type BrowserWindow,
  type IpcMainEvent,
  ipcMain,
  nativeImage,
  session,
} from 'electron';

import { CHANNELS } from '../channels';

import type { CatalogRequest } from '../catalog-types.ts';
import type { CatalogPagePort } from './catalog.ts';
import type { ThumbEncoder, ThumbFetch } from './thumbnails.ts';

const REQUEST_TIMEOUT_MS = 10_000;

/**
 * CatalogPagePort over IPC to the page bridge (renderer.ts -> catalog-page.ts).
 * Replies are only accepted from our own window and are validated by
 * catalog.ts; on timeout the request fails as `unavailable`.
 */
export class CatalogBridge implements CatalogPagePort {
  private readonly win: BrowserWindow;
  private readonly pending = new Map<string, (reply: unknown) => void>();

  constructor(win: BrowserWindow) {
    this.win = win;
  }

  start() {
    ipcMain.on(CHANNELS.catalogReply, this.onReply);
  }

  stop() {
    ipcMain.off(CHANNELS.catalogReply, this.onReply);
    for (const resolve of this.pending.values()) {
      resolve({ ok: false, code: 'not_ready' });
    }
    this.pending.clear();
  }

  request(req: CatalogRequest): Promise<unknown> {
    if (this.win.isDestroyed()) {
      return Promise.resolve({ ok: false, code: 'not_ready' });
    }
    const requestId = randomUUID();
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        resolve({ ok: false, code: 'unavailable' });
      }, REQUEST_TIMEOUT_MS);
      this.pending.set(requestId, (reply) => {
        clearTimeout(timer);
        resolve(reply);
      });
      this.win.webContents.send(CHANNELS.catalogRequest, requestId, req);
    });
  }

  private readonly onReply = (
    event: IpcMainEvent,
    requestId: unknown,
    reply: unknown,
  ) => {
    if (event.sender !== this.win.webContents) return;
    if (typeof requestId !== 'string') return;
    const resolve = this.pending.get(requestId);
    if (!resolve) return;
    this.pending.delete(requestId);
    resolve(reply);
  };
}

/**
 * Thumbnail downloads go through a separate in-memory session, so the
 * user's Google cookies are never attached to them.
 */
export const thumbFetch: ThumbFetch = (url, init) =>
  session.fromPartition('phone-remote-thumbs', { cache: false }).fetch(url, {
    redirect: init.redirect,
    signal: init.signal,
    credentials: 'omit',
  });

/** Decode, resize to fit, re-encode as JPEG until it is under the byte cap. */
export const encodeThumb: ThumbEncoder = (bytes, maxPixels, maxBytes) => {
  const image = nativeImage.createFromBuffer(bytes);
  if (image.isEmpty()) return null;
  const { width, height } = image.getSize();
  if (!width || !height) return null;
  for (const px of [maxPixels, 120, 96, 64]) {
    const scale = Math.min(1, px / Math.max(width, height));
    const resized =
      scale < 1
        ? image.resize({
            width: Math.max(1, Math.round(width * scale)),
            height: Math.max(1, Math.round(height * scale)),
            quality: 'good',
          })
        : image;
    for (const quality of [80, 65, 50, 35]) {
      const jpeg = resized.toJPEG(quality);
      if (jpeg.length > 0 && jpeg.length <= maxBytes) {
        return { mime: 'image/jpeg', bytes: jpeg };
      }
    }
  }
  return null;
};
