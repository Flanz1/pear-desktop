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
  CAPABILITIES,
  COMMAND_REQUIREMENTS,
  isCommandAvailable,
  TrackSchema,
  QueueItemSchema,
  CatalogPageSchema,
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

describe('catalog (1.1)', () => {
  const ref = 'Q2F0YWxvZ1JlZjAwMDAx';
  const track = {
    videoId: 'dQw4w9WgXcQ',
    title: 't',
    artist: 'a',
    album: null,
    durationSeconds: null,
    thumbnailUrl: null,
  };
  it('Track accepts 1.0 shape (no thumb) and 1.1 shape (thumb ref or null)', () => {
    assert.ok(TrackSchema.safeParse(track).success);
    assert.ok(TrackSchema.safeParse({ ...track, thumb: null }).success);
    assert.ok(TrackSchema.safeParse({ ...track, thumb: ref }).success);
    assert.equal(
      TrackSchema.safeParse({ ...track, thumb: 'not a ref' }).success,
      false,
    );
  });
  it('QueueItem accepts both shapes', () => {
    const item = {
      index: 0,
      videoId: 'dQw4w9WgXcQ',
      title: 't',
      artist: 'a',
      durationSeconds: null,
      thumbnailUrl: null,
      isCurrent: false,
    };
    assert.ok(QueueItemSchema.safeParse(item).success);
    assert.ok(QueueItemSchema.safeParse({ ...item, thumb: ref }).success);
  });
  it('every catalog command has a requirement, every requirement a command', () => {
    const catalogTypes = ClientCommandSchema.options
      .map((o) => o.shape.type.value as string)
      .filter((t) => t.startsWith('catalog.'));
    assert.deepEqual(
      [...catalogTypes].sort(),
      Object.keys(COMMAND_REQUIREMENTS).sort(),
    );
  });
  it('gates catalog commands by negotiated minor and capability', () => {
    const all = Object.values(CAPABILITIES);
    assert.equal(
      isCommandAvailable('catalog.browse', { major: 1, minor: 0 }, all),
      false,
    );
    assert.equal(
      isCommandAvailable('catalog.browse', { major: 1, minor: 1 }, []),
      false,
    );
    assert.equal(
      isCommandAvailable('catalog.browse', { major: 1, minor: 1 }, all),
      true,
    );
    assert.equal(
      isCommandAvailable('catalog.thumb', { major: 1, minor: 1 }, [
        CAPABILITIES.catalogBrowse,
      ]),
      false,
    );
    // 1.0 commands are always available.
    assert.equal(
      isCommandAvailable('player.play', { major: 1, minor: 0 }, []),
      true,
    );
  });
  it('trims the search query before checking it', () => {
    const r = ClientCommandSchema.safeParse({
      type: 'catalog.search',
      id: 'a',
      query: '  rick  ',
      filter: 'top',
      continuation: null,
    });
    assert.ok(r.success);
    assert.equal((r.data as { query: string }).query, 'rick');
  });
  it('a worst-case search command fits the client message limit', () => {
    // 150 code units that each take 6 bytes if the encoder escapes them (\uXXXX).
    const query = '\\u00e9'.repeat(LIMITS.catalogQueryMax);
    const msg = JSON.stringify({
      type: 'catalog.search',
      id: 'a'.repeat(64),
      query: 'é'.repeat(LIMITS.catalogQueryMax),
      filter: 'playlists',
      continuation: 'A'.repeat(LIMITS.refMaxLength),
    }).replace('é'.repeat(LIMITS.catalogQueryMax), query);
    assert.ok(Buffer.byteLength(msg) < LIMITS.clientMessageBytes);
    assert.ok(parseClientCommand(msg).ok);
  });
  it('a max-size thumbnail fits the server message limit', () => {
    const data = Buffer.alloc(LIMITS.thumbMaxBytes).toString('base64');
    const msg = JSON.stringify({
      type: 'catalog.thumb',
      id: 'a'.repeat(64),
      mime: 'image/jpeg',
      data,
    });
    assert.ok(parseServerMessage(msg).ok);
    const over = Buffer.alloc(LIMITS.thumbMaxBytes + 1).toString('base64');
    assert.equal(
      ServerMessageSchema.safeParse({
        type: 'catalog.thumb',
        id: 'a',
        mime: 'image/jpeg',
        data: over,
      }).success,
      false,
    );
  });
  const pageWith = (sections: number, items: number, textLen: number) => ({
    type: 'catalog.page',
    id: 'a'.repeat(64),
    title: 'x'.repeat(textLen),
    subtitle: null,
    header: null,
    chips: [],
    sections: Array.from({ length: sections }, () => ({
      title: 'x'.repeat(textLen),
      layout: 'carousel',
      items: Array.from({ length: items }, () => ({
        ref: 'A'.repeat(LIMITS.refMaxLength),
        kind: 'playlist',
        title: 'x'.repeat(textLen),
        subtitle: 'x'.repeat(textLen),
        durationSeconds: 86400,
        explicit: true,
        actions: ['open', 'play', 'shuffle', 'playNext', 'addToQueue'],
        thumb: 'A'.repeat(LIMITS.refMaxLength),
        isCurrent: false,
      })),
    })),
    append: null,
    continuation: 'A'.repeat(LIMITS.refMaxLength),
  });
  it('a large realistic page (30 shelves x 25 items, 100-char text) fits', () => {
    const msg = JSON.stringify(pageWith(30, 25, 100));
    assert.ok(parseServerMessage(msg).ok);
  });
  it('a page at every count limit with max-length text exceeds 1 MiB, so the desktop must trim', () => {
    const page = pageWith(
      LIMITS.catalogSectionsMax,
      LIMITS.catalogItemsMax,
      LIMITS.textMax,
    );
    assert.ok(CatalogPageSchema.safeParse(page).success);
    assert.deepEqual(parseServerMessage(JSON.stringify(page)), {
      ok: false,
      reason: 'too_large',
    });
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
