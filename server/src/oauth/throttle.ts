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

/**
 * Lockouts for many users: one {@link LoginLockout} per handle, so five wrong passphrases for one handle lock that
 * handle and nobody else, plus a cap on the failures of all handles together. The cap is generous and exists for
 * the attacker who spreads guesses over many handles (each of which would allow its own five tries); it counts
 * only passphrases that were actually tried, and a handle that is locked is not tried, so one handle can add only a
 * few failures to it per lock. Handles nobody has are tracked like real ones, so the answer does not tell them apart.
 */
export interface HandleLockouts {
  /** The state for `handle` now: the cap's lock when it is full, otherwise the handle's own. */
  state(handle: string, now: Date): LockoutState;
  /** A wrong passphrase was tried for `handle`. */
  failed(handle: string, now: Date): LockoutState;
  /** The right one was: clears that handle's counters (the cap's window is not cleared). */
  succeeded(handle: string): void;
}

export interface GlobalFailureCap {
  /** Wrong passphrases in the window, over all handles, that close the page for everyone. */
  limit: number;
  windowMs: number;
}

export const DEFAULT_GLOBAL_FAILURE_CAP: GlobalFailureCap = { limit: 100, windowMs: 3_600_000 };

/** How many handles' counters are kept; the oldest are dropped beyond it. */
const MAX_TRACKED_HANDLES = 10_000;

export function createHandleLockouts(
  policy: LockoutPolicy = DEFAULT_LOCKOUT,
  cap: GlobalFailureCap = DEFAULT_GLOBAL_FAILURE_CAP,
): HandleLockouts {
  const byHandle = new Map<string, LoginLockout>();
  const failures: number[] = [];

  const capState = (now: Date): LockoutState | null => {
    const t = now.getTime();
    while (failures.length > 0 && (failures[0] as number) <= t - cap.windowMs) failures.shift();
    if (failures.length < cap.limit) return null;
    const oldest = failures[0] as number;
    return {
      locked: true,
      retryAfterSeconds: Math.max(1, Math.ceil((oldest + cap.windowMs - t) / 1000)),
    };
  };
  const of = (handle: string): LoginLockout => {
    let lockout = byHandle.get(handle);
    if (lockout === undefined) {
      if (byHandle.size >= MAX_TRACKED_HANDLES) {
        const oldest = byHandle.keys().next().value;
        if (oldest !== undefined) byHandle.delete(oldest);
      }
      lockout = createLoginLockout(policy);
      byHandle.set(handle, lockout);
    }
    return lockout;
  };

  return {
    state(handle, now) {
      return capState(now) ?? of(handle).state(now);
    },
    failed(handle, now) {
      failures.push(now.getTime());
      const own = of(handle).failed(now);
      return capState(now) ?? own;
    },
    succeeded(handle) {
      byHandle.delete(handle);
    },
  };
}
