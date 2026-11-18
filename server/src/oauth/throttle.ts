/**
 * Rate limiting and lockout for the OAuth endpoints that are open to the internet (ADR 0018). There
 * is one owner, so the counters are global rather than per address: behind the reverse proxy every
 * request has the proxy's address, and a header naming the caller can be forged. They live in
 * memory: a restart clears them, which nobody but the owner can cause.
 */

export interface LockoutPolicy {
  /** Wrong passphrases in a row that lock the page. */
  maxFailures: number;
  /** The first lock, in milliseconds; each lock after it doubles, up to `maxLockMs`. */
  firstLockMs: number;
  maxLockMs: number;
}

export const DEFAULT_LOCKOUT: LockoutPolicy = {
  maxFailures: 5,
  firstLockMs: 15 * 60_000,
  maxLockMs: 24 * 3_600_000,
};

export type LockoutState =
  | { locked: false; failuresLeft: number }
  | { locked: true; retryAfterSeconds: number };

/**
 * Locks the passphrase page after too many wrong passphrases: five in a row lock it for 15 minutes,
 * and each lock after that is twice as long (a day at most). While it is locked no passphrase is even
 * tried, so guessing costs nothing but the wait. A right passphrase clears everything.
 */
export interface LoginLockout {
  state(now: Date): LockoutState;
  /** A wrong passphrase was tried. */
  failed(now: Date): LockoutState;
  /** The right one was. */
  succeeded(): void;
}

export function createLoginLockout(policy: LockoutPolicy = DEFAULT_LOCKOUT): LoginLockout {
  let failures = 0;
  let locks = 0;
  let lockedUntil = 0;

  const state = (now: Date): LockoutState => {
    if (now.getTime() < lockedUntil) {
      return { locked: true, retryAfterSeconds: Math.ceil((lockedUntil - now.getTime()) / 1000) };
    }
    return { locked: false, failuresLeft: policy.maxFailures - failures };
  };

  return {
    state,
    failed(now) {
      failures++;
      if (failures >= policy.maxFailures) {
        failures = 0;
        lockedUntil = now.getTime() + Math.min(policy.firstLockMs * 2 ** locks, policy.maxLockMs);
        locks++;
      }
      return state(now);
    },
    succeeded() {
      failures = 0;
      locks = 0;
      lockedUntil = 0;
    },
  };
}

/** At most `limit` events in any window of `windowMs`; the oldest fall out as time passes. */
export interface RateLimiter {
  /** Counts one event if there is room: false means it is over the limit and was not counted. */
  take(now: Date): boolean;
}

export function createRateLimiter(limit: number, windowMs: number): RateLimiter {
  const times: number[] = [];
  return {
    take(now) {
      const t = now.getTime();
      while (times.length > 0 && (times[0] as number) <= t - windowMs) times.shift();
      if (times.length >= limit) return false;
      times.push(t);
      return true;
    },
  };
}
