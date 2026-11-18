import {
  applyProfilePatch,
  defaultProfile,
  type Profile,
  validateProfile,
} from "@overdrive/contracts";
import type { Db } from "../db.ts";
import type { EventBus } from "../events.ts";
import { canonicalJson } from "../hash.ts";

/**
 * The rider's profile (spec §8.3, ADR 0018): one row of the `profile` table, read and changed through
 * this store by `GET`/`PATCH /v1/profile` and the MCP tools. Other modules read the FTP and the
 * zones here and hear about a change as the `profile-changed` event.
 */

/** A patch that does not make a valid profile; `errors` say why, in the contract's words. */
export class ProfilePatchError extends Error {
  readonly errors: string[];

  constructor(errors: string[]) {
    super(errors.slice(0, 5).join("; "));
    this.name = "ProfilePatchError";
    this.errors = errors;
  }
}

export interface ProfileUpdate {
  profile: Profile;
  /** The top-level parts that changed; empty when the patch changed nothing (nothing was stored). */
  changed: string[];
}

export interface ProfileStore {
  /** The stored profile, or the defaults while nothing has been set, with the heart-rate estimates the rides give. */
  get(): Promise<Profile>;
  /**
   * Applies a patch (`profile-patch.schema.json`) atomically. Throws {@link ProfilePatchError} and
   * changes nothing when it does not make a valid profile. A patch that changes nothing stores
   * nothing and announces nothing.
   */
  update(patch: unknown): Promise<ProfileUpdate>;
}

/**
 * The heart rates the stored rides say about the rider (ADR 0023): `estimatedMaxHeartRate` and `estimatedThresholdHeartRate`.
 * They are worked out when the profile is read, never stored with it.
 */
export type HeartRateEstimator = () => Promise<{ max: number | null; threshold: number | null }>;

type Stored = Omit<Profile, "updatedAt" | "estimatedMaxHeartRate" | "estimatedThresholdHeartRate">;

const PARTS = [
  "ftp",
  "wPrimeKj",
  "zones",
  "weeklyAvailability",
  "goals",
  "physics",
  "maxHeartRate",
  "thresholdHeartRate",
] as const;

/** What is stored: the profile without its stamp and without the estimates, which are read-only and computed. */
function withoutStamp(profile: Profile): Stored {
  const {
    updatedAt: _stamp,
    estimatedMaxHeartRate: _max,
    estimatedThresholdHeartRate: _threshold,
    ...rest
  } = profile;
  return rest;
}

export function createProfileStore(
  db: Db,
  bus: EventBus,
  now: () => Date,
  estimator?: HeartRateEstimator,
): ProfileStore {
  /** The profile with the estimates the rides give. */
  async function withEstimates(profile: Profile): Promise<Profile> {
    if (estimator === undefined) return profile;
    const estimates = await estimator();
    return {
      ...profile,
      estimatedMaxHeartRate: estimates.max,
      estimatedThresholdHeartRate: estimates.threshold,
    };
  }

  async function read(tx: Pick<Db, "query">, lock: boolean): Promise<Profile> {
    const result = await tx.query<{ data: Stored; updated_at: Date | string }>(
      `SELECT data, updated_at FROM profile WHERE id${lock ? " FOR UPDATE" : ""}`,
    );
    const row = result.rows[0];
    if (row === undefined) return defaultProfile();
    // A row written before W′ existed has no such key (the type says it does): it reads as "not set".
    const profile = {
      ...row.data,
      wPrimeKj: row.data.wPrimeKj ?? null,
      updatedAt: new Date(row.updated_at).toISOString(),
    };
    // What is stored was valid when it was written; a row someone edited by hand is not served as if it were.
    const checked = validateProfile(profile);
    if (!checked.ok) {
      throw new Error(`the stored profile is not valid: ${checked.errors.slice(0, 3).join("; ")}`);
    }
    return checked.value;
  }

  return {
    async get() {
      return withEstimates(await read(db, false));
    },

    async update(patch) {
      const outcome = await db.transaction(async (tx): Promise<ProfileUpdate> => {
        const before = await read(tx, true);
        const applied = applyProfilePatch(before, patch);
        if (!applied.ok) throw new ProfilePatchError(applied.errors);
        const changed = PARTS.filter(
          (part) => canonicalJson(applied.value[part]) !== canonicalJson(before[part]),
        );
        if (changed.length === 0) return { profile: before, changed: [] };
        const stamp = now().toISOString();
        await tx.query(
          `INSERT INTO profile (id, data, updated_at) VALUES (true, $1::jsonb, $2::timestamptz)
           ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = EXCLUDED.updated_at`,
          [canonicalJson(withoutStamp(applied.value)), stamp],
        );
        return { profile: { ...applied.value, updatedAt: stamp }, changed: [...changed] };
      });
      if (outcome.changed.length > 0) bus.emit("profile-changed", { fields: outcome.changed });
      return { ...outcome, profile: await withEstimates(outcome.profile) };
    },
  };
}
