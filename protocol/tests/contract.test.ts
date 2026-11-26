/**
 * Desktop-side contract tests. Run: node --test protocol/tests/
 * The Android app runs the same fixtures through its own parser
 * (see protocol/README.md, "Contract tests").
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

import { renderSchemas } from '../scripts/render-schemas.ts';
import {
  ClientCommandSchema,
  ClientHandshakeSchema,
  CLOSE_CODES,
  formatPairingUri,
  HandshakeEnvelopeSchema,
  LIMITS,
  negotiate,
  parseClientCommand,
  parsePairingUri,
  parseServerMessage,
  PROTOCOL_VERSION,
  DESKTOP_SUPPORTED,
  ServerMessageSchema,
  shortFingerprint,
} from '../src/index.ts';

import type { z } from 'zod';

const fixture = (name: string): unknown =>
  JSON.parse(
    readFileSync(new URL(`../fixtures/${name}`, import.meta.url), 'utf8'),
  );

type Case = { name: string; value: unknown };

const checkFixture = (file: string, schema: z.ZodType) => {
  const { valid, invalid } = fixture(file) as {
    valid: Case[];
    invalid: Case[];
  };
  describe(file, () => {
    for (const c of valid) {
      it(`accepts: ${c.name}`, () => {
        const r = schema.safeParse(c.value);
        assert.ok(r.success, JSON.stringify(r.error?.issues));
      });
    }
    for (const c of invalid) {
      it(`rejects: ${c.name}`, () => {
        assert.equal(schema.safeParse(c.value).success, false);
      });
    }
  });
};

checkFixture('client-handshake.json', ClientHandshakeSchema);
checkFixture('handshake-envelope.json', HandshakeEnvelopeSchema);
checkFixture('client-command.json', ClientCommandSchema);
checkFixture('server-message.json', ServerMessageSchema);

describe('pairing-uri.json', () => {
  const { valid, invalid } = fixture('pairing-uri.json') as {
    valid: { name: string; uri: string; payload: unknown }[];
    invalid: { name: string; uri: string }[];
  };
  for (const c of valid) {
    it(`accepts: ${c.name}`, () => {
      const r = parsePairingUri(c.uri);
      assert.ok(r.success);
      assert.deepEqual(r.data, c.payload);
    });
    it(`round-trips: ${c.name}`, () => {
      const r = parsePairingUri(c.uri);
      assert.ok(r.success);
      const again = parsePairingUri(formatPairingUri(r.data));
      assert.ok(again.success);
      assert.deepEqual(again.data, c.payload);
    });
  }
  for (const c of invalid) {
    it(`rejects: ${c.name}`, () => {
      assert.equal(parsePairingUri(c.uri).success, false);
    });
  }
});

describe('negotiation.json', () => {
  const { cases } = fixture('negotiation.json') as {
    cases: {
      name: string;
      client: { major: number; minor: number };
      supported: Record<string, number>;
      expect: unknown;
    }[];
  };
  for (const c of cases) {
    it(c.name, () => {
      const supported = Object.fromEntries(
        Object.entries(c.supported).map(([k, v]) => [Number(k), v]),
      );
      assert.deepEqual(negotiate(c.client, supported), c.expect);
    });
  }
  it('current version is in the supported table', () => {
    assert.equal(
      DESKTOP_SUPPORTED[PROTOCOL_VERSION.major],
      PROTOCOL_VERSION.minor,
    );
  });
});

describe('raw parsing', () => {
  it('rejects non-JSON', () => {
    assert.deepEqual(parseClientCommand('{"type":'), {
      ok: false,
      reason: 'not_json',
    });
  });
  it('rejects invalid UTF-8 bytes', () => {
    assert.deepEqual(parseClientCommand(new Uint8Array([0x7b, 0xff, 0x7d])), {
      ok: false,
      reason: 'not_json',
    });
  });
  it('rejects client messages over the size limit before parsing', () => {
    const big = JSON.stringify({
      type: 'player.play',
      id: 'r1',
      pad: 'x'.repeat(LIMITS.clientMessageBytes),
    });
    assert.deepEqual(parseClientCommand(big), {
      ok: false,
      reason: 'too_large',
    });
  });
  it('counts bytes, not characters', () => {
    // 1,400 three-byte characters: under the limit in characters, over it in bytes.
    const pad = '♪'.repeat(1400);
    const msg = `{"type":"player.play","id":"r1","p":"${pad}"}`;
    assert.ok(msg.length < LIMITS.clientMessageBytes);
    assert.deepEqual(parseClientCommand(msg), {
      ok: false,
      reason: 'too_large',
    });
  });
  it('accepts a valid command', () => {
    const r = parseClientCommand(
      '{"type":"player.seek","id":"a","positionSeconds":3}',
    );
    assert.ok(r.ok);
    assert.equal(r.value.type, 'player.seek');
  });
  it('a full 500-item queue fits in one server message', () => {
    const item = {
      index: 9999,
      videoId: 'dQw4w9WgXcQ',
      title: 'x'.repeat(LIMITS.textMax),
      artist: 'x'.repeat(LIMITS.textMax),
      durationSeconds: 86400,
      thumbnailUrl: 'https://lh3.googleusercontent.com/' + 'a'.repeat(200),
      isCurrent: false,
    };
    const msg = JSON.stringify({
      type: 'queue',
      id: 'r1',
      items: Array.from({ length: LIMITS.queueMaxItems }, () => item),
      truncated: true,
    });
    assert.ok(parseServerMessage(msg).ok);
  });
});

describe('misc', () => {
  it('short fingerprint format', () => {
    assert.equal(
      shortFingerprint('n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sbv-ixJM'),
      '9F86-D081-884C-7D65',
    );
  });
  it('close codes are unique', () => {
    const values = Object.values(CLOSE_CODES);
    assert.equal(new Set(values).size, values.length);
  });
  it('committed JSON Schema files are up to date', () => {
    for (const [file, content] of Object.entries(renderSchemas())) {
      const committed = readFileSync(
        new URL(`../schema/${file}`, import.meta.url),
        'utf8',
      );
      // Compare as JSON so formatter whitespace changes don't matter.
      assert.deepEqual(
        JSON.parse(committed),
        JSON.parse(content),
        `schema/${file} is stale: run node protocol/scripts/generate-schema.ts`,
      );
    }
  });
});
