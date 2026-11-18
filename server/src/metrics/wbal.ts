import type { RideSample } from "@overdrive/contracts";

/**
 * W′ balance (spec §6.3): how much of the work capacity above critical power is left at each
 * second of a ride, after Skiba et al. (2012), the "integral" model, peer-reviewed. Written down in
 * ADR 0022:
 *
 *     expended(t) = max(P(t) - CP, 0)                       joules in second t
 *     balance(t)  = W′ - sum over u <= t of expended(u) * exp(-(t - u) / tau)
 *     tau         = 546 * exp(-0.01 * D_CP) + 316           seconds
 *     D_CP        = CP - mean of the powers below CP        watts, over the ride, 0 when none
 *
 * At one sample per second the sum is the recursion `E(t) = E(t-1) * exp(-1/tau) + expended(t)`,
 * `balance(t) = W′ - E(t)`, which is what is computed: exact for the model, no integration step to
 * approximate. A paused second and a second with no reading count as 0 W (so W′ is recovering), but
 * they are left out of D_CP (nothing was ridden or measured there).
 *
 * CP is taken to be the FTP, which is the rider's own approximation to it and the caller's to
 * choose; W′ is the rider's profile value or the default of 20 kJ. A balance below 0 means the
 * model says W′ was exceeded, that is, the CP or W′ given are too low for this rider.
 */

export interface WPrimeParams {
  /** Critical power in watts, above 0. */
  cpWatts: number;
  /** W′ in joules, above 0. */
  wPrimeJoules: number;
}

export interface WPrimeResult {
  /** Time constant of the recovery, seconds. */
  tauSeconds: number;
  /** CP minus the mean power below it, watts (0 when no second was below). */
  dCpWatts: number;
  /** The lowest balance in joules, and the second it was first reached (0: W′ was never used). */
  minBalanceJoules: number;
  minAtSeconds: number;
  /** The balance in joules at the end of second 1, 2, 3, … of the ride. */
  balanceJoules: Float64Array;
}

/** Skiba's constants for the time constant of recovery. */
const TAU_A = 546;
const TAU_B = 0.01;
const TAU_C = 316;

export function wPrimeBalance(samples: readonly RideSample[], params: WPrimeParams): WPrimeResult {
  const { cpWatts, wPrimeJoules } = params;

  let belowSum = 0;
  let belowCount = 0;
  for (const sample of samples) {
    if (sample.paused === true || sample.power == null) continue;
    if (sample.power < cpWatts) {
      belowSum += sample.power;
      belowCount++;
    }
  }
  const dCpWatts = belowCount === 0 ? 0 : cpWatts - belowSum / belowCount;
  const tauSeconds = TAU_A * Math.exp(-TAU_B * dCpWatts) + TAU_C;
  const decay = Math.exp(-1 / tauSeconds);

  const balanceJoules = new Float64Array(samples.length);
  let expended = 0;
  let min = wPrimeJoules;
  let minAt = 0;
  for (const [index, sample] of samples.entries()) {
    const power = sample.paused === true || sample.power == null ? 0 : sample.power;
    expended = expended * decay + Math.max(power - cpWatts, 0);
    const balance = wPrimeJoules - expended;
    balanceJoules[index] = balance;
    if (balance < min) {
      min = balance;
      minAt = index + 1;
    }
  }
  return { tauSeconds, dCpWatts, minBalanceJoules: min, minAtSeconds: minAt, balanceJoules };
}

/** The balance at the end of every `stepSeconds`-th second (kilojoules, two decimals); a partial last step is left out. */
export function sampleBalanceKj(result: WPrimeResult, stepSeconds: number): number[] {
  const points: number[] = [];
  for (let t = stepSeconds; t <= result.balanceJoules.length; t += stepSeconds) {
    points.push(Math.round(((result.balanceJoules[t - 1] as number) / 1000) * 100) / 100);
  }
  return points;
}
