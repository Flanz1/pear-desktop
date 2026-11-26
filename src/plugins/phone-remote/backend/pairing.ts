import { randomBytes, randomInt, timingSafeEqual } from 'node:crypto';

import { LIMITS } from '../../../../protocol/src/index.ts';

export interface PairingCredentials {
  secret: string;
  code: string;
  expiresAt: number;
}

export type PairingCloseReason =
  | 'paired'
  | 'cancelled'
  | 'timeout'
  | 'disabled'
  | 'too_many_attempts'
  | 'denied';

export type PairingWindowState =
  | { open: false; reason: PairingCloseReason | null }
  | {
      open: true;
      credentials: PairingCredentials;
      awaitingConfirmation: boolean;
    };

export type AttemptResult = 'closed' | 'invalid' | 'accepted';

export interface ConfirmRequest {
  name: string;
  model: string | null;
  address: string;
}

/**
 * The user asks the desktop to confirm. Resolves true for Allow. Must resolve
 * false (Deny) when `signal` aborts, which happens on window timeout or close.
 */
export type ConfirmPairing = (
  request: ConfirmRequest,
  signal: AbortSignal,
) => Promise<boolean>;

const safeEqual = (a: string, b: string) => {
  const ab = Buffer.from(a, 'utf8');
  const bb = Buffer.from(b, 'utf8');
  // Lengths are fixed by the schema; a mismatch is just a wrong value.
  return ab.length === bb.length && timingSafeEqual(ab, bb);
};

/**
 * SPEC 6.1 / 6.3. One window at a time, 120 s, single-use credentials.
 *
 * Interpretation: a correct secret or code consumes the window. While the
 * user is answering the dialog, other `pair.request`s get `pairing_closed`,
 * and if the user clicks Deny the window closes (the credentials were used).
 */
export class PairingController {
  private state:
    | { open: false; reason: PairingCloseReason | null }
    | {
        open: true;
        credentials: PairingCredentials;
        failures: number;
        timer: ReturnType<typeof setTimeout>;
        confirm: AbortController | null;
      } = { open: false, reason: null };

  private readonly listeners = new Set<(s: PairingWindowState) => void>();
  private readonly confirmPairing: ConfirmPairing;
  private readonly now: () => number;
  private readonly windowMs: number;

  constructor(
    confirmPairing: ConfirmPairing,
    options: { now?: () => number; windowMs?: number } = {},
  ) {
    this.confirmPairing = confirmPairing;
    this.now = options.now ?? Date.now;
    this.windowMs = options.windowMs ?? LIMITS.pairingWindowMs;
  }

  onChange(listener: (s: PairingWindowState) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  getState(): PairingWindowState {
    return this.state.open
      ? {
          open: true,
          credentials: { ...this.state.credentials },
          awaitingConfirmation: this.state.confirm !== null,
        }
      : { open: false, reason: this.state.reason };
  }

  /** Opens a new window, replacing any open one. */
  open(): PairingCredentials {
    if (this.state.open) this.close('cancelled');
    const credentials: PairingCredentials = {
      secret: randomBytes(16).toString('base64url'),
      code: randomInt(0, 1_000_000).toString().padStart(6, '0'),
      expiresAt: this.now() + this.windowMs,
    };
    const timer = setTimeout(() => this.close('timeout'), this.windowMs);
    timer.unref?.();
    this.state = { open: true, credentials, failures: 0, timer, confirm: null };
    this.emit();
    return { ...credentials };
  }

  close(reason: PairingCloseReason) {
    if (!this.state.open) return;
    clearTimeout(this.state.timer);
    const pending = this.state.confirm;
    this.state = { open: false, reason };
    // Dismisses the dialog; the pending attempt resolves as timeout/closed.
    pending?.abort(reason);
    this.emit();
  }

  /**
   * Checks a credential. On 'accepted' the caller must call `confirm()`.
   */
  attempt(credential: { secret: string } | { code: string }): AttemptResult {
    const s = this.state;
    if (
      !s.open ||
      s.confirm !== null ||
      this.now() >= s.credentials.expiresAt
    ) {
      return 'closed';
    }
    const ok =
      'secret' in credential
        ? safeEqual(credential.secret, s.credentials.secret)
        : safeEqual(credential.code, s.credentials.code);
    if (!ok) {
      s.failures += 1;
      if (s.failures >= LIMITS.pairingMaxFailedAttempts) {
        this.close('too_many_attempts');
      }
      return 'invalid';
    }
    s.confirm = new AbortController();
    this.emit();
    return 'accepted';
  }

  /**
   * Shows the Allow/Deny dialog for an accepted attempt.
   * `connectionClosed` aborts the dialog if the phone goes away.
   */
  async confirm(
    request: ConfirmRequest,
    connectionClosed: AbortSignal,
  ): Promise<'allowed' | 'denied' | 'timeout' | 'closed'> {
    const s = this.state;
    if (!s.open || !s.confirm) return 'closed';
    const controller = s.confirm;
    const onPhoneGone = () => controller.abort('phone_disconnected');
    connectionClosed.addEventListener('abort', onPhoneGone, { once: true });

    let allowed = false;
    try {
      allowed = await this.confirmPairing(request, controller.signal);
    } catch {
      allowed = false;
    } finally {
      connectionClosed.removeEventListener('abort', onPhoneGone);
    }

    if (controller.signal.aborted) {
      const reason: unknown = controller.signal.reason;
      if (reason === 'phone_disconnected') {
        this.close('cancelled');
        return 'closed';
      }
      return reason === 'timeout' ? 'timeout' : 'closed';
    }
    // Window still ours (not closed meanwhile).
    if (!this.state.open || this.state.confirm !== controller) return 'closed';
    this.close(allowed ? 'paired' : 'denied');
    return allowed ? 'allowed' : 'denied';
  }

  private emit() {
    const snapshot = this.getState();
    for (const l of this.listeners) l(snapshot);
  }
}
