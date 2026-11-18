import type { Confidence, PowerCurveDuration } from "@overdrive/contracts";
import { type BestEffort, bestEfforts, type RideCurve } from "./power-curve.ts";

/**
 * FTP without a test (spec §6.3): an estimate from the best efforts of recent rides, in the manner
 * of the eFTP of intervals.icu. It is a **suggestion only**: nothing here changes the profile; the
 * rider confirms a change (stage P3-5) before the zones move. Written down in ADR 0022:
 *
 *  1. Take the rides of the last 42 days (the caller's window; local days, today included).
 *  2. Two candidate efforts, each a lower bound of the FTP if the rider did not go all out:
 *     the best 20-minute power times 0.95 (Allen and Coggan's field test), and the best 60-minute
 *     power times 1.00 (FTP is, by definition, about what an hour of full effort holds).
 *  3. A candidate counts only when at least {@link EFTP_MIN_RIDES} rides have a comparable effort
 *     (within 90% of the best at that duration), so one unusual ride cannot move the FTP.
 *  4. The estimate is the larger of the candidates that count, rounded to whole watts. The basis is
 *     the best effort it came from, with its ride and day.
 *  5. Confidence follows from how many rides back the effort: 2 low, 3 to 4 medium, 5 or more high.
 *  6. Pauses and missing readings count as 0 W in the power curve, so they can only lower it.
 *
 * Other activities (heart-rate load) never enter: only rides have a power curve.
 */

export const EFTP_WINDOW_DAYS = 42;
export const EFTP_MIN_RIDES = 2;
/** A ride backs an effort when its power at that duration is at least this share of the best. */
export const EFTP_SUPPORT_SHARE = 0.9;
/** A new suggestion must differ from the FTP (and from the pending suggestion) by at least this, in percent. */
export const SUGGESTION_THRESHOLD_PERCENT = 3;

export const EFTP_CANDIDATES: readonly { durationSec: PowerCurveDuration; factor: number }[] = [
  { durationSec: 1200, factor: 0.95 },
  { durationSec: 3600, factor: 1 },
];

const MIN_FTP = 30;
const MAX_FTP = 2000;

export interface EftpEstimate {
  /** Whole watts. */
  value: number;
  basis: BestEffort;
  confidence: Confidence;
  /** The rides with an effort comparable to the best at the basis duration, the best one included. */
  supportingRides: number;
}

export type EftpResult = { ok: true; estimate: EftpEstimate } | { ok: false; reason: string };

const round1 = (value: number): number => Math.round(value * 10) / 10;

export function confidenceFor(supportingRides: number): Confidence {
  if (supportingRides >= 5) return "high";
  if (supportingRides >= 3) return "medium";
  return "low";
}

/** The eFTP from the curves of the rides in the window, or why there is none. */
export function estimateFtp(rides: readonly RideCurve[]): EftpResult {
  if (rides.length === 0) {
    return { ok: false, reason: `No ride in the last ${EFTP_WINDOW_DAYS} days` };
  }
  const best = bestEfforts(rides);
  let chosen: { estimate: number; effort: BestEffort; supporting: number } | null = null;
  let longestEffortSeen = false;
  let mostSupport = 0;
  for (const { durationSec, factor } of EFTP_CANDIDATES) {
    const effort = best.find((b) => b.durationSec === durationSec);
    if (effort === undefined) continue;
    longestEffortSeen = true;
    const floor = effort.power * EFTP_SUPPORT_SHARE;
    const supporting = rides.filter((ride) =>
      ride.curve.some((p) => p.durationSec === durationSec && p.power >= floor),
    ).length;
    mostSupport = Math.max(mostSupport, supporting);
    if (supporting < EFTP_MIN_RIDES) continue;
    const estimate = effort.power * factor;
    if (chosen === null || estimate > chosen.estimate) chosen = { estimate, effort, supporting };
  }
  if (chosen === null) {
    return {
      ok: false,
      reason: longestEffortSeen
        ? `Only ${mostSupport} ride${mostSupport === 1 ? "" : "s"} in the last ${EFTP_WINDOW_DAYS} days ${mostSupport === 1 ? "has" : "have"} a 20-minute effort close to the best; ${EFTP_MIN_RIDES} are needed so that one unusual ride does not move the FTP`
        : `No ride in the last ${EFTP_WINDOW_DAYS} days has 20 minutes of power`,
    };
  }
  const value = Math.round(chosen.estimate);
  if (value < MIN_FTP || value > MAX_FTP) {
    return { ok: false, reason: `The estimate of ${value} W is not a possible FTP` };
  }
  return {
    ok: true,
    estimate: {
      value,
      basis: chosen.effort,
      confidence: confidenceFor(chosen.supporting),
      supportingRides: chosen.supporting,
    },
  };
}

/** `value` against `against` in percent, one decimal; null when there is nothing to compare with. */
export function differencePercent(value: number, against: number | null): number | null {
  return against === null || against <= 0 ? null : round1(((value - against) / against) * 100);
}

/**
 * Whether `value` differs from `against` by at least {@link SUGGESTION_THRESHOLD_PERCENT}. Whole
 * watts on both sides, so the comparison is exact: no float on the boundary. With nothing to compare
 * with it does (a first FTP is always worth suggesting).
 */
export function differsEnough(value: number, against: number | null): boolean {
  if (against === null) return true;
  return Math.abs(value - against) * 100 >= SUGGESTION_THRESHOLD_PERCENT * against;
}
