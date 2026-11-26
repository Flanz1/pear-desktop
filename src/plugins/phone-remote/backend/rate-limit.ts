import { LIMITS } from '../../../../protocol/src/index.ts';

/** Command token bucket (SPEC 8): burst 20, refill 10/s. */
export class TokenBucket {
  private tokens: number;
  private last: number;
  /** When the client first went over the limit without recovering, or null. */
  private overSince: number | null = null;

  private readonly now: () => number;
  private readonly burst: number;
  private readonly perSecond: number;

  constructor(
    now: () => number = Date.now,
    burst: number = LIMITS.commandBurst,
    perSecond: number = LIMITS.commandsPerSecond,
  ) {
    this.now = now;
    this.burst = burst;
    this.perSecond = perSecond;
    this.tokens = burst;
    this.last = now();
  }

  /** 'ok', 'limited', or 'abusive' (over the limit continuously for 10 s). */
  take(): 'ok' | 'limited' | 'abusive' {
    const t = this.now();
    const refill = ((t - this.last) / 1000) * this.perSecond;
    this.tokens = Math.min(this.burst, this.tokens + refill);
    this.last = t;
    if (this.tokens >= 1) {
      this.tokens -= 1;
      this.overSince = null;
      return 'ok';
    }
    this.overSince ??= t;
    return t - this.overSince >= 10_000 ? 'abusive' : 'limited';
  }
}

/**
 * Failed authentication / pairing attempts per peer address (SPEC 5.1 step 3):
 * after 5 failures in 60 s, refuse that address for 60 s.
 */
export class FailureLimiter {
  private readonly failures = new Map<string, number[]>();
  private readonly blockedUntil = new Map<string, number>();

  private readonly now: () => number;
  private readonly maxFailures = 5;
  private readonly windowMs = 60_000;
  private readonly blockMs = 60_000;

  constructor(now: () => number = Date.now) {
    this.now = now;
  }

  isBlocked(address: string): boolean {
    const until = this.blockedUntil.get(address);
    if (until === undefined) return false;
    if (this.now() >= until) {
      this.blockedUntil.delete(address);
      return false;
    }
    return true;
  }

  recordFailure(address: string) {
    const t = this.now();
    const recent = (this.failures.get(address) ?? []).filter(
      (x) => t - x < this.windowMs,
    );
    recent.push(t);
    if (recent.length >= this.maxFailures) {
      this.blockedUntil.set(address, t + this.blockMs);
      this.failures.delete(address);
    } else {
      this.failures.set(address, recent);
    }
  }

  clear() {
    this.failures.clear();
    this.blockedUntil.clear();
  }
}
