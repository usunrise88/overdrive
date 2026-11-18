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
  /** The stored profile, or the defaults while nothing has been set. */
  get(): Promise<Profile>;
  /**
   * Applies a patch (`profile-patch.schema.json`) atomically. Throws {@link ProfilePatchError} and
   * changes nothing when it does not make a valid profile. A patch that changes nothing stores
   * nothing and announces nothing.
   */
  update(patch: unknown): Promise<ProfileUpdate>;
}

type Stored = Omit<Profile, "updatedAt">;

const PARTS = ["ftp", "wPrimeKj", "zones", "weeklyAvailability", "goals", "physics"] as const;

function withoutStamp(profile: Profile): Stored {
  const { updatedAt: _stamp, ...rest } = profile;
  return rest;
}

export function createProfileStore(db: Db, bus: EventBus, now: () => Date): ProfileStore {
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
    get: () => read(db, false),

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
      return outcome;
    },
  };
}
