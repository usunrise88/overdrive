# ADR 0044: Multi-tenancy: one installation, several users

Status: accepted (the owner's decisions of 2026-11-24 and 2026-11-25). It replaces ADR 0015 in the parts that deferred
multi-user (see there). The code is built in stages A1, A2, B1, B2 and B3 (the modules, the tests, the migrations)
on `claude/sleepy-gates-zqbtwq`. **The production cutover is not done.** It waits for the owner's approval and for a rehearsal
on a copy of the dump. Until then production runs the single-user layout.

## Context

ADR 0015 kept multi-user to the last stage. The owner now wants: the model providers (LLM, TTS, BRouter) shared by the whole
installation; each user's data stored apart from every other user's; accounts created through the admin API only, with no
sign-up page; and an exact, checkable move of all data the owner has accumulated into the owner's own account. The tablet and
the phone apps hold only the server's address and a bearer token, so their owner tokens keep working after the move.

## Decision

### 1. Storage: one schema per user, one `shared` schema

- One PostgreSQL database with two kinds of schema.
  - `shared`: `users` (id, handle, passphrase hash, Telegram chat id, disabled_at), `user_tokens` (device and MCP tokens
    as SHA-256 digests), `user_settings` (per-user keys only), `oauth_families` (family to user), the OAuth tables
    (`oauth_clients`, `oauth_codes`, `oauth_tokens`), `bot_state`, and its own `schema_migrations`. Migrations in
    `server/migrations/shared/`.
  - `t_<id>`: every other table, unchanged, with its own `schema_migrations`. Tenant migrations are in `server/migrations/`.
- Why: the SQL of the single-user code stays as it is, and so do the unique keys (two users may each have a ride with the same
  id). A query that forgets `WHERE user_id` cannot leak another user's rows. Moving the owner is `ALTER TABLE … SET SCHEMA`,
  which changes only metadata: rows, FIT bytes, sequences, indexes and foreign keys are not rewritten.
- Tenant migration `0014_tenant_cleanup.sql` drops the copies of the OAuth and bot tables that 0003 and 0004 made in each
  user's schema. It was planned for stage A and moved to B2, because the OAuth and Telegram code had to read `shared` first.
- Each user's pool has at most two connections and `search_path` set to the user's schema (`server/src/db.ts`,
  `createPgSchemaDb`).

### 2. Configuration: three lists, never mixed

`server/src/tenancy/keys.ts` puts every configuration key in exactly one list (a test checks this):

- **Installation keys**: read from the environment only. The model providers, the bot token, the integration base URLs,
  `PUBLIC_BASE_URL`, `ADMIN_TOKEN`, `ADMIN_PORT`, the live coach limits, the Google client id and secret defaults.
- **Per-user keys**: read from `user_settings` only. `TIMEZONE`, the intervals.icu key and athlete id, Google refresh token and
  calendar, the Strava client id, secret and refresh token, reminders, summaries, insights, alerts, wellness, the live coach's
  language and switch, `TTS_VOICE` and `TTS_SPEED`, and similar.
- **Import-only keys**: `DEVICE_TOKENS`, `MCP_STATIC_TOKENS`, `OWNER_PASSPHRASE_HASH`, `TELEGRAM_OWNER_CHAT_ID`, `OWNER_HANDLE`.
  They are read by the move and by user creation, and never stored as settings.

There is no fallback from one list to another. A user without a Strava key has no Strava, even when the environment holds the
owner's key. A per-user key found in the environment is ignored, and its name (never its value) is logged. Each user's
configuration is built with the same Zod loader as before. An invalid configuration puts that user in the `failed` state
(the key names are logged); the other users keep running.

### 3. Sign-in

- The OAuth authorisation page asks for a **handle and a passphrase**. The passphrase is stored only as a scrypt hash made by
  `hash-passphrase` (ADR 0018). A user without a hash cannot sign in.
- An unknown handle gets the same answer as a wrong passphrase, and the same scrypt time (a fixed dummy hash).
- Lockout per handle (five wrong passphrases lock that handle; each lock is twice as long, up to a day), plus a cap on the
  failures of all handles together (`server/src/oauth/throttle.ts`). The counters are in memory, as before.
- `oauth_families` maps each authorisation to its user. The families of the single-user layout all go to user 1, so a
  connector authorised before the move keeps working without a new sign-in.

### 4. Tokens

- Device and MCP tokens are rows of `shared.user_tokens`: a name, a kind (`device` or `mcp`), a digest and an optional
  `revoked_at`. Names are unique per user, revoked ones included.
- The server keeps an in-memory map from digest to user, refreshed by the admin API. A disabled user's tokens are not served.
- A device token identifies the user and the device. The device's name is still written to `rides.uploaded_by`.

### 5. Telegram: one bot

- One bot token and one long-polling loop for the whole installation (`server/src/telegram/poller.ts`; Telegram allows one
  `getUpdates` consumer per token). Each update goes to the user whose `users.telegram_chat_id` equals its chat id.
- An update from a chat that no user has is logged with its chat id and ignored; its content is not logged.
- The offset is kept in `shared.bot_state`. Each user's bot runs with polling off: commands and notifications to its own chat.
  A FIT file sent to the bot is imported into the sender's schema.

### 6. Admin API: only from the server

- A separate Fastify app (`server/src/admin/app.ts`) on its own port, `ADMIN_PORT` (default 8081). It is meant to be bound to
  `127.0.0.1` and never published: no compose port, no Caddy route. Every request needs `Authorization: Bearer <ADMIN_TOKEN>`;
  the app refuses to start without the token. The owner reaches it from the server itself (`docker compose exec server`, or an
  SSH tunnel).
- Endpoints: create a user (handle, passphrase hash, Telegram chat, the name of the first device token, per-user settings);
  list users and their tokens; issue and revoke tokens; read settings (secret-looking values are masked as `***`) and write
  them (`null` removes one; unknown keys are refused; values are checked by the same schemas); set the Telegram chat; disable
  and enable; reload a user's services; delete a user (the confirmation is the handle; deleting the owner needs
  `<handle> allow-owner`). Deletion drops the schema and the rows in one transaction.
- A token is returned once, when it is created. Nothing the API logs or answers contains a token, a passphrase or a value.

### 7. The move of the owner's data (`server/src/tenancy/bootstrap.ts`)

The bootstrap runs before the migrations and detects the layout inside one transaction, under the migrations' advisory lock:
**fresh** (an empty database: creates `shared`, the owner and `t_1`), **legacy** (the single-user tables are in `public`: moves
them) or **multi** (nothing to move; the pending migrations run). The legacy move is one transaction:

1. **Preflight, read-only.** `public` holds exactly the 37 expected tables and `schema_migrations`, and no other object the
   move would leave behind or break. The first 13 migration files match the recorded checksums. `DEVICE_TOKENS` is not empty.
   The owner's environment is valid, and no token is in both `DEVICE_TOKENS` and `MCP_STATIC_TOKENS`. A refusal changes
   nothing.
2. **Snapshot before**: per table the row count and an MD5 of the rows; sequence values; column defaults; index and
   constraint definitions (without schema names).
3. **Create** `shared` and `t_1`, move the tables (all but the OAuth and bot tables to `t_1`, the rest to `shared`), and run the
   `shared` migration.
4. **Import the owner** from the environment: user 1 with the handle from `OWNER_HANDLE` (default `owner`), the passphrase hash,
   the Telegram chat, the device and MCP tokens under their old names, and the per-user keys through the same loader. The
   Strava seed hash therefore still matches a rotated Strava token. Every OAuth family found in the tables becomes user 1's.
5. **Guard**: `public.schema_migrations` is recreated with one row (version 1, name `rides`, checksum
   `moved-to-multi-tenant`). The old image compares checksums at start and refuses to run on this database, so it cannot start
   empty and accept rides into it.
6. **Snapshot after**, with the same function. Any difference, or a lost table, rolls the whole transaction back.

After the commit, the `shared` migrations run and then each user's (0014 in `t_1`, which then finds nothing to drop). Running
the bootstrap again changes nothing. Its report (counts, hashes, migration versions, imported key **names**, per-user keys
still in the environment) goes to the log.

**Process for the owner's data (not done).** A rehearsal on a copy first: two scratch PostgreSQL 18 containers from one dump,
with no external network, so that the Telegram poller and the Strava and intervals loaders cannot run twice. One runs the old
image, the other the new image with `MIGRATE_ONLY`. The two reports are compared, and the `GET /v1` responses and read-only MCP
tools are compared with the same token at the same moment. Then production: stop the server, take a fresh dump, run the new
image with `MIGRATE_ONLY`, check the report, start, smoke test. The owner's go-ahead is needed for the production step.

### 8. Not done

- More than one owner (more than one sign-in) for one user.
- A review of Strava's terms for several people, and a Strava app per user. A new Strava app serves one athlete until Strava
  reviews it. Each user needs their own app.
- Encryption of secrets at rest. `user_settings` and `strava_auth` hold their secrets in clear, as the single-user code does.
- The rehearsal and the production cutover.
- Anything in "Differences from the plan" below.

## Consequences

- Other users' health data goes to the owner's LLM endpoint, and it sits in the owner's backups together with their secrets.
  The users should know this. The owner's backups are whole-database dumps (deploy section 8), so every user's schema is in them.
- Each new user needs their own intervals.icu key, their own Strava app, a Google client that is out of the Testing mode (or
  listed as a tester, since a Testing client's refresh tokens last seven days), and a chat with the shared bot.
- **Rollback.** Restore the dump taken before the cutover (deploy section 9) and run the previous image. Everything uploaded
  after the cutover is lost, except what the tablet sends again. The plan expects the tablet to re-send rides by their ids; this
  is to be confirmed in the rehearsal.
  The guard row makes the old image refuse the moved database, so a rollback cannot silently start an empty one.
- The admin API is a second listener on the server. It is safe only while it stays on `127.0.0.1` and the port is not
  published, so a compose change that publishes it must be refused in review.

## Differences from the plan

The plan is `/home/ubuntu/.claude/plans/tranquil-chasing-pillow.md`. Where the code differs, the code is what is described above:

- **The start-up wiring is not on this branch.** `server/src/main.ts` and `compose.yaml` are unchanged. The server still
  starts the single-user path (`buildApp` with the single-tenant directory, `migrate(db)` on `public`). The bootstrap on start,
  `MIGRATE_ONLY`, the tenant registry, the shared Telegram poller, the admin listener and the compose settings (`ADMIN_TOKEN`,
  `ADMIN_PORT`) are built and tested as modules but are not started anywhere yet. This is the rest of stage C.
- **Fresh install** creates user 1 always, with or without `DEVICE_TOKENS` (the plan created it only with `DEVICE_TOKENS`).
  Such a user has no tokens until one is issued through the admin API.
- **Sign-in handle** is typed and has no default. The page is not filled in with `owner`. The single-user path asks for no
  handle at all until stage C is wired.
- **Passphrase after creation.** The admin API sets a passphrase only when a user is created (`passphraseHash`). There is no
  call to change it later.
- **No cap on the number of users** (`MAX_USERS`). Each schema has a pool of two connections and nothing limits the count.
- **A missing passphrase hash** does not stop the move: the owner is imported with no passphrase and cannot approve OAuth
  sign-ins until one is set (which the API cannot do yet, see above). The plan did not decide this case.
- **The report** is written to the log. The plan also wanted a file in `MIGRATE_ONLY` mode, which waits for `main.ts`.
- **Migration 0014** is in stage B2, not A (plan journal).
- **The image has no `curl`** (`server/Dockerfile` is `node:24-slim`). The admin commands in deploy section 22 call `curl`
  inside the container. Stage C adds it to the image or changes those commands to `node`.
