import { z } from 'zod';

import { LIMITS } from './limits.ts';

/** 32 random bytes, base64url without padding. */
export const TokenSchema = z.string().regex(/^[A-Za-z0-9_-]{43}$/);

/** SHA-256 of the certificate's SubjectPublicKeyInfo, base64url without padding. */
export const SpkiFingerprintSchema = z.string().regex(/^[A-Za-z0-9_-]{43}$/);

/** 16 random bytes, base64url without padding. Used once, in the QR code. */
export const PairingSecretSchema = z.string().regex(/^[A-Za-z0-9_-]{22}$/);

/** Six decimal digits, for manual pairing. */
export const PairingCodeSchema = z.string().regex(/^[0-9]{6}$/);

/** Desktop-assigned device ID / desktop instance ID. */
export const UuidSchema = z.uuid();

/** Client-chosen request ID, echoed in the matching result or error. */
export const RequestIdSchema = z.string().regex(/^[A-Za-z0-9_-]{1,64}$/);

/** YouTube video ID. */
export const VideoIdSchema = z.string().regex(/^[A-Za-z0-9_-]{11}$/);

/**
 * Catalog (1.1): opaque reference issued by the desktop, base64url, 16-32
 * chars (at least 96 random bits). The desktop maps it, for one paired device
 * only, to a YouTube endpoint, continuation token or image URL. Phones never
 * build or modify refs; they only send back ones they received.
 */
export const RefSchema = z
  .string()
  .regex(
    new RegExp(
      `^[A-Za-z0-9_-]{${LIMITS.refMinLength},${LIMITS.refMaxLength}}$`,
    ),
  );
/** Catalog (1.1): opaque "load more" cursor. Same shape as a ref. */
export const CursorSchema = RefSchema;
/** Catalog (1.1): opaque thumbnail ref, fetched with `catalog.thumb`. Same shape as a ref. */
export const ThumbRefSchema = RefSchema;

export const TextSchema = z.string().max(LIMITS.textMax);
export const DeviceNameSchema = z
  .string()
  .trim()
  .min(1)
  .max(LIMITS.deviceNameMax);

export const HttpsUrlSchema = z.url({ protocol: /^https$/ }).max(2048);

export const VersionSchema = z.strictObject({
  major: z.int().min(0).max(999),
  minor: z.int().min(0).max(999),
});

export const AppInfoSchema = z.strictObject({
  /** e.g. "pear-remote-android" or "pear-desktop". */
  name: z.string().regex(/^[a-z0-9-]{1,64}$/),
  /** App version string, informational only. */
  version: z.string().regex(/^[0-9A-Za-z.+-]{1,32}$/),
});

export const RepeatModeSchema = z.enum(['NONE', 'ALL', 'ONE']);
export const LikeStatusSchema = z.enum(['LIKE', 'DISLIKE', 'INDIFFERENT']);
export const PlaybackStatusSchema = z.enum([
  'playing',
  'paused',
  'buffering',
  'stopped',
]);

export const SecondsSchema = z
  .number()
  .min(0)
  .max(24 * 60 * 60);
export const VolumeSchema = z.int().min(0).max(100);

export const TrackSchema = z.strictObject({
  videoId: VideoIdSchema,
  title: TextSchema,
  artist: TextSchema,
  album: TextSchema.nullable(),
  durationSeconds: SecondsSchema.nullable(),
  thumbnailUrl: HttpsUrlSchema.nullable(),
  /**
   * 1.1: present (ref or null) when the negotiated minor is >= 1, absent in
   * 1.0 sessions, because 1.0 parsers reject unknown keys.
   */
  thumb: ThumbRefSchema.nullable().optional(),
});

export const ErrorCodeSchema = z.enum([
  'bad_message',
  'unknown_type',
  'unauthorized',
  'revoked',
  'update_required',
  'desktop_update_required',
  'pairing_closed',
  'pairing_invalid',
  'pairing_denied',
  'pairing_timeout',
  'rate_limited',
  'not_ready',
  'stale_queue',
  'internal',
  /** 1.1, catalog commands only: ref or cursor unknown, expired, or not valid for this action. */
  'not_found',
  /** 1.1, catalog commands only: needs a signed-in YouTube Music account on the desktop. */
  'signed_out',
  /** 1.1, catalog commands only: YouTube failed or returned something the desktop can't read. */
  'unavailable',
]);

export type RepeatMode = z.infer<typeof RepeatModeSchema>;
export type LikeStatus = z.infer<typeof LikeStatusSchema>;
export type PlaybackStatus = z.infer<typeof PlaybackStatusSchema>;
export type Track = z.infer<typeof TrackSchema>;
export type ErrorCode = z.infer<typeof ErrorCodeSchema>;
