import { z } from 'zod';

import {
  AppInfoSchema,
  DeviceNameSchema,
  LikeStatusSchema,
  PairingCodeSchema,
  PairingSecretSchema,
  RepeatModeSchema,
  RequestIdSchema,
  SecondsSchema,
  TokenSchema,
  UuidSchema,
  VersionSchema,
  VideoIdSchema,
  VolumeSchema,
} from './common.ts';

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
]);

export type Hello = z.infer<typeof HelloSchema>;
export type PairRequest = z.infer<typeof PairRequestSchema>;
export type ClientHandshake = z.infer<typeof ClientHandshakeSchema>;
export type ClientCommand = z.infer<typeof ClientCommandSchema>;
export type ClientCommandType = ClientCommand['type'];
