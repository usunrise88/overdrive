import {
  DEFAULT_W_PRIME_KJ,
  type Profile,
  type RideRecord,
  type WPrimeBalance,
} from "@overdrive/contracts";
import { sampleBalanceKj, type WPrimeResult, wPrimeBalance } from "./wbal.ts";

/**
 * The W′ balance of a stored ride with the inputs the service chooses (ADR 0022): critical power is
 * the profile's FTP, else the FTP the ride was recorded with, unless the caller asks for another;
 * W′ is the profile's `wPrimeKj`, else 20 kJ, unless the caller asks for another.
 */

export interface WBalanceChoice {
  cpWatts: number;
  cpSource: WPrimeBalance["cpSource"];
  wPrimeKj: number;
  wPrimeSource: WPrimeBalance["wPrimeSource"];
  result: WPrimeResult;
}

export interface WBalanceOverrides {
  /** Watts. */
  cp?: number | undefined;
  /** Kilojoules. */
  wPrimeKj?: number | undefined;
}

const round = (value: number, digits: number): number => {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
};

export function rideWBalance(
  record: RideRecord,
  profile: Pick<Profile, "ftp" | "wPrimeKj">,
  overrides: WBalanceOverrides = {},
): WBalanceChoice {
  const cpSource: WBalanceChoice["cpSource"] =
    overrides.cp !== undefined ? "request" : profile.ftp !== null ? "profile" : "ride";
  const cpWatts = overrides.cp ?? profile.ftp ?? record.ride.ftp;
  const wPrimeSource: WBalanceChoice["wPrimeSource"] =
    overrides.wPrimeKj !== undefined
      ? "request"
      : profile.wPrimeKj !== null
        ? "profile"
        : "default";
  const wPrimeKj = overrides.wPrimeKj ?? profile.wPrimeKj ?? DEFAULT_W_PRIME_KJ;
  const result = wPrimeBalance(record.samples, { cpWatts, wPrimeJoules: wPrimeKj * 1000 });
  return { cpWatts, cpSource, wPrimeKj, wPrimeSource, result };
}

/** The summary of the balance as the API gives it; the series only when `stepSeconds` is given. */
export function describeWBalance(choice: WBalanceChoice, stepSeconds?: number): WPrimeBalance {
  const { result } = choice;
  const minKj = result.minBalanceJoules / 1000;
  const summary: WPrimeBalance = {
    cpWatts: choice.cpWatts,
    cpSource: choice.cpSource,
    wPrimeKj: choice.wPrimeKj,
    wPrimeSource: choice.wPrimeSource,
    tauSeconds: round(result.tauSeconds, 1),
    dCpWatts: round(result.dCpWatts, 1),
    minBalanceKj: round(minKj, 2),
    minAtSec: result.minAtSeconds,
    maxDepletionPercent: round(((choice.wPrimeKj - minKj) / choice.wPrimeKj) * 100, 1),
  };
  if (stepSeconds !== undefined) {
    summary.series = { stepSeconds, balanceKj: sampleBalanceKj(result, stepSeconds) };
  }
  return summary;
}
