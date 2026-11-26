import { z } from 'zod';

import {
  AppInfoSchema,
  ErrorCodeSchema,
  LikeStatusSchema,
  PlaybackStatusSchema,
  RepeatModeSchema,
  RequestIdSchema,
  SecondsSchema,
  TextSchema,
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
});

export const WelcomeSchema = z.strictObject({
  type: z.literal('welcome'),
  /** The negotiated version. Clients must not send anything newer. */
  protocol: VersionSchema,
  server: AppInfoSchema,
  instanceId: UuidSchema,
  /** Desktop display name, user-editable. */
  name: TextSchema,
  /** Optional features beyond the base protocol for this version. Empty in 1.0. */
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
]);

export type Welcome = z.infer<typeof WelcomeSchema>;
export type ErrorMessage = z.infer<typeof ErrorMessageSchema>;
export type PlayerState = z.infer<typeof PlayerStateSchema>;
export type ServerMessage = z.infer<typeof ServerMessageSchema>;
export type ServerMessageType = ServerMessage['type'];
