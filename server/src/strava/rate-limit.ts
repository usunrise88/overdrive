/**
 * Strava's rate limits (developers.strava.com/docs/rate-limits), as far as this service meets them.
 * Every answer carries our own usage in `X-RateLimit-Limit` / `X-RateLimit-Usage` (the 15-minute
 * and the daily figure, "200,2000") and the same pair for reads, `X-ReadRateLimit-*` (a read is
 * every call but an upload: the status polls). The 15-minute windows end at :00, :15, :30 and :45;
 * the daily one at midnight UTC. A request over a limit is answered 429, and still counts toward
 * the daily one: so the worker stops at the limit instead of finding it out with a 429.
 * These headers describe our own application's usage, not anything Strava holds about the rider.
 */

const MINUTE = 60_000;
const QUARTER = 15 * MINUTE;
const DAY = 24 * 60 * MINUTE;
/** Waited after a window ends, so the clocks of the two sides need not agree to the second. */
const SLACK_MS = 5_000;

export interface RateWindow {
  limit15: number;
  limitDay: number;
  usage15: number;
  usageDay: number;
}

export interface RateInfo {
  overall: RateWindow | null;
  read: RateWindow | null;
}

function pair(value: string | null): [number, number] | null {
  if (value === null) return null;
  const parts = value.split(",").map((part) => Number(part.trim()));
  const [first, second] = parts;
  if (parts.length !== 2 || first === undefined || second === undefined) return null;
  if (!Number.isFinite(first) || !Number.isFinite(second) || first < 0 || second < 0) return null;
  return [first, second];
}

function windowOf(limit: string | null, usage: string | null): RateWindow | null {
  const limits = pair(limit);
  const used = pair(usage);
  if (limits === null || used === null) return null;
  return { limit15: limits[0], limitDay: limits[1], usage15: used[0], usageDay: used[1] };
}

/** The rate-limit headers of an answer, or nulls where they are absent or unreadable. */
export function readRateInfo(headers: Headers): RateInfo {
  return {
    overall: windowOf(headers.get("x-ratelimit-limit"), headers.get("x-ratelimit-usage")),
    read: windowOf(headers.get("x-readratelimit-limit"), headers.get("x-readratelimit-usage")),
  };
}

/** The end of the 15-minute window `at` is in (epoch milliseconds), plus the slack. */
export function nextQuarterHour(at: number): number {
  return Math.floor(at / QUARTER) * QUARTER + QUARTER + SLACK_MS;
}

/** The next midnight UTC after `at`, plus the slack. */
export function nextMidnightUtc(at: number): number {
  return Math.floor(at / DAY) * DAY + DAY + SLACK_MS;
}

/**
 * When the next request may be sent, if the headers say a limit is used up (epoch milliseconds), or
 * null when there is room. Either window counts, the overall and the read one: a stop is a stop for
 * the whole service, which is simpler than telling an upload from a poll, and the cost of the
 * caution is a few minutes.
 */
export function exhaustedUntil(info: RateInfo, now: number): number | null {
  let until: number | null = null;
  for (const window of [info.overall, info.read]) {
    if (window === null) continue;
    if (window.limitDay > 0 && window.usageDay >= window.limitDay) {
      until = Math.max(until ?? 0, nextMidnightUtc(now));
    } else if (window.limit15 > 0 && window.usage15 >= window.limit15) {
      until = Math.max(until ?? 0, nextQuarterHour(now));
    }
  }
  return until;
}

/** `Retry-After` as seconds or an HTTP date, in milliseconds from `now`; null when absent or unreadable. */
export function retryAfterMs(value: string | null, now: number): number | null {
  if (value === null) return null;
  const trimmed = value.trim();
  if (/^\d+$/.test(trimmed)) return Number(trimmed) * 1000;
  const date = Date.parse(trimmed);
  return Number.isNaN(date) ? null : Math.max(0, date - now);
}

const MAX_PAUSE_MS = 24 * 60 * MINUTE;

/**
 * When to try again after a 429 (epoch milliseconds): the server's `Retry-After` if it sent one, the
 * end of the window the headers say is used up, otherwise the end of this 15-minute window. A 429
 * that follows another with nothing in between waits longer each time, in case the window it
 * guessed at was the wrong one (the daily limit): 15 minutes, then 30, … up to 6 hours.
 */
export function pauseAfter429(options: {
  now: number;
  retryAfter: string | null;
  info: RateInfo;
  /** How many 429s in a row, this one included. */
  consecutive: number;
}): number {
  const { now, retryAfter, info, consecutive } = options;
  const asked = retryAfterMs(retryAfter, now);
  const known = exhaustedUntil(info, now);
  let until =
    asked === null && known === null
      ? nextQuarterHour(now)
      : Math.max(asked === null ? 0 : now + Math.max(asked, 1000), known ?? 0);
  if (consecutive >= 2) {
    until = Math.max(until, now + Math.min(QUARTER * 2 ** (consecutive - 2), 6 * 60 * MINUTE));
  }
  return Math.min(until, now + MAX_PAUSE_MS);
}
