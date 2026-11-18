import type { RecoveryMetric } from "@overdrive/contracts";

/**
 * What it usually means for training when a metric stays outside the rider's own baseline for several days in a row (spec §6.6,
 * ADR 0025). Fixed sentences, written once here: they say what the pattern usually goes with in training terms and what to do about
 * it, name no cause and no medical condition, and carry no numbers (a number in them would have to be in the facts). The alert's
 * text is built from them, by the template or by the model rephrasing them.
 */
export interface Reading {
  /** What the pattern usually means for training. */
  usually: string;
  /** What to do about it, in training terms. */
  hint: string;
}

/** Only the adverse side of each metric alerts (ADR 0025): a resting heart rate below the usual range is not a warning. */
export const READINGS: Record<RecoveryMetric, Reading> = {
  restingHr: {
    usually:
      "A resting heart rate above your usual range for days in a row usually goes with accumulated fatigue or stress, and hard sessions tend to feel heavier.",
    hint: "Keep the next day or two easy and leave the hardest session until it comes back into range.",
  },
  sleepSeconds: {
    usually:
      "Several short nights in a row usually mean recovery is lagging, so hard sessions are less productive and feel harder.",
    hint: "Protect sleep first; ride easy until the nights are back to your usual length.",
  },
  bedtimeMinutes: {
    usually:
      "A run of late bedtimes usually shortens sleep and slows recovery, even when the night is long enough.",
    hint: "Move the evening routine earlier before adding intensity.",
  },
  respiratoryRate: {
    usually:
      "A breathing rate above your usual range in sleep, day after day, usually means the body is under extra load, and hard efforts tend to cost more.",
    hint: "Favour easy riding until it settles.",
  },
  spo2: {
    usually:
      "Overnight oxygen readings from a watch are noisy, so a run below your usual range is worth watching but is not a reason by itself to change the plan.",
    hint: "Train as planned unless other signals agree, and look at the trend rather than one night.",
  },
  sleepingHr: {
    usually:
      "A heart rate in sleep above your usual range for days in a row usually goes with accumulated fatigue, and recovery between sessions is slower.",
    hint: "Keep intensity low for a day or two.",
  },
  hrvRmssd: {
    usually:
      "Heart rate variability trending below your usual range usually means recovery is incomplete.",
    hint: "Favour easy riding and short intervals until it recovers.",
  },
};
