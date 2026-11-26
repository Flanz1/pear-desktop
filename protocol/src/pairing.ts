import { isIP } from 'node:net';

import { z } from 'zod';

import {
  PairingSecretSchema,
  SpkiFingerprintSchema,
  TextSchema,
  UuidSchema,
} from './common.ts';

/**
 * Contents of the pairing QR code, encoded as
 *   pear-remote://pair?v=1&h=<ip>&p=<port>&fp=<spki>&s=<secret>&i=<instanceId>&n=<name>
 * `v` lists the protocol majors the desktop accepts, comma-separated.
 */
export const PAIRING_URI_PREFIX = 'pear-remote://pair?';

/**
 * True for addresses the listener may serve: RFC 1918, IPv4 link-local,
 * IPv6 unique-local (fc00::/7) and link-local (fe80::/10). IPv4-mapped IPv6
 * (::ffff:a.b.c.d) is checked as IPv4. Everything else, including loopback,
 * is refused.
 */
export const isLanAddress = (address: string): boolean => {
  const a = address.toLowerCase().replace(/^::ffff:(?=\d+\.)/, '');
  const family = isIP(a.replace(/%[0-9a-z._-]+$/, ''));
  if (family === 4) {
    const [p0, p1] = a.split('.').map(Number);
    return (
      p0 === 10 ||
      (p0 === 172 && p1 >= 16 && p1 <= 31) ||
      (p0 === 192 && p1 === 168) ||
      (p0 === 169 && p1 === 254)
    );
  }
  if (family === 6) {
    return /^f[cd][0-9a-f]{2}:/.test(a) || /^fe[89ab][0-9a-f]:/.test(a);
  }
  return false;
};

export const LanAddressSchema = z
  .string()
  .max(64)
  .regex(/^[0-9A-Fa-f.:]+$/, 'IP literal without zone ID')
  .refine(isLanAddress, 'must be a private LAN address');

export const PairingPayloadSchema = z.strictObject({
  majors: z.array(z.int().min(0).max(999)).min(1).max(8),
  host: LanAddressSchema,
  port: z.int().min(1024).max(65535),
  fingerprint: SpkiFingerprintSchema,
  secret: PairingSecretSchema,
  instanceId: UuidSchema,
  name: TextSchema,
});

export type PairingPayload = z.infer<typeof PairingPayloadSchema>;

const KEYS = ['v', 'h', 'p', 'fp', 's', 'i', 'n'] as const;

export const parsePairingUri = (
  uri: string,
): ReturnType<typeof PairingPayloadSchema.safeParse> | { success: false } => {
  if (uri.length > 1024 || !uri.startsWith(PAIRING_URI_PREFIX)) {
    return { success: false };
  }
  let params: URLSearchParams;
  try {
    params = new URLSearchParams(uri.slice(PAIRING_URI_PREFIX.length));
  } catch {
    return { success: false };
  }
  const seen = [...params.keys()];
  if (
    seen.length !== KEYS.length ||
    new Set(seen).size !== seen.length ||
    !seen.every((k) => (KEYS as readonly string[]).includes(k))
  ) {
    return { success: false };
  }
  const v = params.get('v')!;
  const p = params.get('p')!;
  if (!/^[0-9]{1,3}(,[0-9]{1,3}){0,7}$/.test(v) || !/^[0-9]{1,5}$/.test(p)) {
    return { success: false };
  }
  return PairingPayloadSchema.safeParse({
    majors: v.split(',').map(Number),
    host: params.get('h'),
    port: Number(p),
    fingerprint: params.get('fp'),
    secret: params.get('s'),
    instanceId: params.get('i'),
    name: params.get('n'),
  });
};

export const formatPairingUri = (payload: PairingPayload): string => {
  const params = new URLSearchParams({
    v: payload.majors.join(','),
    h: payload.host,
    p: String(payload.port),
    fp: payload.fingerprint,
    s: payload.secret,
    i: payload.instanceId,
    n: payload.name,
  });
  return PAIRING_URI_PREFIX + params.toString();
};

/**
 * Short form of the fingerprint, shown on both screens for the manual-code
 * path: the first 16 hex characters of the SPKI SHA-256, in groups of four.
 */
export const shortFingerprint = (fingerprint: string): string => {
  const hex = Buffer.from(fingerprint, 'base64url')
    .subarray(0, 8)
    .toString('hex')
    .toUpperCase();
  return hex.match(/.{4}/g)!.join('-');
};
