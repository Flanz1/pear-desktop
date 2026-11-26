import { z } from 'zod';

import {
  AppInfoSchema,
  CursorSchema,
  ErrorCodeSchema,
  LikeStatusSchema,
  PlaybackStatusSchema,
  RefSchema,
  RepeatModeSchema,
  RequestIdSchema,
  SecondsSchema,
  TextSchema,
  ThumbRefSchema,
  TokenSchema,
  TrackSchema,
  UuidSchema,
  VersionSchema,
  VideoIdSchema,
  VolumeSchema,
} from './common.ts';
import { LIMITS } from './limits.ts';

export const PlaybackSchema = z.strictObject({
  status: PlaybackStatusSchema,
  /** Position when the desktop sent this. Clients interpolate from receipt time while playing. */
  positionSeconds: SecondsSchema,
});

export const VolumeStateSchema = z.strictObject({
  volume: VolumeSchema,
  muted: z.boolean(),
});

export const PlayerStateSchema = z.strictObject({
  track: TrackSchema.nullable(),
  playback: PlaybackSchema,
  volume: VolumeStateSchema,
  repeat: RepeatModeSchema,
  shuffle: z.boolean(),
  like: LikeStatusSchema,
});

export const QueueItemSchema = z.strictObject({
  index: z.int().min(0).max(9999),
  videoId: VideoIdSchema,
  title: TextSchema,
  artist: TextSchema,
  durationSeconds: SecondsSchema.nullable(),
  thumbnailUrl: TrackSchema.shape.thumbnailUrl,
  isCurrent: z.boolean(),
  /** 1.1: same rule as Track.thumb (present when the negotiated minor is >= 1). */
  thumb: TrackSchema.shape.thumb,
});

// ---- Catalog (1.1) ----

const unique = <T>(a: readonly T[]) => new Set(a).size === a.length;

export const CatalogItemKindSchema = z.enum([
  'song',
  'video',
  'album',
  'single',
  'playlist',
  'artist',
  'episode',
  'station',
]);

export const CatalogItemActionSchema = z.enum([
  'open',
  'play',
  'shuffle',
  'playNext',
  'addToQueue',
]);

export const CatalogItemSchema = z.strictObject({
  /** Use with catalog.open (action `open`) or catalog.play (the other actions). */
  ref: RefSchema,
  kind: CatalogItemKindSchema,
  title: TextSchema,
  /** e.g. "Song • Rick Astley • 3:33". May be empty. */
  subtitle: TextSchema,
  durationSeconds: SecondsSchema.nullable(),
  explicit: z.boolean(),
  /** What the phone may offer for this item. Non-empty, no duplicates. */
  actions: z
    .array(CatalogItemActionSchema)
    .min(1)
    .max(CatalogItemActionSchema.options.length)
    .refine(unique, 'duplicate action'),
  thumb: ThumbRefSchema.nullable(),
  /** True if this is the track playing on the desktop when the page was built. Not updated later. */
  isCurrent: z.boolean(),
});

export const CatalogSectionSchema = z.strictObject({
  /** Shelf title ("Listen again", "Quick picks"), or null for an untitled list. */
  title: TextSchema.nullable(),
  layout: z.enum(['carousel', 'list', 'grid']),
  items: z.array(CatalogItemSchema).min(1).max(LIMITS.catalogItemsMax),
});

export const CatalogChipSchema = z.strictObject({
  /** e.g. "Relax", "Energize". Opened with catalog.open. */
  title: TextSchema,
  ref: RefSchema,
});

export const CatalogHeaderSchema = z.strictObject({
  kind: z.enum(['album', 'playlist', 'artist']),
  title: TextSchema,
  subtitle: TextSchema,
  thumb: ThumbRefSchema.nullable(),
  /** The page's own ref, for catalog.play with the actions below. */
  ref: RefSchema,
  actions: z
    .array(z.enum(['play', 'shuffle']))
    .max(2)
    .refine(unique, 'duplicate action'),
});

export const CatalogPageSchema = z
  .strictObject({
    type: z.literal('catalog.page'),
    id: RequestIdSchema,
    title: TextSchema,
    subtitle: TextSchema.nullable(),
    header: CatalogHeaderSchema.nullable(),
    chips: z.array(CatalogChipSchema).max(LIMITS.catalogChipsMax),
    sections: z.array(CatalogSectionSchema).max(LIMITS.catalogSectionsMax),
    /**
     * How this answer joins the page the phone already shows:
     * - null: a first page (the request had `continuation: null`).
     * - 'sections': append `sections` after the page's sections.
     * - 'items': `sections` has exactly one entry; append its items to the
     *   page's last section.
     * Continuation answers carry `header: null` and `chips: []`.
     */
    append: z.enum(['sections', 'items']).nullable(),
    /** Pass to the same command to load more, or null when there is no more. */
    continuation: CursorSchema.nullable(),
  })
  .refine((p) => p.append !== 'items' || p.sections.length === 1, {
    message: "append 'items' needs exactly one section",
  })
  .refine(
    (p) => p.append === null || (p.header === null && p.chips.length === 0),
    { message: 'continuation answers carry no header or chips' },
  );

export const CatalogSuggestionsSchema = z.strictObject({
  type: z.literal('catalog.suggestions'),
  id: RequestIdSchema,
  items: z
    .array(z.string().min(1).max(LIMITS.catalogQueryMax))
    .max(LIMITS.catalogSuggestionsMax),
});

export const CatalogThumbSchema = z.strictObject({
  type: z.literal('catalog.thumb'),
  id: RequestIdSchema,
  mime: z.enum(['image/jpeg', 'image/webp', 'image/png']),
  /** Standard base64 with padding. Decoded: at most 24 KiB, at most 160x160 px. */
  data: z
    .base64()
    .min(4)
    .max(Math.ceil(LIMITS.thumbMaxBytes / 3) * 4),
});

export const WelcomeSchema = z.strictObject({
  type: z.literal('welcome'),
  /** The negotiated version. Clients must not send anything newer. */
  protocol: VersionSchema,
  server: AppInfoSchema,
  instanceId: UuidSchema,
  /** Desktop display name, user-editable. */
  name: TextSchema,
  /**
   * Optional features beyond the base protocol for this version (see
   * CAPABILITIES). Empty in 1.0.
   */
  capabilities: z.array(z.string().regex(/^[a-z0-9.-]{1,64}$/)).max(64),
});

export const ErrorMessageSchema = z.strictObject({
  type: z.literal('error'),
  /** Echoes the request ID when the error answers a specific command. */
  id: RequestIdSchema.nullable(),
  code: ErrorCodeSchema,
  /** Human-readable, English, for logs. Never shown as-is in place of localized UI. */
  message: TextSchema,
  /** Present with update_required / desktop_update_required. */
  supported: z
    .strictObject({
      min: z.int().min(0),
      max: z.int().min(0),
    })
    .nullable(),
});

export const ServerMessageSchema = z.discriminatedUnion('type', [
  WelcomeSchema,
  ErrorMessageSchema,
  z.strictObject({
    type: z.literal('pair.result'),
    deviceId: UuidSchema,
    token: TokenSchema,
    instanceId: UuidSchema,
  }),
  z.strictObject({ type: z.literal('result'), id: RequestIdSchema }),
  z.strictObject({
    type: z.literal('state'),
    /** Set when this answers `state.get`; null when pushed. */
    id: RequestIdSchema.nullable(),
    state: PlayerStateSchema,
  }),
  z.strictObject({
    type: z.literal('track.changed'),
    track: TrackSchema.nullable(),
  }),
  z.strictObject({
    type: z.literal('playback.changed'),
    playback: PlaybackSchema,
  }),
  z.strictObject({
    type: z.literal('volume.changed'),
    volume: VolumeStateSchema,
  }),
  z.strictObject({
    type: z.literal('repeat.changed'),
    repeat: RepeatModeSchema,
  }),
  z.strictObject({ type: z.literal('shuffle.changed'), shuffle: z.boolean() }),
  z.strictObject({ type: z.literal('like.changed'), like: LikeStatusSchema }),
  /** Hint only: clients showing the queue send `queue.get`. */
  z.strictObject({ type: z.literal('queue.changed') }),
  z.strictObject({
    type: z.literal('queue'),
    id: RequestIdSchema,
    items: z.array(QueueItemSchema).max(LIMITS.queueMaxItems),
    /** True if the queue had more than queueMaxItems items. */
    truncated: z.boolean(),
  }),
  z.strictObject({
    type: z.literal('server.closing'),
    reason: z.enum(['shutdown', 'disabled', 'revoked']),
  }),
  // 1.1, only ever sent in answer to catalog commands.
  CatalogPageSchema,
  CatalogSuggestionsSchema,
  CatalogThumbSchema,
]);

export type Welcome = z.infer<typeof WelcomeSchema>;
export type ErrorMessage = z.infer<typeof ErrorMessageSchema>;
export type PlayerState = z.infer<typeof PlayerStateSchema>;
export type ServerMessage = z.infer<typeof ServerMessageSchema>;
export type ServerMessageType = ServerMessage['type'];
export type CatalogItem = z.infer<typeof CatalogItemSchema>;
export type CatalogSection = z.infer<typeof CatalogSectionSchema>;
export type CatalogPage = z.infer<typeof CatalogPageSchema>;
