import {
  POWER_LEVEL_NAMES,
  POWER_SKILL_DURATIONS,
  type PowerSkillDuration,
} from "@overdrive/contracts";

/**
 * The nine levels of the power skills (spec §6.3, ADR 0042): a rough guide to how a power at a duration compares with other riders,
 * in watts per kilogram.
 *
 * WHERE THE NUMBERS COME FROM, AND HOW FAR TO TRUST THEM. The scale is our own, built from the published power-profile table of
 * Coggan and Allen (*Training and Racing with a Power Meter*), the widely quoted approximate watts-per-kilogram table for **men**
 * at four durations: 5 s, 1 min, 5 min and 60 min (FTP). The four anchor columns below are rounded approximations of its nine
 * rows (from "untrained" to "world class"), made into equal-looking steps; they are **not** a copy of the book. The twelve
 * durations of the power skills have no published row, so the other eight are **interpolated** between the anchors, linearly in
 * the logarithm of the duration and of the power, and rounded to 0.01 W/kg. The table is not tied to sex or age: for a woman or an
 * older rider the same level means a different standing against peers, so "Level 4 of 9" is a rough guide to how the rider's own
 * numbers move, not a verdict. Nothing here is a physiological law and nothing is fitted to this rider.
 *
 * Level 1 starts at 0: every effort is at least level 1. A power reaches level N at a duration when its watts per kilogram are at
 * least the threshold of level N at that duration. Thresholds rise with the level and fall with the duration: a test checks both.
 *
 * Anchors (W/kg at 5 s, 1 min, 5 min, 60 min) of levels 2 to 9:
 *
 *     2: 9.5 / 4.0 / 2.5 / 2.0      6: 17.5 / 8.2 / 5.3 / 4.4
 *     3: 11.5 / 5.0 / 3.2 / 2.6     7: 19.5 / 9.3 / 6.0 / 5.0
 *     4: 13.5 / 6.0 / 3.9 / 3.2     8: 21.5 / 10.4 / 6.8 / 5.7
 *     5: 15.5 / 7.1 / 4.6 / 3.8     9: 24.0 / 11.5 / 7.6 / 6.4
 */
export const LEVEL_COUNT: number = POWER_LEVEL_NAMES.length;

/**
 * The least watts per kilogram that reaches each level at each duration: `LEVEL_THRESHOLDS[seconds][level - 1]`, so index 0 (level 1)
 * is always 0 and index 8 (level 9, "Elite") the highest.
 */
export const LEVEL_THRESHOLDS: Readonly<Record<PowerSkillDuration, readonly number[]>> = {
  15: [0, 6.48, 7.96, 9.43, 10.98, 12.52, 14.06, 15.6, 17.34],
  30: [0, 5.09, 6.31, 7.52, 8.83, 10.13, 11.43, 12.74, 14.12],
  60: [0, 4.0, 5.0, 6.0, 7.1, 8.2, 9.3, 10.4, 11.5],
  120: [0, 3.27, 4.13, 4.98, 5.89, 6.79, 7.7, 8.66, 9.62],
  180: [0, 2.9, 3.69, 4.47, 5.28, 6.09, 6.9, 7.78, 8.67],
  300: [0, 2.5, 3.2, 3.9, 4.6, 5.3, 6.0, 6.8, 7.6],
  600: [0, 2.35, 3.02, 3.69, 4.36, 5.03, 5.7, 6.47, 7.24],
  900: [0, 2.27, 2.92, 3.57, 4.23, 4.88, 5.54, 6.29, 7.04],
  1200: [0, 2.21, 2.85, 3.49, 4.13, 4.78, 5.42, 6.16, 6.91],
  1800: [0, 2.13, 2.76, 3.38, 4.01, 4.63, 5.26, 5.99, 6.71],
  2700: [0, 2.05, 2.66, 3.27, 3.88, 4.5, 5.11, 5.82, 6.53],
  3600: [0, 2.0, 2.6, 3.2, 3.8, 4.4, 5.0, 5.7, 6.4],
};

export interface LevelReached {
  /** 1 to 9. */
  index: number;
  name: string;
  /** What the same duration needs for the next level; null at level 9. */
  nextLevelWattsPerKg: number | null;
}

/** The level that `wattsPerKg` reaches at `seconds`: the highest whose threshold it meets. */
export function levelAt(seconds: PowerSkillDuration, wattsPerKg: number): LevelReached {
  const thresholds = LEVEL_THRESHOLDS[seconds];
  let index = 1;
  for (let level = 2; level <= LEVEL_COUNT; level++) {
    if (wattsPerKg >= (thresholds[level - 1] as number)) index = level;
  }
  return {
    index,
    name: POWER_LEVEL_NAMES[index - 1] as string,
    nextLevelWattsPerKg: index === LEVEL_COUNT ? null : (thresholds[index] as number),
  };
}

/** Every duration the table has a row for, in order (the contract's twelve). */
export const LEVEL_DURATIONS: readonly PowerSkillDuration[] = POWER_SKILL_DURATIONS;
