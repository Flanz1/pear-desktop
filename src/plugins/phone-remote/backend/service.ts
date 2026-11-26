import { randomUUID } from 'node:crypto';
import { hostname } from 'node:os';
import { join } from 'node:path';

import { app, safeStorage, type BrowserWindow } from 'electron';

import { t } from '@/i18n';
import { LoggerPrefix } from '@/utils';

import { DeviceStore, type DeviceRecord } from './devices.ts';
import {
  loadOrCreateIdentity,
  spkiFingerprint,
  type KeyProtector,
  type TlsIdentity,
} from './identity.ts';
import { MdnsAdvertiser } from './mdns.ts';
import { pickBindAddress } from './network.ts';
import { confirmPairingDialog, PairingWindow } from './pairing-window.ts';
import { PairingController } from './pairing.ts';
import { PlayerBridge } from './player-bridge.ts';
import { RemoteServer } from './server.ts';
import { StateFile, type RemoteState } from './storage.ts';

import {
  DESKTOP_SUPPORTED,
  formatPairingUri,
  shortFingerprint,
} from '../../../../protocol/src/index.ts';

import type { PhoneRemoteConfig } from '../config';

export type ServiceStatus =
  | { kind: 'stopped' }
  | { kind: 'starting' }
  | { kind: 'running'; host: string; port: number; fingerprint: string }
  | { kind: 'no_lan' }
  | { kind: 'error'; message: string };

const protector: KeyProtector = {
  available: () => safeStorage.isEncryptionAvailable(),
  encrypt: (s) => safeStorage.encryptString(s),
  decrypt: (b) => safeStorage.decryptString(b),
};

const log = {
  info: (...a: unknown[]) => console.log(LoggerPrefix, ...a),
  warn: (...a: unknown[]) => console.warn(LoggerPrefix, ...a),
};

/**
 * Owns the listener and everything around it. The backend lifecycle starts
 * and stops it; the menu calls into it.
 */
class PhoneRemoteService {
  status: ServiceStatus = { kind: 'stopped' };
  private win: BrowserWindow | null = null;
  private stateFile: StateFile | null = null;
  private state: RemoteState | null = null;
  private devices: DeviceStore | null = null;
  private identity: TlsIdentity | null = null;
  private server: RemoteServer | null = null;
  private player: PlayerBridge | null = null;
  private pairing: PairingController | null = null;
  private readonly pairingWindow = new PairingWindow();
  private readonly mdns = new MdnsAdvertiser();
  private readonly listeners = new Set<() => void>();
  private quitHooked = false;
  /** Serialises start/stop. */
  private chain: Promise<void> = Promise.resolve();

  onChange(listener: () => void) {
    this.listeners.add(listener);
  }

  start(win: BrowserWindow, config: PhoneRemoteConfig) {
    return (this.chain = this.chain.then(() => this.doStart(win, config)));
  }

  stop(reason: 'disabled' | 'shutdown' = 'disabled') {
    return (this.chain = this.chain.then(() => this.doStop(reason)));
  }

  listDevices(): readonly DeviceRecord[] {
    return this.devices?.list() ?? this.loadState().devices;
  }

  isConnected(deviceId: string) {
    return this.server?.connectedDeviceIds().has(deviceId) ?? false;
  }

  revoke(deviceId: string) {
    const devices =
      this.devices ??
      new DeviceStore(this.loadState().devices, (d) => this.saveDevices(d));
    if (devices.revoke(deviceId)) this.server?.disconnectDevice(deviceId);
    this.emit();
  }

  async openPairing() {
    const { server, pairing, identity, win } = this;
    if (
      !server ||
      !pairing ||
      !identity ||
      !win ||
      this.status.kind !== 'running'
    ) {
      return;
    }
    const credentials = pairing.open();
    const { host, port } = this.status;
    const name = this.desktopName();
    await this.pairingWindow.show(
      win,
      {
        uri: formatPairingUri({
          majors: Object.keys(DESKTOP_SUPPORTED).map(Number),
          host,
          port,
          fingerprint: identity.fingerprint,
          secret: credentials.secret,
          instanceId: this.state!.instanceId,
          // Keep the QR small; the full name arrives in `welcome`.
          name: name.slice(0, 32),
        }),
        code: credentials.code,
        shortFingerprint: shortFingerprint(identity.fingerprint),
        host,
        port,
        expiresAt: credentials.expiresAt,
      },
      () => pairing.close('cancelled'),
    );
  }

  /** "Reset remote identity": new key, every pairing removed (SPEC 2.1). */
  async resetIdentity(config: PhoneRemoteConfig) {
    const wasRunning = this.server !== null;
    await this.stop('disabled');
    const state = this.loadState();
    state.identity = null;
    state.devices = [];
    this.stateFile!.save(state);
    this.state = state;
    if (wasRunning && this.win) await this.start(this.win, config);
    this.emit();
  }

  // ---- internals ----

  private desktopName() {
    return hostname() || 'Pear Desktop';
  }

  private loadState(): RemoteState {
    if (!this.stateFile) {
      this.stateFile = new StateFile(
        join(app.getPath('userData'), 'phone-remote', 'state.json'),
      );
    }
    this.state ??= this.stateFile.load();
    return this.state;
  }

  private saveDevices(devices: DeviceRecord[]) {
    const state = this.loadState();
    state.devices = devices;
    this.stateFile!.save(state);
  }

  private emit() {
    for (const l of this.listeners) {
      try {
        l();
      } catch {
        /* menu refresh failures are not ours to handle */
      }
    }
  }

  private async doStart(win: BrowserWindow, config: PhoneRemoteConfig) {
    if (this.server) await this.doStop('disabled');
    this.win = win;
    this.status = { kind: 'starting' };
    this.hookQuit();
    try {
      const state = this.loadState();
      let dirty = false;
      if (!state.instanceId) {
        state.instanceId = randomUUID();
        dirty = true;
      }
      const hadIdentity = state.identity !== null;
      const { identity, stored } = await loadOrCreateIdentity(
        state.identity,
        protector,
      );
      if (stored) {
        const previous = state.identity?.cert;
        state.identity = stored;
        dirty = true;
        // A brand-new key (old one unreadable) invalidates every pairing.
        if (
          hadIdentity &&
          previous &&
          spkiFingerprintOrNull(previous) !== identity.fingerprint
        ) {
          state.devices = [];
        }
      }
      if (dirty) this.stateFile!.save(state);
      this.identity = identity;

      const host = pickBindAddress(config.bindAddress);
      if (!host) {
        this.status = { kind: 'no_lan' };
        log.warn(
          'phone-remote: no private LAN address found, listener not started',
        );
        return;
      }

      this.devices = new DeviceStore(state.devices, (d) => this.saveDevices(d));
      this.player = new PlayerBridge(win);
      this.player.start();
      this.pairing = new PairingController((req, signal) =>
        confirmPairingDialog(win, req, signal),
      );
      this.pairing.onChange((s) => {
        if (!s.open) this.pairingWindow.close();
        else if (s.awaitingConfirmation) {
          this.pairingWindow.setStatus(
            t('plugins.phone-remote.pairing.waiting'),
          );
        }
        this.emit();
      });

      this.server = new RemoteServer({
        host,
        port: config.port,
        key: identity.keyPem,
        cert: identity.certPem,
        instanceId: state.instanceId,
        name: this.desktopName(),
        appVersion: app.getVersion(),
        devices: this.devices,
        pairing: this.pairing,
        player: this.player,
        onDevicesChanged: () => this.emit(),
        logger: log,
      });
      await this.server.start();
      const port = this.server.address?.port ?? config.port;
      this.status = {
        kind: 'running',
        host,
        port,
        fingerprint: identity.fingerprint,
      };
      this.mdns.start({
        bindAddress: host,
        port,
        instanceId: state.instanceId,
        fingerprint: identity.fingerprint,
        name: this.desktopName(),
        onError: (err) => log.warn('phone-remote: mDNS error', String(err)),
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.warn('phone-remote: failed to start:', message);
      await this.doStop('disabled');
      this.status = { kind: 'error', message };
    } finally {
      this.emit();
    }
  }

  private async doStop(reason: 'disabled' | 'shutdown') {
    this.mdns.stop();
    this.pairing?.close('disabled');
    this.pairingWindow.close();
    const server = this.server;
    this.server = null;
    await server?.stop(reason).catch(() => {});
    this.player?.stop();
    this.player = null;
    this.pairing = null;
    this.status = { kind: 'stopped' };
    this.emit();
  }

  private hookQuit() {
    if (this.quitHooked) return;
    this.quitHooked = true;
    app.once('before-quit', () => {
      this.stop('shutdown').catch(() => {});
    });
  }
}

const spkiFingerprintOrNull = (pem: string) => {
  try {
    return spkiFingerprint(pem);
  } catch {
    return null;
  }
};

export const service = new PhoneRemoteService();
