import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

import type { DeviceRecord } from './devices.ts';

export interface StoredIdentity {
  /** PKCS#8 private key PEM, base64. Encrypted with safeStorage when `encrypted`. */
  key: string;
  encrypted: boolean;
  /** Self-signed certificate (PEM). Public. */
  cert: string;
}

export interface RemoteState {
  instanceId: string;
  identity: StoredIdentity | null;
  devices: DeviceRecord[];
}

const empty = (): RemoteState => ({
  instanceId: '',
  identity: null,
  devices: [],
});

/**
 * Plugin state outside the shared app config, which the renderer can read.
 * One JSON file, owner-only permissions, atomic replace on write.
 */
export class StateFile {
  private readonly path: string;

  constructor(path: string) {
    this.path = path;
  }

  load(): RemoteState {
    try {
      const v = JSON.parse(
        readFileSync(this.path, 'utf8'),
      ) as Partial<RemoteState>;
      return {
        instanceId: typeof v.instanceId === 'string' ? v.instanceId : '',
        identity:
          v.identity &&
          typeof v.identity.key === 'string' &&
          typeof v.identity.cert === 'string'
            ? {
                key: v.identity.key,
                encrypted: v.identity.encrypted === true,
                cert: v.identity.cert,
              }
            : null,
        devices: Array.isArray(v.devices) ? v.devices : [],
      };
    } catch {
      return empty();
    }
  }

  save(state: RemoteState) {
    mkdirSync(dirname(this.path), { recursive: true, mode: 0o700 });
    const tmp = `${this.path}.tmp`;
    writeFileSync(tmp, JSON.stringify(state, null, 2), { mode: 0o600 });
    renameSync(tmp, this.path);
  }
}
