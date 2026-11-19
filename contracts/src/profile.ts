import { readFileSync } from "node:fs";
import { Ajv2020 } from "ajv/dist/2020.js";
import type { Validation } from "./index.ts";
import { isIsoDate } from "./plan.ts";

// ---- Sync API: the rider's profile (spec §8.3, ADR 0018) --------------------------------------
// Hand-written mirror of schema/profile.schema.json and schema/profile-patch.schema.json, which are
// the source of truth; the fixtures test keeps the two honest. Kotlin's `ProfileApiJson` reads and
// writes the same bodies against the same fixtures.

export const WEEKDAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"] as const;
export type Weekday = (typeof WEEKDAYS)[number];

/** Coggan's zones 1 to 6 as inclusive upper bounds in percent of FTP; zone 7 is above the last. */
export const COGGAN_ZONE_UPPER_PERCENT: readonly number[] = [55, 75, 90, 105, 120, 150];

/** The constants of the virtual-speed model; the names and defaults of the ride engine's `PhysicsSettings`. */
export interface Physics {
  /** Rider, bike and kit together, in kilograms. */
  systemMassKg: number;
  /** Drag area in square metres. */
  cdA: number;
  /** Rolling resistance coefficient. */
  crr: number;
  /** Air density in kg/m³. */
  airDensity: number;
  /** Share of the measured power that reaches the wheel, in (0, 1]. */
  drivetrainEfficiency: number;
}

export const DEFAULT_PHYSICS: Readonly<Physics> = {
  systemMassKg: 80,
  cdA: 0.35,
  crr: 0.004,
  airDensity: 1.225,
  drivetrainEfficiency: 1,
};

/** The W′ in kilojoules the service uses while the profile has none: a typical value, a placeholder until the rider sets their own. */
export const DEFAULT_W_PRIME_KJ = 20;

/** The rider's weight as the service has it (spec §12.5): the mean of the daily weights of the last seven days. Read-only and derived. */
export interface RiderWeight {
  /** Kilograms, to one decimal. */
  kg: number;
  /** The local date of the newest weighing in the average. */
  date: string;
  /** How many of the last seven days had a weighing, 1 to 7. */
  days: number;
}

export interface Profile {
  /** Watts, or null when not set. */
  ftp: number | null;
  /** W′ in kilojoules (1 to 100) for the W′ balance, or null for {@link DEFAULT_W_PRIME_KJ}. Critical power is the FTP. */
  wPrimeKj: number | null;
  zones: { upperPercentOfFtp: number[] };
  /** Minutes per weekday; null where unknown. */
  weeklyAvailability: Record<Weekday, number | null>;
  goals: string[];
  physics: Physics;
  /** UTC instant of the last change, null while it is the defaults. */
  updatedAt: string | null;
  // The four heart-rate keys are optional because a profile written before they existed lacks them; the service
  // always writes all four (`withHeartRateKeys` fills what is missing with null).
  /** Maximum heart rate in bpm, or null when not set (spec §6.7, ADR 0023). */
  maxHeartRate?: number | null;
  /** Threshold heart rate in bpm, or null when not set. */
  thresholdHeartRate?: number | null;
  /** The highest heart rate in the stored rides; read-only, null while they say nothing. */
  estimatedMaxHeartRate?: number | null;
  /** 95% of the best 20-minute average heart rate in the stored rides; read-only, null when not enough. */
  estimatedThresholdHeartRate?: number | null;
  // Written only when they have a value: an absent key is "not set" (spec §12.5, ADR 0034).
  /** The bike, kit and bottles in kilograms; with `riderWeight` known and this set, the ride's system mass is their sum. */
  bikeWeightKg?: number | null;
  /** The rider's 7-day average weight from Health Connect; read-only and derived, absent when no weighing is recent. */
  riderWeight?: RiderWeight | null;
}

/** What `PATCH /v1/profile` takes (see `profile-patch.schema.json`). */
export interface ProfilePatch {
  ftp?: number | null;
  /** null puts W′ back to the default. */
  wPrimeKj?: number | null;
  /** null resets the zones to Coggan's. */
  zones?: { upperPercentOfFtp: number[] } | null;
  weeklyAvailability?: Partial<Record<Weekday, number | null>>;
  goals?: string[];
  physics?: Partial<Physics>;
  /** null clears it. */
  maxHeartRate?: number | null;
  /** null clears it. */
  thresholdHeartRate?: number | null;
  /** null clears it. */
  bikeWeightKg?: number | null;
}

/** The profile of a rider who has set nothing. */
export function defaultProfile(): Profile {
  return {
    ftp: null,
    wPrimeKj: null,
    zones: { upperPercentOfFtp: [...COGGAN_ZONE_UPPER_PERCENT] },
    weeklyAvailability: Object.fromEntries(WEEKDAYS.map((day) => [day, null])) as Record<
      Weekday,
      number | null
    >,
    goals: [],
    physics: { ...DEFAULT_PHYSICS },
    updatedAt: null,
    maxHeartRate: null,
    thresholdHeartRate: null,
    estimatedMaxHeartRate: null,
    estimatedThresholdHeartRate: null,
  };
}

/** The four heart-rate keys of a profile that may lack them (one cached before they existed). */
const HEART_RATE_KEYS = [
  "maxHeartRate",
  "thresholdHeartRate",
  "estimatedMaxHeartRate",
  "estimatedThresholdHeartRate",
] as const;

/** `profile` with the heart-rate keys it lacks set to null: what the service always writes. */
export function withHeartRateKeys(profile: Profile): Profile {
  if (HEART_RATE_KEYS.every((key) => profile[key] !== undefined)) return profile;
  const filled = { ...profile };
  for (const key of HEART_RATE_KEYS) filled[key] ??= null;
  return filled;
}

// ---- Validation ------------------------------------------------------------------------------

const ajv = new Ajv2020({ allErrors: true });

function load(name: string): object {
  return JSON.parse(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
}

// The patch schema points into the profile schema by its file name.
const profileSchema = load("profile.schema.json");
ajv.addSchema(profileSchema, "profile.schema.json");
const checkProfile = ajv.compile<Profile>(profileSchema);
const checkPatch = ajv.compile<ProfilePatch>(load("profile-patch.schema.json"));

function errorsOf(errors: typeof checkPatch.errors): string[] {
  return (errors ?? []).map((e) => `${e.instancePath || "/"} ${e.message ?? "is invalid"}`);
}

/**
 * What the schema cannot say: the six zone bounds strictly increase, no goal is blank, a threshold
 * heart rate is below the maximum when both are set and the date of the rider's weight is a real one. Returns the violations, or an empty list.
 */
export function profileConsistencyErrors(profile: Profile): string[] {
  const errors: string[] = [];
  const bounds = profile.zones.upperPercentOfFtp;
  for (let i = 1; i < bounds.length; i++) {
    const previous = bounds[i - 1] as number;
    const current = bounds[i] as number;
    if (current <= previous) {
      errors.push(
        `/zones/upperPercentOfFtp/${i} is ${current}, not above the bound before it (${previous})`,
      );
      break;
    }
  }
  for (const [index, goal] of profile.goals.entries()) {
    if (goal.trim() === "") {
      errors.push(`/goals/${index} is blank`);
      break;
    }
  }
  if (profile.riderWeight != null && !isIsoDate(profile.riderWeight.date)) {
    errors.push("/riderWeight/date is not a real calendar date");
  }
  const { maxHeartRate: max, thresholdHeartRate: threshold } = profile;
  if (max != null && threshold != null && threshold >= max) {
    errors.push(`/thresholdHeartRate ${threshold} is not below /maxHeartRate ${max}`);
  }
  return errors;
}

/**
 * Validates a profile (as GET /v1/profile answers): the schema first, then the rules a schema cannot state.
 * A profile written before the heart-rate keys existed is valid; the value returned has them, null.
 */
export function validateProfile(input: unknown): Validation<Profile> {
  if (!checkProfile(input)) return { ok: false, errors: errorsOf(checkProfile.errors) };
  const errors = profileConsistencyErrors(input);
  return errors.length === 0
    ? { ok: true, value: withHeartRateKeys(input) }
    : { ok: false, errors };
}

/** Validates the shape of a patch; whether it makes a valid profile is {@link applyProfilePatch}'s to say. */
export function validateProfilePatch(input: unknown): Validation<ProfilePatch> {
  return checkPatch(input)
    ? { ok: true, value: input }
    : { ok: false, errors: errorsOf(checkPatch.errors) };
}

/**
 * The profile `patch` makes of `profile`, or why it is refused (nothing is changed either way).
 * `updatedAt` is left as it was: the service stamps it when it stores the change.
 */
export function applyProfilePatch(profile: Profile, patch: unknown): Validation<Profile> {
  const shape = validateProfilePatch(patch);
  if (!shape.ok) return shape;
  const change = shape.value;
  const next = structuredClone(profile);
  if (change.ftp !== undefined) next.ftp = change.ftp;
  if (change.wPrimeKj !== undefined) next.wPrimeKj = change.wPrimeKj;
  if (change.zones !== undefined) {
    next.zones = {
      upperPercentOfFtp:
        change.zones === null
          ? [...COGGAN_ZONE_UPPER_PERCENT]
          : [...change.zones.upperPercentOfFtp],
    };
  }
  for (const day of WEEKDAYS) {
    const minutes = change.weeklyAvailability?.[day];
    if (minutes !== undefined) next.weeklyAvailability[day] = minutes;
  }
  if (change.goals !== undefined) next.goals = [...change.goals];
  if (change.maxHeartRate !== undefined) next.maxHeartRate = change.maxHeartRate;
  if (change.thresholdHeartRate !== undefined) next.thresholdHeartRate = change.thresholdHeartRate;
  if (change.bikeWeightKg !== undefined) next.bikeWeightKg = change.bikeWeightKg;
  if (change.physics !== undefined) {
    next.physics = { ...next.physics, ...change.physics };
  }
  const errors = profileConsistencyErrors(next);
  return errors.length === 0 ? { ok: true, value: next } : { ok: false, errors };
}

// ---- Zones -----------------------------------------------------------------------------------

/** One power zone in whole watts; `maxWatts` is null for the last zone, which has no upper bound. */
export interface PowerZoneRange {
  /** 1 to 7. */
  zone: number;
  minWatts: number;
  maxWatts: number | null;
}

/**
 * The seven zones in watts for an FTP and the six upper bounds: power is measured in whole watts,
 * so a bound of 55% of 250 W (137.5) is 137 W, zone 1 is 0 to 137 W and zone 2 starts at 138 W. The
 * same reading as the ride app's `PowerZone.of`, which `zone-ranges.json` pins in both languages.
 */
export function powerZoneRanges(
  ftp: number,
  upperPercentOfFtp: readonly number[],
): PowerZoneRange[] {
  const ranges: PowerZoneRange[] = [];
  let min = 0;
  for (const [index, percent] of upperPercentOfFtp.entries()) {
    const max = Math.floor((percent * ftp) / 100);
    ranges.push({ zone: index + 1, minWatts: min, maxWatts: max });
    min = max + 1;
  }
  ranges.push({ zone: upperPercentOfFtp.length + 1, minWatts: min, maxWatts: null });
  return ranges;
}

/** The zone (1 to 7) a power in watts falls in; negative power counts as zero. */
export function powerZoneOf(
  powerWatts: number,
  ftp: number,
  upperPercentOfFtp: readonly number[],
): number {
  const scaled = Math.max(powerWatts, 0) * 100;
  for (const [index, percent] of upperPercentOfFtp.entries()) {
    if (scaled <= percent * ftp) return index + 1;
  }
  return upperPercentOfFtp.length + 1;
}
