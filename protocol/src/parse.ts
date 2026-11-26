import {
  ClientCommandSchema,
  ClientHandshakeSchema,
  HandshakeEnvelopeSchema,
} from './client.ts';
import { LIMITS } from './limits.ts';
import { ServerMessageSchema } from './server.ts';

import type { z } from 'zod';

export type ParseResult<T> =
  | { ok: true; value: T }
  | { ok: false; reason: 'too_large' | 'not_json' | 'invalid' };

const byteLength = (raw: string | Uint8Array) =>
  typeof raw === 'string' ? Buffer.byteLength(raw, 'utf8') : raw.byteLength;

const parseWith =
  <S extends z.ZodType>(schema: S, maxBytes: number) =>
  (raw: string | Uint8Array): ParseResult<z.infer<S>> => {
    if (byteLength(raw) > maxBytes) return { ok: false, reason: 'too_large' };
    let json: unknown;
    try {
      json = JSON.parse(
        typeof raw === 'string'
          ? raw
          : new TextDecoder('utf-8', { fatal: true }).decode(raw),
      );
    } catch {
      return { ok: false, reason: 'not_json' };
    }
    const result = schema.safeParse(json);
    return result.success
      ? { ok: true, value: result.data }
      : { ok: false, reason: 'invalid' };
  };

/** Step 1 on the desktop: read only the version from the first message. */
export const parseHandshakeEnvelope = parseWith(
  HandshakeEnvelopeSchema,
  LIMITS.clientMessageBytes,
);
/** Step 2 on the desktop: full validation of the first message, once the version is supported. */
export const parseClientHandshake = parseWith(
  ClientHandshakeSchema,
  LIMITS.clientMessageBytes,
);
/** Every message after `welcome`. */
export const parseClientCommand = parseWith(
  ClientCommandSchema,
  LIMITS.clientMessageBytes,
);
/** Reference parser for clients (the Android app implements the equivalent). */
export const parseServerMessage = parseWith(
  ServerMessageSchema,
  LIMITS.serverMessageBytes,
);
