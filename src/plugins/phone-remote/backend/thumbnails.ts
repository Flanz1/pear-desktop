import { LIMITS } from '../../../../protocol/src/index.ts';

/** SPEC 10.6: the only hosts covers are fetched from (exact match). */
export const THUMB_HOSTS: ReadonlySet<string> = new Set([
  'i.ytimg.com',
  'lh3.googleusercontent.com',
  'yt3.ggpht.com',
  'yt3.googleusercontent.com',
]);

export const isAllowedThumbUrl = (raw: string): boolean => {
  if (typeof raw !== 'string' || raw.length > 2048) return false;
  try {
    const u = new URL(raw);
    return (
      u.protocol === 'https:' &&
      THUMB_HOSTS.has(u.hostname) &&
      u.username === '' &&
      u.password === '' &&
      (u.port === '' || u.port === '443')
    );
  } catch {
    return false;
  }
};

/** A fetch that does NOT follow redirects by itself. */
export type ThumbFetch = (
  url: string,
  init: { redirect: 'manual'; signal: AbortSignal },
) => Promise<Response>;

/** Decodes, resizes to fit maxPixels and re-encodes under maxBytes, or null. */
export type ThumbEncoder = (
  bytes: Buffer,
  maxPixels: number,
  maxBytes: number,
) => { mime: 'image/jpeg' | 'image/webp' | 'image/png'; bytes: Buffer } | null;

export interface Thumb {
  mime: 'image/jpeg' | 'image/webp' | 'image/png';
  /** Standard base64. */
  data: string;
}

export interface ThumbnailFetcherOptions {
  fetch: ThumbFetch;
  encode: ThumbEncoder;
  maxDownloadBytes?: number;
  timeoutMs?: number;
  cacheSize?: number;
  maxRedirects?: number;
}

/**
 * Fetches covers from the allowlisted hosts only, with a download size and
 * time cap, and re-encodes them (SPEC 10.6). Caches results by source URL.
 */
export class ThumbnailFetcher {
  private readonly opts: Required<ThumbnailFetcherOptions>;
  private readonly cache = new Map<string, Thumb>();
  private readonly inflight = new Map<string, Promise<Thumb | null>>();

  constructor(opts: ThumbnailFetcherOptions) {
    this.opts = {
      maxDownloadBytes: 2 * 1024 * 1024,
      timeoutMs: 8000,
      cacheSize: 500,
      maxRedirects: 3,
      ...opts,
    };
  }

  isAllowed(url: string) {
    return isAllowedThumbUrl(url);
  }

  async get(url: string): Promise<Thumb | null> {
    if (!isAllowedThumbUrl(url)) return null;
    const hit = this.cache.get(url);
    if (hit) {
      this.cache.delete(url);
      this.cache.set(url, hit);
      return hit;
    }
    let p = this.inflight.get(url);
    if (!p) {
      p = this.load(url).finally(() => this.inflight.delete(url));
      this.inflight.set(url, p);
    }
    return p;
  }

  clear() {
    this.cache.clear();
  }

  private async load(url: string): Promise<Thumb | null> {
    const bytes = await this.download(url).catch(() => null);
    if (!bytes) return null;
    let encoded: ReturnType<ThumbEncoder>;
    try {
      encoded = this.opts.encode(
        bytes,
        LIMITS.thumbMaxPixels,
        LIMITS.thumbMaxBytes,
      );
    } catch {
      return null;
    }
    if (
      !encoded ||
      encoded.bytes.length === 0 ||
      encoded.bytes.length > LIMITS.thumbMaxBytes
    ) {
      return null;
    }
    const thumb: Thumb = {
      mime: encoded.mime,
      data: encoded.bytes.toString('base64'),
    };
    this.cache.set(url, thumb);
    while (this.cache.size > this.opts.cacheSize) {
      const oldest = this.cache.keys().next().value;
      if (oldest === undefined) break;
      this.cache.delete(oldest);
    }
    return thumb;
  }

  private async download(start: string): Promise<Buffer | null> {
    const signal = AbortSignal.timeout(this.opts.timeoutMs);
    let current = start;
    for (let hop = 0; hop <= this.opts.maxRedirects; hop++) {
      if (!isAllowedThumbUrl(current)) return null;
      const res = await this.opts.fetch(current, {
        redirect: 'manual',
        signal,
      });
      if (res.status >= 300 && res.status < 400) {
        const location = res.headers.get('location');
        await res.body?.cancel().catch(() => {});
        if (!location) return null;
        try {
          current = new URL(location, current).toString();
        } catch {
          return null;
        }
        continue;
      }
      if (!res.ok || !res.body) {
        await res.body?.cancel().catch(() => {});
        return null;
      }
      const type = res.headers.get('content-type') ?? '';
      if (!type.toLowerCase().startsWith('image/')) {
        await res.body.cancel().catch(() => {});
        return null;
      }
      const declared = Number(res.headers.get('content-length'));
      if (Number.isFinite(declared) && declared > this.opts.maxDownloadBytes) {
        await res.body.cancel().catch(() => {});
        return null;
      }
      return readCapped(res.body, this.opts.maxDownloadBytes);
    }
    return null;
  }
}

const readCapped = async (
  body: ReadableStream<Uint8Array>,
  max: number,
): Promise<Buffer | null> => {
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel().catch(() => {});
      return null;
    }
    chunks.push(value);
  }
  return total > 0 ? Buffer.concat(chunks) : null;
};
