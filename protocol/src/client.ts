import { z } from 'zod';

import {
  AppInfoSchema,
  CursorSchema,
  DeviceNameSchema,
  LikeStatusSchema,
  PairingCodeSchema,
  PairingSecretSchema,
  RefSchema,
  RepeatModeSchema,
  RequestIdSchema,
  SecondsSchema,
  ThumbRefSchema,
  TokenSchema,
  UuidSchema,
  VersionSchema,
  VideoIdSchema,
  VolumeSchema,
} from './common.ts';
import { LIMITS } from './limits.ts';

/**
 * Version-stable envelope. `type` and `protocol` keep this shape in every
 * major version, so the desktop can always read the version of a first
 * message and answer "update required" instead of failing to parse it.
 */
export const HandshakeEnvelopeSchema = z.looseObject({
  type: z.enum(['hello', 'pair.request']),
  protocol: VersionSchema,
});

// ---- Handshake (first message on a connection) ----

export const HelloSchema = z.strictObject({
  type: z.literal('hello'),
  protocol: VersionSchema,
  client: AppInfoSchema,
  deviceId: UuidSchema,
  token: TokenSchema,
});

export const PairDeviceSchema = z.strictObject({
  name: DeviceNameSchema,
  model: DeviceNameSchema.nullable(),
});

/** Exactly one of `secret` (from the QR code) or `code` (typed by the user). */
export const PairRequestSchema = z.union([
  z.strictObject({
    type: z.literal('pair.request'),
    protocol: VersionSchema,
    client: AppInfoSchema,
    device: PairDeviceSchema,
    secret: PairingSecretSchema,
  }),
  z.strictObject({
    type: z.literal('pair.request'),
    protocol: VersionSchema,
    client: AppInfoSchema,
    device: PairDeviceSchema,
    code: PairingCodeSchema,
  }),
]);

export const ClientHandshakeSchema = z.union([HelloSchema, PairRequestSchema]);

// ---- Commands (only after `welcome`) ----

// ---- Catalog (1.1) ----

export const CatalogBrowseTargetSchema = z.enum([
  'home',
  'library.playlists',
  'library.albums',
  'library.artists',
  'library.songs',
  'library.liked',
]);

export const CatalogSearchFilterSchema = z.enum([
  'top',
  'songs',
  'albums',
  'artists',
  'playlists',
  'videos',
]);

export const CatalogPlayActionSchema = z.enum([
  'play',
  'shuffle',
  'playNext',
  'addToQueue',
]);

/**
 * Search text. Trimmed, then 1-150 UTF-16 code units, no control characters
 * (U+0000-U+001F, U+007F). Validated after trimming.
 */
export const CatalogQuerySchema = z
  .string()
  .trim()
  .min(1)
  .max(LIMITS.catalogQueryMax)
  .regex(/^[^\u0000-\u001f\u007f]*$/);

const cmd = <T extends string, S extends z.ZodRawShape>(type: T, shape: S) =>
  z.strictObject({
    type: z.literal(type),
    id: RequestIdSchema,
    ...shape,
  });

export const ClientCommandSchema = z.discriminatedUnion('type', [
  cmd('player.play', {}),
  cmd('player.pause', {}),
  cmd('player.togglePlay', {}),
  cmd('player.next', {}),
  cmd('player.previous', {}),
  cmd('player.seek', { positionSeconds: SecondsSchema }),
  cmd('player.setVolume', { volume: VolumeSchema }),
  cmd('player.setMuted', { muted: z.boolean() }),
  cmd('player.toggleShuffle', {}),
  cmd('player.setRepeat', { mode: RepeatModeSchema }),
  cmd('track.setLike', { status: LikeStatusSchema }),
  cmd('state.get', {}),
  cmd('queue.get', {}),
  cmd('queue.play', {
    index: z.int().min(0).max(9999),
    videoId: VideoIdSchema,
  }),
  // 1.1, gated by capability (see COMMAND_REQUIREMENTS in version.ts).
  cmd('catalog.browse', {
    target: CatalogBrowseTargetSchema,
    continuation: CursorSchema.nullable(),
  }),
  cmd('catalog.open', {
    ref: RefSchema,
    continuation: CursorSchema.nullable(),
  }),
  cmd('catalog.search', {
    query: CatalogQuerySchema,
    filter: CatalogSearchFilterSchema,
    continuation: CursorSchema.nullable(),
  }),
  cmd('catalog.suggest', { query: CatalogQuerySchema }),
  cmd('catalog.play', { ref: RefSchema, action: CatalogPlayActionSchema }),
  cmd('catalog.thumb', { thumb: ThumbRefSchema }),
]);

export type Hello = z.infer<typeof HelloSchema>;
export type PairRequest = z.infer<typeof PairRequestSchema>;
export type ClientHandshake = z.infer<typeof ClientHandshakeSchema>;
export type ClientCommand = z.infer<typeof ClientCommandSchema>;
export type ClientCommandType = ClientCommand['type'];
export type CatalogBrowseTarget = z.infer<typeof CatalogBrowseTargetSchema>;
export type CatalogSearchFilter = z.infer<typeof CatalogSearchFilterSchema>;
export type CatalogPlayAction = z.infer<typeof CatalogPlayActionSchema>;
