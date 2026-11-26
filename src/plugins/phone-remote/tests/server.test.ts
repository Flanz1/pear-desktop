/**
 * Desktop integration tests against the real listener (TLS + WebSocket).
 * Every message the client receives is validated with ServerMessageSchema
 * (TestClient.invalid must stay empty).
 */
import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';

import {
  TestClient,
  client,
  connectHello,
  pairWithSecret,
  startHarness,
  v1,
  type Harness,
} from './helpers.ts';

import { CLOSE_CODES } from '../../../../protocol/src/index.ts';

describe('phone-remote listener', () => {
  let h: Harness;
  before(async () => {
    h = await startHarness();
  });
  after(async () => {
    await h.stop();
  });

  it('presents the certificate whose SPKI pin goes in the QR code', async () => {
    const c = new TestClient(h.port);
    await c.open();
    assert.equal(c.peerFingerprint, h.identity.fingerprint);
    c.ws.close();
  });

  it('answers other paths with 404', async () => {
    const c = new TestClient(h.port, '/other');
    await assert.rejects(
      new Promise((_, reject) => c.ws.on('error', reject)),
      /404/,
    );
  });

  it('rejects a too-old major with update_required / 4426', async () => {
    const c = new TestClient(h.port);
    await c.open();
    c.send({ type: 'hello', protocol: { major: 0, minor: 9 }, anything: true });
    const err = await c.waitFor('error');
    assert.equal(err.code, 'update_required');
    assert.deepEqual(err.supported, { min: 1, max: 1 });
    assert.equal(await c.waitClose(), CLOSE_CODES.versionUnsupported);
    assert.deepEqual(c.invalid, []);
  });

  it('rejects a too-new major with desktop_update_required / 4426', async () => {
    const c = new TestClient(h.port);
    await c.open();
    c.send({ type: 'pair.request', protocol: { major: 2, minor: 0 } });
    const err = await c.waitFor('error');
    assert.equal(err.code, 'desktop_update_required');
    assert.equal(await c.waitClose(), CLOSE_CODES.versionUnsupported);
  });

  it('closes 4400 on an invalid handshake', async () => {
    for (const bad of [
      'not json',
      { type: 'hello', protocol: v1 },
      { type: 'state.get', id: 'x' },
    ]) {
      const c = new TestClient(h.port);
      await c.open();
      c.send(bad);
      const err = await c.waitFor('error');
      assert.equal(err.code, 'bad_message');
      assert.equal(await c.waitClose(), CLOSE_CODES.badMessage);
    }
  });

  it('closes 4400 on a binary first frame', async () => {
    const c = new TestClient(h.port);
    await c.open();
    c.ws.send(Buffer.from('{}'), { binary: true });
    assert.equal(await c.waitClose(), CLOSE_CODES.badMessage);
  });

  it('closes 1009 on an oversize frame', async () => {
    const c = new TestClient(h.port);
    await c.open();
    c.send('x'.repeat(5000));
    assert.equal(await c.waitClose(), CLOSE_CODES.messageTooBig);
  });

  it('refuses pairing outside a window', async () => {
    const c = new TestClient(h.port);
    await c.open();
    c.send({
      type: 'pair.request',
      protocol: v1,
      client,
      device: { name: 'Pixel', model: null },
      code: '123456',
    });
    assert.equal((await c.waitFor('error')).code, 'pairing_closed');
    assert.equal(await c.waitClose(), CLOSE_CODES.unauthorized);
  });

  it('pairs with the QR secret after Allow, stores only a hash, then authenticates', async () => {
    h.answer.value = true;
    const { result } = await pairWithSecret(h);
    assert.equal(result.instanceId, '6f1c2c1e-6c1d-4c47-9a39-0e9b3c8a1d2f');
    const record = h.devices.get(result.deviceId)!;
    assert.equal(record.name, 'Pixel');
    assert.notEqual(record.tokenHash, result.token);
    assert.ok(!JSON.stringify(h.devices.list()).includes(result.token));
    assert.equal(
      h.pairing.getState().open,
      false,
      'window closes after pairing',
    );

    const c = await connectHello(h, result.deviceId, result.token);
    const welcome = await c.waitFor('welcome');
    assert.deepEqual(welcome.protocol, v1);
    assert.equal(welcome.server.name, 'pear-desktop');
    const state = await c.waitFor('state');
    assert.equal(state.id, null);
    assert.ok(h.devices.get(result.deviceId)!.lastSeenAt);

    c.send({ type: 'player.next', id: 'a1' });
    await c.waitFor('result', (m) => m.id === 'a1');
    assert.equal(h.player.executed.at(-1)?.type, 'player.next');

    c.send({ type: 'state.get', id: 's1' });
    await c.waitFor('state', (m) => m.id === 's1');

    c.send({ type: 'queue.get', id: 'q1' });
    const q = await c.waitFor('queue', (m) => m.id === 'q1');
    assert.equal(q.items.length, 1);

    c.send({ type: 'queue.play', id: 'q2', index: 0, videoId: 'AAAAAAAAAAA' });
    assert.equal(
      (await c.waitFor('error', (m) => m.id === 'q2')).code,
      'stale_queue',
    );

    h.player.emit({
      type: 'volume.changed',
      volume: { volume: 10, muted: true },
    });
    await c.waitFor('volume.changed');

    h.player.state = null;
    c.send({ type: 'player.play', id: 'nr' });
    assert.equal(
      (await c.waitFor('error', (m) => m.id === 'nr')).code,
      'not_ready',
    );
    h.player.state = (await import('./helpers.ts')).sampleState();

    // Third invalid message closes the connection.
    c.send({ type: 'player.seek', id: 'b1', positionSeconds: -1 });
    assert.equal(
      (await c.waitFor('error', (m) => m.id === 'b1')).code,
      'bad_message',
    );
    c.send({ type: 'nope', id: 'b2' });
    c.ws.send(Buffer.from('x'), { binary: true });
    assert.equal(await c.waitClose(), CLOSE_CODES.badMessage);
    assert.deepEqual(c.invalid, []);
  });

  it('rate limits commands', async () => {
    const { result } = await pairWithSecret(h);
    const c = await connectHello(h, result.deviceId, result.token);
    await c.waitFor('welcome');
    for (let i = 0; i < 30; i++) c.send({ type: 'player.play', id: `r${i}` });
    await c.waitFor('error', (m) => m.code === 'rate_limited');
    const ok = c.received.filter((m) => m.type === 'result').length;
    assert.ok(ok >= 20 && ok < 30, `accepted ${ok}`);
    c.ws.close();
  });

  it('replaces an older connection for the same device with 4409', async () => {
    const { result } = await pairWithSecret(h);
    const a = await connectHello(h, result.deviceId, result.token);
    await a.waitFor('welcome');
    const b = await connectHello(h, result.deviceId, result.token);
    await b.waitFor('welcome');
    assert.equal(await a.waitClose(), CLOSE_CODES.replaced);
    b.ws.close();
  });

  it('revoking sends server.closing and closes 4403', async () => {
    const { result } = await pairWithSecret(h);
    const c = await connectHello(h, result.deviceId, result.token);
    await c.waitFor('welcome');
    h.devices.revoke(result.deviceId);
    h.server.disconnectDevice(result.deviceId);
    assert.equal((await c.waitFor('server.closing')).reason, 'revoked');
    assert.equal(await c.waitClose(), CLOSE_CODES.revoked);
    const again = await connectHello(h, result.deviceId, result.token);
    assert.equal((await again.waitFor('error')).code, 'unauthorized');
  });

  it('denied pairing gets pairing_denied and pairs nothing', async () => {
    h.answer.value = false;
    const before = h.devices.list().length;
    const { secret } = h.pairing.open();
    const c = new TestClient(h.port);
    await c.open();
    c.send({
      type: 'pair.request',
      protocol: v1,
      client,
      device: { name: 'Evil', model: null },
      secret,
    });
    assert.equal((await c.waitFor('error')).code, 'pairing_denied');
    assert.equal(await c.waitClose(), CLOSE_CODES.unauthorized);
    assert.equal(h.devices.list().length, before);
    h.answer.value = true;
  });

  it('window timeout while the dialog is open gives pairing_timeout', async () => {
    h.answer.value = 'hang';
    const { code } = h.pairing.open();
    const c = new TestClient(h.port);
    await c.open();
    c.send({
      type: 'pair.request',
      protocol: v1,
      client,
      device: { name: 'Slow', model: null },
      code,
    });
    await new Promise((r) => setTimeout(r, 100));
    h.pairing.close('timeout');
    assert.equal((await c.waitFor('error')).code, 'pairing_timeout');
    h.answer.value = true;
  });
});

describe('phone-remote pairing attempts', () => {
  it('wrong codes give pairing_invalid and 5 of them close the window', async () => {
    const h = await startHarness();
    try {
      const { code } = h.pairing.open();
      const wrong = code === '000000' ? '111111' : '000000';
      for (let i = 0; i < 5; i++) {
        const c = new TestClient(h.port);
        await c.open();
        c.send({
          type: 'pair.request',
          protocol: v1,
          client,
          device: { name: 'Guess', model: null },
          code: wrong,
        });
        assert.equal((await c.waitFor('error')).code, 'pairing_invalid');
        assert.equal(await c.waitClose(), CLOSE_CODES.unauthorized);
      }
      assert.equal(h.pairing.getState().open, false);
    } finally {
      await h.stop();
    }
  });
});

describe('phone-remote auth rate limit', () => {
  it('refuses an address after 5 failed hellos', async () => {
    const h = await startHarness();
    try {
      for (let i = 0; i < 5; i++) {
        const c = await connectHello(
          h,
          '0d4ab8a4-5c4e-4b55-9a8e-1f0c2a3b4c5d',
          'A'.repeat(43),
        );
        assert.equal((await c.waitFor('error')).code, 'unauthorized');
        assert.equal(await c.waitClose(), CLOSE_CODES.unauthorized);
      }
      const blocked = new TestClient(h.port);
      await assert.rejects(
        new Promise((_, reject) => blocked.ws.on('error', reject)),
        /429/,
      );
    } finally {
      await h.stop();
    }
  });
});
