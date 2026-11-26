import {
  createHash,
  randomBytes,
  randomUUID,
  timingSafeEqual,
} from 'node:crypto';

/** SPEC 6.4. Never holds the token itself, only its SHA-256. */
export interface DeviceRecord {
  deviceId: string;
  name: string;
  model: string | null;
  /** SHA-256 of the token, lowercase hex. */
  tokenHash: string;
  /** ISO 8601. */
  pairedAt: string;
  lastSeenAt: string | null;
}

const hashToken = (token: string) =>
  createHash('sha256').update(token, 'utf8').digest();

// Compared against when the device ID is unknown, so unknown device and wrong
// token take the same path (SPEC 5.1 step 2).
const DUMMY_HASH = hashToken(randomBytes(32).toString('base64url'));

export class DeviceStore {
  private devices: DeviceRecord[];
  private readonly persist: (devices: DeviceRecord[]) => void;

  constructor(
    initial: readonly DeviceRecord[],
    persist: (devices: DeviceRecord[]) => void,
  ) {
    // The state file is ours, but don't trust its shape blindly.
    this.devices = initial
      .filter(
        (d) =>
          typeof d?.deviceId === 'string' &&
          typeof d.name === 'string' &&
          typeof d.tokenHash === 'string' &&
          /^[0-9a-f]{64}$/.test(d.tokenHash),
      )
      .map((d) => ({
        deviceId: d.deviceId,
        name: d.name,
        model: typeof d.model === 'string' ? d.model : null,
        tokenHash: d.tokenHash,
        pairedAt: typeof d.pairedAt === 'string' ? d.pairedAt : '',
        lastSeenAt: typeof d.lastSeenAt === 'string' ? d.lastSeenAt : null,
      }));
    this.persist = persist;
  }

  list(): readonly DeviceRecord[] {
    return this.devices.map((d) => ({ ...d }));
  }

  get(deviceId: string): DeviceRecord | undefined {
    const d = this.devices.find((x) => x.deviceId === deviceId);
    return d ? { ...d } : undefined;
  }

  /** Constant-time token check. Returns the record on success. */
  verify(deviceId: string, token: string): DeviceRecord | null {
    const record = this.devices.find((d) => d.deviceId === deviceId);
    const expected = record ? Buffer.from(record.tokenHash, 'hex') : DUMMY_HASH;
    const ok = timingSafeEqual(hashToken(token), expected);
    return ok && record ? record : null;
  }

  /** Creates a device. The returned token is shown to the phone once and never stored. */
  create(
    name: string,
    model: string | null,
    now = new Date(),
  ): { record: DeviceRecord; token: string } {
    const token = randomBytes(32).toString('base64url');
    const record: DeviceRecord = {
      deviceId: randomUUID(),
      name,
      model,
      tokenHash: hashToken(token).toString('hex'),
      pairedAt: now.toISOString(),
      lastSeenAt: null,
    };
    this.devices.push(record);
    this.save();
    return { record: { ...record }, token };
  }

  touch(deviceId: string, now = new Date()) {
    const d = this.devices.find((x) => x.deviceId === deviceId);
    if (!d) return;
    d.lastSeenAt = now.toISOString();
    this.save();
  }

  revoke(deviceId: string): boolean {
    const before = this.devices.length;
    this.devices = this.devices.filter((d) => d.deviceId !== deviceId);
    if (this.devices.length === before) return false;
    this.save();
    return true;
  }

  clear() {
    this.devices = [];
    this.save();
  }

  private save() {
    this.persist(this.list().slice());
  }
}
