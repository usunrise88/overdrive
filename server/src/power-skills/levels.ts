import type { PowerProfileTable, PowerSkillDuration } from "@overdrive/contracts";

/**
 * The level scale of the power skills (spec §6.3, ADR 0042, design/companion.md §6.11): a rough guide to how a power at a duration
 * compares with the published power-profile tables, in watts per kilogram.
 *
 * THE SCALE. At each duration the table gives two numbers: B, the floor of level 1, and T, the floor of level 9; levels 2 to 8 are
 * equal steps between. A rider's watts per kilogram W are placed on that scale as a **score**,
 *
 *     score = 1 + 8 * (W - B) / (T - B)
 *
 * so a score of 1 is the floor of level 1, 5 is the floor of level 5 and 9 is the floor of level 9; it is rounded to 0.01 and held
 * between 0 and 9, so that one huge effort cannot dominate a mean and a very weak one cannot drag it below nothing. A duration's level
 * is the floor of its score (not below 1); a skill's level is the floor of the mean of the scores of its durations that have an
 * effort (`skillLevelIndex` of the contracts). Scores, not levels, are what the companion draws on its radar.
 *
 * WHERE THE NUMBERS COME FROM, AND HOW FAR TO TRUST THEM. The tables are those of Allen and Coggan (*Training and Racing with a Power
 * Meter*), the widely quoted watts-per-kilogram power profile at four durations, 5 s, 1 min, 5 min and 60 min (FTP), made for young
 * adults and applied as they are whatever the rider's age. **They are approximate:** they survey trained riders, indoor power differs
 * from outdoor, the weight is a 7-day average, and the twelve durations of the skills have no published row, so the other eight are
 * interpolated between the four anchors, linearly in the logarithm of the duration and of the power, and rounded to 0.01 W/kg. A level
 * is a rough guide to how the rider's own numbers move, not a verdict, and nothing here is fitted to this rider.
 *
 * - **Men** (the default): the floors and tops of design/companion.md §6.11, given as they are.
 * - **Women**: built the same way from the published women's anchors. The book itself was not at hand, so the anchors were taken from
 *   two independent transcriptions of its women's chart (the TrainingPeaks power-profile chart read into a table by
 *   triathlonregimen.com, and the "simplified" table of Allen and Coggan, 2nd edition, on cyclistshub.com) and from the top figures
 *   Coggan is quoted giving for women (about 19 W/kg at 5 s, 9 at 1 min, 6.5 at 5 min and 5.5 at threshold). The two agree to within
 *   0.3 W/kg in every cell they share. B is the lowest category boundary of the chart (the floor of "Novice 2"): 8.63, 4.75, 1.97 and
 *   1.58 W/kg at 5 s, 1 min, 5 min and 60 min. T is the top of the chart, which is one category step above the floor of "World class"
 *   (17.88, 8.64, 6.06 and 5.10 W/kg): 19.2, 9.19, 6.64 and 5.61. The same procedure applied to the men's chart (floors 10.35, 5.75,
 *   2.42, 1.93; "World class" from 23.06, 10.68, 6.86, 5.93) lands within a few percent of the design's men's anchors, which is
 *   the check that it is the design's own method. What this does not prove: the numbers were read from images by third parties.
 */

/** B and T at one duration. */
export interface LevelFloors {
  /** The floor of level 1, in W/kg. */
  b: number;
  /** The floor of level 9, in W/kg. */
  t: number;
}

const seconds = [15, 30, 60, 120, 180, 300, 600, 900, 1200, 1800, 2700, 3600] as const;

function table(
  b: readonly number[],
  t: readonly number[],
): Readonly<Record<PowerSkillDuration, LevelFloors>> {
  return Object.fromEntries(
    seconds.map((s, i) => [s, { b: b[i] as number, t: t[i] as number }]),
  ) as Record<PowerSkillDuration, LevelFloors>;
}

/** The floors and tops of the men's table: design/companion.md §6.11, as given. */
const MEN = table(
  [8.2, 6.9, 5.6, 4.2, 3.35, 2.3, 2.2, 2.1, 2.1, 2.0, 1.95, 1.9],
  [18.5, 15.0, 11.5, 9.8, 8.85, 7.6, 7.25, 7.05, 6.95, 6.75, 6.55, 6.4],
);

/** The women's table: log-log interpolation of the anchors above (see the comment of this file). */
const WOMEN = table(
  [6.63, 5.61, 4.75, 3.25, 2.6, 1.97, 1.85, 1.79, 1.74, 1.68, 1.62, 1.58],
  [13.86, 11.29, 9.19, 7.99, 7.36, 6.64, 6.34, 6.16, 6.04, 5.88, 5.72, 5.61],
);

export const LEVEL_FLOORS: Readonly<
  Record<PowerProfileTable, Readonly<Record<PowerSkillDuration, LevelFloors>>>
> = { men: MEN, women: WOMEN };

/** The four durations the published tables give, in seconds: the anchors of the interpolation. */
export const ANCHOR_SECONDS = [5, 60, 300, 3600] as const;

/** The women's anchors (B, then T) at {@link ANCHOR_SECONDS}, from which {@link LEVEL_FLOORS}.women is interpolated. */
export const WOMEN_ANCHORS = {
  b: [8.63, 4.75, 1.97, 1.58],
  t: [19.2, 9.19, 6.64, 5.61],
} as const;

const round2 = (value: number): number => Math.round(value * 100) / 100;

/** The score of `wattsPerKg` at `duration` in `table`: 0 to 9, two decimals (see the comment of this file). */
export function scoreAt(
  profileTable: PowerProfileTable,
  duration: PowerSkillDuration,
  wattsPerKg: number,
): number {
  const { b, t } = LEVEL_FLOORS[profileTable][duration];
  const score = 1 + (8 * (wattsPerKg - b)) / (t - b);
  return round2(Math.min(9, Math.max(0, score)));
}
