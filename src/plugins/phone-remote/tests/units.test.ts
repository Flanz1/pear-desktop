import assert from 'node:assert/strict';
import { X509Certificate } from 'node:crypto';
import { describe, it } from 'node:test';

import { plainProtector } from './helpers.ts';

import { ServerMessageSchema } from '../../../../protocol/src/index.ts';
import { DeviceStore } from '../backend/devices.ts';
import { loadOrCreateIdentity } from '../backend/identity.ts';
import { PairingController } from '../backend/pairing.ts';
import { PlayerStateTracker, sanitizeTrack } from '../backend/player-state.ts';
import { FailureLimiter, TokenBucket } from '../backend/rate-limit.ts';

describe('DeviceStore', () => {
  it('stores a SHA-256 hash, verifies in constant time, revokes', () => {
    let saved: unknown = null;
    const store = new DeviceStore([], (d) => (saved = d));
    const { record, token } = store.create('Pixel', null);
    assert.match(record.tokenHash, /^[0-9a-f]{64}$/);
    assert.ok(!JSON.stringify(saved).includes(token));
    assert.ok(store.verify(record.deviceId, token));
    assert.equal(store.verify(record.deviceId, 'B'.repeat(43)), null);
    assert.equal(
      store.verify('00000000-0000-4000-8000-000000000000', token),
      null,
    );
    assert.ok(store.revoke(record.deviceId));
    assert.equal(store.verify(record.deviceId, token), null);
  });
});

describe('TLS identity', () => {
  it('creates a P-256 self-signed cert, reloads it and reissues it with the same key', async () => {
    const first = await loadOrCreateIdentity(null, plainProtector);
    const cert = new X509Certificate(first.identity.certPem);
    assert.equal(cert.subject, 'CN=Pear Remote');
    assert.equal(cert.publicKey.asymmetricKeyDetails?.namedCurve, 'prime256v1');
    assert.ok(cert.verify(cert.publicKey));
    const years = (Date.parse(cert.validTo) - Date.now()) / (365 * 864e5);
    assert.ok(years > 9.9 && years < 10.1);
    assert.match(first.identity.fingerprint, /^[A-Za-z0-9_-]{43}$/);

    const again = await loadOrCreateIdentity(first.stored, plainProtector);
    assert.equal(again.stored, null);
    assert.equal(again.identity.fingerprint, first.identity.fingerprint);

    const almostTenYears = 9.95 * 365 * 864e5;
    const later = new Date(Date.now() + almostTenYears);
    const reissued = await loadOrCreateIdentity(
      first.stored,
      plainProtector,
      later,
    );
    assert.ok(reissued.stored);
    assert.notEqual(reissued.identity.certPem, first.identity.certPem);
    assert.equal(reissued.identity.fingerprint, first.identity.fingerprint);
  });

  it('encrypts the key when OS protection is available', async () => {
    const xor = (b: Buffer) => Buffer.from(b.map((x) => x ^ 0x5a));
    const protector = {
      available: () => true,
      encrypt: (s: string) => xor(Buffer.from(s)),
      decrypt: (b: Buffer) => xor(b).toString(),
    };
    const { stored, identity } = await loadOrCreateIdentity(null, protector);
    assert.equal(stored!.encrypted, true);
    assert.ok(
      !Buffer.from(stored!.key, 'base64').toString().includes('PRIVATE KEY'),
    );
    const reloaded = await loadOrCreateIdentity(stored, protector);
    assert.equal(reloaded.identity.keyPem, identity.keyPem);
  });
});

describe('PairingController', () => {
  it('uses fresh single-use credentials and closes on timeout', async () => {
    const p = new PairingController(async () => true, { windowMs: 50 });
    const a = p.open();
    assert.match(a.secret, /^[A-Za-z0-9_-]{22}$/);
    assert.match(a.code, /^[0-9]{6}$/);
    const b = p.open();
    assert.notEqual(a.secret, b.secret);
    assert.equal(p.attempt({ secret: a.secret }), 'invalid');
    await new Promise((r) => setTimeout(r, 80));
    assert.deepEqual(p.getState(), { open: false, reason: 'timeout' });
    assert.equal(p.attempt({ secret: b.secret }), 'closed');
  });

  it('blocks other attempts while the dialog is open and closes after Deny', async () => {
    let release: (v: boolean) => void = () => {};
    const p = new PairingController(
      () => new Promise<boolean>((r) => (release = r)),
    );
    const { code } = p.open();
    assert.equal(p.attempt({ code }), 'accepted');
    const pending = p.confirm(
      { name: 'x', model: null, address: '192.168.1.2' },
      new AbortController().signal,
    );
    assert.equal(p.attempt({ code }), 'closed');
    release(false);
    assert.equal(await pending, 'denied');
    assert.equal(p.getState().open, false);
    p.close('cancelled');
  });
});

describe('PlayerStateTracker', () => {
  const page = {
    status: 'playing' as const,
    positionSeconds: 10,
    volume: 50,
    muted: false,
    repeat: 'NONE' as const,
    shuffle: false,
    like: 'INDIFFERENT' as const,
    queueRevision: 0,
  };

  it('emits only real changes, all schema-valid', async () => {
    let now = 0;
    const out: { type: string }[] = [];
    const t = new PlayerStateTracker(
      (m) => {
        assert.ok(ServerMessageSchema.safeParse(m).success, JSON.stringify(m));
        out.push(m);
      },
      () => now,
    );
    t.setPage(page);
    assert.equal(out.length, 0, 'first snapshot is delivered through `state`');
    now = 1000;
    t.setPage({ ...page, positionSeconds: 11 }); // normal progress
    assert.equal(out.length, 0);
    now = 2000;
    t.setPage({ ...page, positionSeconds: 60 }); // seek
    assert.deepEqual(
      out.map((m) => m.type),
      ['playback.changed'],
    );
    now = 3000;
    t.setPage({
      ...page,
      positionSeconds: 61,
      like: 'LIKE',
      repeat: 'ALL',
      queueRevision: 1,
    });
    assert.deepEqual(
      out
        .slice(1)
        .map((m) => m.type)
        .sort(),
      ['like.changed', 'queue.changed', 'repeat.changed'],
    );
    t.dispose();
  });

  it('coalesces bursts to at most 10 per second per type', async () => {
    const out: unknown[] = [];
    const t = new PlayerStateTracker((m) => out.push(m));
    t.setPage(page);
    for (let v = 0; v <= 100; v += 5) t.setPage({ ...page, volume: v });
    await new Promise((r) => setTimeout(r, 150));
    assert.ok(out.length <= 3, `got ${out.length}`);
    assert.deepEqual(out.at(-1), {
      type: 'volume.changed',
      volume: { volume: 100, muted: false },
    });
    t.dispose();
  });

  it('sanitises tracks from the page', () => {
    assert.equal(
      sanitizeTrack({ videoId: 'bad', title: 't', artist: 'a' }),
      null,
    );
    const track = sanitizeTrack({
      videoId: 'dQw4w9WgXcQ',
      title: 'x'.repeat(900),
      artist: 'a',
      songDuration: 0,
      imageSrc: 'http://insecure/x.jpg',
    })!;
    assert.equal(track.title.length, 500);
    assert.equal(track.durationSeconds, null);
    assert.equal(track.thumbnailUrl, null);
  });
});

describe('rate limits', () => {
  it('token bucket: burst 20, then limited, abusive after 10 s', () => {
    let now = 0;
    const b = new TokenBucket(() => now);
    for (let i = 0; i < 20; i++) assert.equal(b.take(), 'ok');
    assert.equal(b.take(), 'limited');
    now = 100;
    assert.equal(b.take(), 'ok'); // refilled one
    assert.equal(b.take(), 'limited');
    now = 10_100;
    // 10 s later the bucket is full again: back to ok.
    assert.equal(b.take(), 'ok');
  });

  it('failure limiter blocks for 60 s after 5 failures', () => {
    let now = 0;
    const f = new FailureLimiter(() => now);
    for (let i = 0; i < 4; i++) f.recordFailure('192.168.1.9');
    assert.equal(f.isBlocked('192.168.1.9'), false);
    f.recordFailure('192.168.1.9');
    assert.equal(f.isBlocked('192.168.1.9'), true);
    now = 60_001;
    assert.equal(f.isBlocked('192.168.1.9'), false);
  });
});
