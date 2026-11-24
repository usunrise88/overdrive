import { type DeviceToken, describeConfigError, loadConfig } from "../config.ts";
import { assertSchemaName, type Db, type Queryable } from "../db.ts";
import { sha256Hex } from "../hash.ts";
import type { Logger } from "../log.ts";
import {
  applyMigration,
  ensureMigrationsTable,
  MIGRATION_LOCK_ID,
  type Migration,
  migrate,
  readMigrations,
  SHARED_MIGRATIONS_DIR,
} from "../migrate.ts";
import { IMPORT_ONLY_KEYS, PER_USER_KEYS } from "./keys.ts";
import {
  diffSnapshots,
  listTables,
  mergeSnapshots,
  quoteIdent,
  type SnapshotSummary,
  summarize,
  takeSnapshot,
} from "./snapshot.ts";

/**
 * Brings a database to the multi-tenant layout (one `shared` schema and one `t_<id>` schema per user), in one of three
 * ways, and says which. The one-time move of the owner's single-user data (everything in `public`) is the careful part:
 * one transaction, under the migrations' advisory lock, that checks first, moves second and compares third.
 */

export type Layout = "fresh" | "legacy" | "multi";

export const SHARED_SCHEMA = "shared";
/** The owner is the first user: id 1, schema `t_1`. */
export const OWNER_ID = 1;
export const OWNER_SCHEMA = "t_1";
export const DEFAULT_OWNER_HANDLE = "owner";
const HANDLE = /^[a-z][a-z0-9_-]{1,31}$/;

/** The tables of the OAuth server and the bot's state move to `shared`; every other table moves to the owner's schema. */
export const MOVED_TO_SHARED: readonly string[] = [
  "oauth_clients",
  "oauth_codes",
  "oauth_tokens",
  "bot_state",
];

/** The tenant migrations that produced the live single-user layout. Later ones run in `t_1` after the move. */
export const LEGACY_MIGRATION_COUNT = 13;

/**
 * The application tables of the live layout, which tenant migrations 0001 to 0013 create (a test derives the list by
 * applying them to an empty database and compares it with this one). `schema_migrations` is not in it.
 */
export const EXPECTED_TABLES: readonly string[] = [
  "activities",
  "activity_destinations",
  "alert_streaks",
  "bot_notifications",
  "bot_state",
  "calendar_events",
  "discussion_topics",
  "ftp_history",
  "ftp_suggestions",
  "insights",
  "live_cues",
  "notebook_versions",
  "oauth_clients",
  "oauth_codes",
  "oauth_tokens",
  "plan_event_cursor",
  "plan_events",
  "plan_history",
  "plan_sync_state",
  "planned_workout_extras",
  "planned_workouts",
  "profile",
  "ride_destinations",
  "ride_heart_rate",
  "ride_metrics",
  "ride_progress_stats",
  "ride_repeat_stats",
  "ride_routes",
  "ride_workouts",
  "rides",
  "routes",
  "strava_auth",
  "strava_uploads",
  "variant_rides",
  "wellness_daily",
  "wellness_records",
  "wellness_sync_state",
];

/** The one row of the guard table left in `public`: no real migration has this checksum, so the old image refuses to start. */
export const GUARD_CHECKSUM = "moved-to-multi-tenant";

/** The database is not in a state the bootstrap will change; nothing was changed. */
export class BootstrapRefused extends Error {
  constructor(reason: string) {
    super(`Bootstrap refused, nothing was changed: ${reason}`);
    this.name = "BootstrapRefused";
  }
}

export type Env = Readonly<Record<string, string | undefined>>;

export interface BootstrapOptions {
  /** A root connection: its `search_path` is the default, so the legacy tables are in `public`. */
  db: Db;
  /** The environment, as a plain record. The bootstrap never reads `process.env` or `.env`. */
  env: Env;
  log?: Logger;
  /**
   * A `Db` whose `search_path` is `schema`, for the migrations that run after the cutover. Default: a view on `db` that
   * starts every statement and transaction with `SET LOCAL search_path` (no extra connections).
   */
  schemaDb?: (schema: string) => Db;
  /** For tests only: runs inside the cutover's transaction after the move and before the comparison, to prove a failure rolls back. */
  beforeVerify?: (tx: Queryable) => Promise<void>;
}

export interface BootstrapReport {
  layout: Layout;
  /** Counts and content hashes of the tables before and after the move; only for the legacy cutover. */
  before: SnapshotSummary | null;
  after: SnapshotSummary | null;
  /** The migration versions this run applied, by schema. Empty lists are left out. */
  migrations: Record<string, number[]>;
  /** The owner this run created; null when the layout was already `multi`. */
  owner: { id: number; handle: string } | null;
  /** Names (never values) of the environment keys that were imported into the owner's rows. */
  importedKeys: string[];
  /** Per-user keys that are still set in the environment: from now on they are ignored. */
  perUserKeysStillInEnv: string[];
}

const blank = (value: string | undefined): boolean => value === undefined || value.trim() === "";

/** A `Db` for one schema on top of any `Db`: every statement and transaction starts with `SET LOCAL search_path`. */
export function schemaView(db: Db, schema: string): Db {
  assertSchemaName(schema);
  const set = `SET LOCAL search_path TO "${schema}"`;
  const inSchema = <T>(work: (tx: Queryable) => Promise<T>): Promise<T> =>
    db.transaction(async (tx) => {
      await tx.exec(set);
      return work(tx);
    });
  return {
    query: (sql, params) => inSchema((tx) => tx.query(sql, params)),
    exec: (sql) => inSchema((tx) => tx.exec(sql)),
    transaction: (work) => inSchema(work),
    close: async () => {},
  };
}

// ---------------------------------------------------------------------------------------------------------------------
// What is in `public`

interface PublicObject {
  relname: string;
  relkind: string;
  owned: boolean;
}

const KIND_NAMES: Record<string, string> = {
  v: "view",
  m: "materialised view",
  f: "foreign table",
  c: "composite type",
  S: "sequence that no table column owns",
};

/** Everything in `public` that is not one of `tables` (or an index or an owned sequence of one), as plain sentences. */
export async function foreignObjectsInPublic(
  q: Queryable,
  tables: ReadonlySet<string>,
): Promise<string[]> {
  const problems: string[] = [];
  const classes = await q.query<PublicObject>(
    `SELECT c.relname, c.relkind::text AS relkind,
            EXISTS (SELECT 1 FROM pg_depend d JOIN pg_class t ON t.oid = d.refobjid
                     WHERE d.objid = c.oid AND d.classid = 'pg_class'::regclass AND d.deptype IN ('a', 'i')
                       AND t.relkind IN ('r', 'p')) AS owned
       FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' ORDER BY c.relname`,
  );
  for (const c of classes.rows) {
    if (c.relkind === "r" || c.relkind === "p") {
      if (!tables.has(c.relname)) problems.push(`the table ${c.relname} is not one of ours`);
    } else if (c.relkind === "S") {
      if (!c.owned) problems.push(`the sequence ${c.relname} is not owned by a table column`);
    } else if (c.relkind !== "i" && c.relkind !== "I") {
      problems.push(`${KIND_NAMES[c.relkind] ?? `an object of kind ${c.relkind}`} ${c.relname}`);
    }
  }
  const functions = await q.query<{ proname: string }>(
    "SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' ORDER BY 1",
  );
  for (const f of functions.rows) problems.push(`the function or procedure ${f.proname}`);
  const types = await q.query<{ typname: string }>(
    `SELECT t.typname FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
      WHERE n.nspname = 'public' AND t.typtype <> 'c' AND t.typcategory <> 'A' ORDER BY 1`,
  );
  for (const t of types.rows) problems.push(`the type ${t.typname}`);
  const triggers = await q.query<{ tgname: string }>(
    `SELECT g.tgname FROM pg_trigger g JOIN pg_class c ON c.oid = g.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' AND NOT g.tgisinternal ORDER BY 1`,
  );
  for (const t of triggers.rows) problems.push(`the trigger ${t.tgname}`);
  const rules = await q.query<{ rulename: string }>(
    "SELECT rulename FROM pg_rules WHERE schemaname = 'public' ORDER BY 1",
  );
  for (const r of rules.rows) problems.push(`the rule ${r.rulename}`);
  const policies = await q.query<{ policyname: string }>(
    "SELECT policyname FROM pg_policies WHERE schemaname = 'public' ORDER BY 1",
  );
  for (const p of policies.rows) problems.push(`the row-level security policy ${p.policyname}`);
  return problems;
}

/** The tenant schemas (`t_<digits>`) and `shared`, as they exist now. */
async function serviceSchemas(q: Queryable): Promise<string[]> {
  const res = await q.query<{ nspname: string }>(
    "SELECT nspname FROM pg_namespace WHERE nspname = 'shared' OR nspname ~ '^t_[0-9]+$' ORDER BY 1",
  );
  return res.rows.map((r) => r.nspname);
}

const refuse = (reason: string): never => {
  throw new BootstrapRefused(reason);
};

/**
 * Decides which of the three layouts the database is in, or refuses. Call it with the migrations' lock held: the answer
 * is only good while nothing else changes the database.
 */
export async function detectLayout(q: Queryable): Promise<Layout> {
  const schemas = await serviceSchemas(q);
  const hasShared = schemas.includes(SHARED_SCHEMA);
  const tenantSchemas = schemas.filter((s) => s !== SHARED_SCHEMA);
  const rides = await q.query(
    "SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = 'rides' AND c.relkind = 'r'",
  );
  const hasRides = rides.rows.length > 0;

  if (hasShared && hasRides) {
    return refuse(
      "both the schema shared and the table public.rides exist: this is neither the old layout nor the new one",
    );
  }
  if (!hasShared && hasRides) {
    if (tenantSchemas.length > 0) {
      return refuse(
        `public.rides exists and so do the schemas ${tenantSchemas.join(", ")} but there is no schema shared`,
      );
    }
    return "legacy";
  }
  if (!hasShared) {
    if (tenantSchemas.length > 0) {
      return refuse(`the schemas ${tenantSchemas.join(", ")} exist but there is no schema shared`);
    }
    const problems = await foreignObjectsInPublic(q, new Set());
    if (problems.length > 0) {
      return refuse(
        `the database is not empty and is not the old layout (no public.rides): ${problems.join("; ")}`,
      );
    }
    return "fresh";
  }
  // The new layout: `shared` exists and the old tables are gone from `public`. Only the guard may be left there.
  const users = await q.query("SELECT to_regclass('shared.users')::text AS t");
  if ((users.rows[0] as { t: string | null } | undefined)?.t == null) {
    return refuse("the schema shared exists but has no users table");
  }
  const problems = await foreignObjectsInPublic(q, new Set(["schema_migrations"]));
  if (problems.length > 0)
    return refuse(`public holds more than the guard table: ${problems.join("; ")}`);
  // (A failed statement would abort the caller's transaction, so look before reading.)
  const hasGuard = await q.query("SELECT to_regclass('public.schema_migrations') AS t");
  if ((hasGuard.rows[0] as { t: string | null } | undefined)?.t != null) {
    const guard = await q.query<{ version: number; name: string; checksum: string }>(
      "SELECT version, name, checksum FROM public.schema_migrations",
    );
    const [only] = guard.rows;
    if (
      guard.rows.length > 1 ||
      (only !== undefined &&
        (only.version !== 1 || only.name !== "rides" || only.checksum !== GUARD_CHECKSUM))
    ) {
      return refuse("public.schema_migrations is not the guard table the cutover leaves behind");
    }
  }
  return "multi";
}

// ---------------------------------------------------------------------------------------------------------------------
// The owner, from the environment

/** What the owner's env says, after the configuration code's own checks. Holds secrets: never put it in a report. */
export interface OwnerImport {
  handle: string;
  passphraseHash: string | null;
  chatId: string | null;
  devices: DeviceToken[];
  mcpTokens: DeviceToken[];
  /** Per-user settings: key and the value as text, after the configuration code's trim and transform. */
  settings: { key: string; value: string }[];
  /** Names of the keys that are imported. */
  importedKeys: string[];
}

function settingText(value: unknown): string | null {
  if (Array.isArray(value)) return value.join(",");
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return null;
}

/**
 * Reads the owner's data out of `env` with the configuration code itself (so what the server accepts today is what is
 * imported), and refuses on anything invalid. Messages name keys and never repeat a value.
 */
export function parseOwnerEnv(env: Env): OwnerImport {
  const handle = blank(env.OWNER_HANDLE) ? DEFAULT_OWNER_HANDLE : (env.OWNER_HANDLE ?? "").trim();
  if (!HANDLE.test(handle)) {
    return refuse(
      "OWNER_HANDLE must be 2 to 32 characters: a lowercase letter, then lowercase letters, digits, _ or -",
    );
  }
  const picked: Record<string, string | undefined> = {
    DATABASE_URL: "postgres://bootstrap.invalid/none",
  };
  for (const key of [...IMPORT_ONLY_KEYS, ...PER_USER_KEYS]) {
    if (key !== "OWNER_HANDLE" && !blank(env[key])) picked[key] = env[key];
  }
  let config: Record<string, unknown>;
  try {
    config = loadConfig(picked) as unknown as Record<string, unknown>;
  } catch (err) {
    return refuse(`the environment is invalid:\n${describeConfigError(err)}`);
  }
  const devices = config.DEVICE_TOKENS as DeviceToken[];
  const mcpTokens = config.MCP_STATIC_TOKENS as DeviceToken[];
  for (const mcp of mcpTokens) {
    const hash = sha256Hex(mcp.token);
    const clash = devices.find((d) => sha256Hex(d.token) === hash);
    if (clash !== undefined) {
      return refuse(
        `the same token is in DEVICE_TOKENS (${clash.name}) and in MCP_STATIC_TOKENS (${mcp.name})`,
      );
    }
  }
  const settings: { key: string; value: string }[] = [];
  for (const key of PER_USER_KEYS) {
    if (blank(env[key])) continue;
    const text = settingText(config[key]);
    if (text !== null) settings.push({ key, value: text });
  }
  const importedKeys = [
    ...(devices.length > 0 ? ["DEVICE_TOKENS"] : []),
    ...(mcpTokens.length > 0 ? ["MCP_STATIC_TOKENS"] : []),
    ...(typeof config.OWNER_PASSPHRASE_HASH === "string" ? ["OWNER_PASSPHRASE_HASH"] : []),
    ...(typeof config.TELEGRAM_OWNER_CHAT_ID === "string" ? ["TELEGRAM_OWNER_CHAT_ID"] : []),
    ...(blank(env.OWNER_HANDLE) ? [] : ["OWNER_HANDLE"]),
    ...settings.map((s) => s.key),
  ];
  return {
    handle,
    passphraseHash:
      typeof config.OWNER_PASSPHRASE_HASH === "string" ? config.OWNER_PASSPHRASE_HASH : null,
    chatId:
      typeof config.TELEGRAM_OWNER_CHAT_ID === "string" ? config.TELEGRAM_OWNER_CHAT_ID : null,
    devices,
    mcpTokens,
    settings,
    importedKeys,
  };
}

/** Per-user keys that are set in `env`. */
export function perUserKeysIn(env: Env): string[] {
  return PER_USER_KEYS.filter((key) => !blank(env[key]));
}

/** Writes the owner into `shared` (inside the caller's transaction): user 1, tokens as digests, settings, OAuth families. */
export async function importOwner(tx: Queryable, owner: OwnerImport): Promise<void> {
  const created = await tx.query<{ id: string }>(
    "INSERT INTO shared.users (handle, passphrase_hash, telegram_chat_id) VALUES ($1, $2, $3) RETURNING id::text AS id",
    [owner.handle, owner.passphraseHash, owner.chatId],
  );
  if (created.rows[0]?.id !== String(OWNER_ID)) {
    throw new Error(
      `The owner got id ${created.rows[0]?.id}, not ${OWNER_ID}: shared.users was not empty`,
    );
  }
  const tokens: [DeviceToken, "device" | "mcp"][] = [
    ...owner.devices.map((d): [DeviceToken, "device"] => [d, "device"]),
    ...owner.mcpTokens.map((d): [DeviceToken, "mcp"] => [d, "mcp"]),
  ];
  for (const [token, kind] of tokens) {
    await tx.query(
      "INSERT INTO shared.user_tokens (token_sha256, kind, name, user_id) VALUES ($1, $2, $3, $4)",
      [sha256Hex(token.token), kind, token.name, OWNER_ID],
    );
  }
  for (const { key, value } of owner.settings) {
    await tx.query("INSERT INTO shared.user_settings (user_id, key, value) VALUES ($1, $2, $3)", [
      OWNER_ID,
      key,
      value,
    ]);
  }
  await tx.query(
    `INSERT INTO shared.oauth_families (family_id, user_id)
     SELECT family_id, $1::bigint FROM (
       SELECT family_id FROM shared.oauth_codes UNION SELECT family_id FROM shared.oauth_tokens
     ) f ON CONFLICT DO NOTHING`,
    [OWNER_ID],
  );
}

// ---------------------------------------------------------------------------------------------------------------------
// The three ways

async function lock(tx: Queryable): Promise<void> {
  await tx.query("SELECT pg_advisory_xact_lock($1)", [MIGRATION_LOCK_ID]);
}

/** Creates `shared` and applies its first migration, with its bookkeeping, inside the caller's transaction. */
async function createShared(tx: Queryable, migrations: Migration[]): Promise<void> {
  const first = migrations.find((m) => m.version === 1);
  if (first === undefined) throw new Error("shared/0001 is missing");
  await tx.exec(`CREATE SCHEMA IF NOT EXISTS ${SHARED_SCHEMA}`);
  await tx.exec(`SET LOCAL search_path TO ${SHARED_SCHEMA}`);
  await ensureMigrationsTable(tx);
  await applyMigration(tx, first);
}

/** The legacy preflight: read-only; throws {@link BootstrapRefused} at the first thing that is wrong. */
export async function preflightLegacy(q: Queryable, env: Env): Promise<OwnerImport> {
  const expected = new Set([...EXPECTED_TABLES, "schema_migrations"]);
  const present = await listTables(q, "public");
  const missing = [...expected].filter((t) => !present.includes(t));
  const extra = present.filter((t) => !expected.has(t));
  if (missing.length > 0) refuse(`public lacks the tables ${missing.join(", ")}`);
  if (extra.length > 0) refuse(`public holds the tables ${extra.join(", ")}, which are not ours`);
  const problems = await foreignObjectsInPublic(q, expected);
  if (problems.length > 0)
    refuse(
      `public holds objects that the move would leave behind or break: ${problems.join("; ")}`,
    );

  const files = (await readMigrations()).slice(0, LEGACY_MIGRATION_COUNT);
  const rows = await q.query<{ version: number; name: string; checksum: string }>(
    "SELECT version, name, checksum FROM public.schema_migrations ORDER BY version",
  );
  if (rows.rows.length !== files.length) {
    refuse(
      `public.schema_migrations holds ${rows.rows.length} versions, expected 1 to ${files.length}`,
    );
  }
  for (const [index, file] of files.entries()) {
    const row = rows.rows[index];
    if (row?.version !== file.version || row.name !== file.name || row.checksum !== file.checksum) {
      refuse(
        `public.schema_migrations differs from the migration file ${file.version} (${file.name}): the database was not made by these migrations`,
      );
    }
  }
  const owner = parseOwnerEnv(env);
  if (owner.devices.length === 0) {
    refuse("DEVICE_TOKENS is empty: the devices that use this database would lose access");
  }
  return owner;
}

interface Outcome {
  layout: Layout;
  owner?: OwnerImport;
  before?: SnapshotSummary;
  after?: SnapshotSummary;
}

/** The legacy move, inside the caller's transaction (which holds the lock and has detected the layout). */
async function moveLegacy(
  tx: Queryable,
  env: Env,
  shared: Migration[],
  beforeVerify: BootstrapOptions["beforeVerify"],
): Promise<Outcome> {
  const owner = await preflightLegacy(tx, env);
  const before = await takeSnapshot(tx, "public");
  const toTenant = [
    ...EXPECTED_TABLES.filter((t) => !MOVED_TO_SHARED.includes(t)),
    "schema_migrations",
  ];

  await tx.exec(`CREATE SCHEMA ${SHARED_SCHEMA}; CREATE SCHEMA ${OWNER_SCHEMA}`);
  for (const table of toTenant) {
    await tx.exec(`ALTER TABLE public.${quoteIdent(table)} SET SCHEMA ${OWNER_SCHEMA}`);
  }
  for (const table of MOVED_TO_SHARED) {
    await tx.exec(`ALTER TABLE public.${quoteIdent(table)} SET SCHEMA ${SHARED_SCHEMA}`);
  }
  await createShared(tx, shared);
  await importOwner(tx, owner);
  await tx.exec(`
      CREATE TABLE public.schema_migrations (
        version integer PRIMARY KEY,
        name text NOT NULL,
        checksum text NOT NULL,
        applied_at timestamptz NOT NULL DEFAULT now()
      )`);
  await tx.query(
    "INSERT INTO public.schema_migrations (version, name, checksum) VALUES (1, 'rides', $1)",
    [GUARD_CHECKSUM],
  );

  await beforeVerify?.(tx);
  const after = mergeSnapshots(
    await takeSnapshot(tx, OWNER_SCHEMA, toTenant),
    await takeSnapshot(tx, SHARED_SCHEMA, MOVED_TO_SHARED),
  );
  const differences = diffSnapshots(before, after);
  if (differences.length > 0) {
    throw new Error(`The move changed the data, rolling back:\n${differences.join("\n")}`);
  }
  if (Object.keys(after.tables).length !== EXPECTED_TABLES.length + 1) {
    throw new Error("The move lost a table, rolling back");
  }
  return { layout: "legacy", owner, before: summarize(before), after: summarize(after) };
}

/**
 * Brings the database to the multi-tenant layout, whatever it is in now: an empty database gets `shared`, the owner and
 * `t_1`; the single-user layout is moved (verified, all or nothing); the new layout is left as it is. Then every pending
 * migration runs: `shared` first, then each user's schema. Safe to run again and again.
 */
export async function runBootstrap(options: BootstrapOptions): Promise<BootstrapReport> {
  const { db, env, log } = options;
  const schemaDb = options.schemaDb ?? ((schema: string) => schemaView(db, schema));
  const shared = await readMigrations(SHARED_MIGRATIONS_DIR);
  const migrations: Record<string, number[]> = {};
  const note = (schema: string, versions: number[]) => {
    if (versions.length > 0) migrations[schema] = [...(migrations[schema] ?? []), ...versions];
  };

  // One transaction under the lock, whatever the layout: it is detected inside, so another instance that got there first
  // is seen as `multi`. A refusal or an error rolls everything back.
  const outcome = await db.transaction(async (tx): Promise<Outcome> => {
    await lock(tx);
    const layout = await detectLayout(tx);
    if (layout === "legacy") {
      log?.info({}, "bootstrap: the single-user layout was found, moving it");
      return moveLegacy(tx, env, shared, options.beforeVerify);
    }
    if (layout === "fresh") {
      log?.info({}, "bootstrap: an empty database, creating the schemas and the owner");
      const owner = parseOwnerEnv(env);
      await createShared(tx, shared);
      await importOwner(tx, owner);
      await tx.exec(`CREATE SCHEMA ${OWNER_SCHEMA}`);
      return { layout, owner };
    }
    return { layout };
  });
  if (outcome.layout !== "multi") note(SHARED_SCHEMA, [1]);

  // Outside the transaction and without the lock: `migrate` takes it in each of its own transactions.
  note(SHARED_SCHEMA, await migrate(schemaDb(SHARED_SCHEMA), SHARED_MIGRATIONS_DIR));
  const users = await db.query<{ id: string }>(
    "SELECT id::text AS id FROM shared.users ORDER BY id",
  );
  for (const { id } of users.rows) {
    const schema = `t_${id}`;
    const exists = await db.query("SELECT 1 FROM pg_namespace WHERE nspname = $1", [schema]);
    if (exists.rows.length === 0) throw new Error(`User ${id} has no schema ${schema}`);
    note(schema, await migrate(schemaDb(schema)));
  }
  log?.info({ layout: outcome.layout }, "bootstrap: done");

  return {
    layout: outcome.layout,
    before: outcome.before ?? null,
    after: outcome.after ?? null,
    migrations,
    owner: outcome.owner === undefined ? null : { id: OWNER_ID, handle: outcome.owner.handle },
    importedKeys: outcome.owner?.importedKeys ?? [],
    perUserKeysStillInEnv: perUserKeysIn(env),
  };
}
