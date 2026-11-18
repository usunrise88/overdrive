# ADR 0014: Rides leave the tablet: deployment, storage, authentication and the intake API

Status: accepted (Phase 2, stage P2-1, 2026-11-18).

## Context

Phase 1 ends with every stored ride queued in the tablet's outbox and nothing uploaded (ADR 0012). Phase 2, stage 1 makes the
queue drain: a ride finished on the tablet appears on our server, then in intervals.icu, with no manual step. This ADR fixes what
the kickoff (`docs/phase2-kickoff.md`) left to the first stage, with the owner's answers where only the owner could give them.

## Decision

### 1. Deployment (owner's answers)

- The service runs on the owner's server at **`https://overdrive.llmto.day`**, behind the host's Caddy, which terminates TLS and
  proxies to `127.0.0.1:${SERVER_PORT}` (compose publishes the container on loopback only). The Caddy site file is
  `deploy/overdrive.llmto.day.caddy`. The tablet is given exactly this address in Settings, Sync.
- The host is shared with other projects, so compose's ports are set in `.env` (not the defaults 8080 and 5432, which are taken
  there). `.env` is ignored by git.

### 2. Storage: PostgreSQL

The kickoff suggested SQLite; spec §7.2 and `compose.yaml` already chose **PostgreSQL**, the host already runs Docker, and the
later stages (statistics, the 14-day plan cache, the scheduler) are better served by it. SQLite is not used.

- Driver `pg`; schema changes are numbered SQL files in `server/migrations/`, applied at start in one transaction each and
  recorded in a `schema_migrations` table. No ORM.
- Tests run the same SQL against **PGlite** (in-process PostgreSQL, no Docker daemon needed), so `pnpm test` stays hermetic.
  A test against the real server image runs only in CI.

### 3. What the tablet uploads

The ride record (spec §8.2, schema 1) is the source and the FIT is generated from it by `FitWriter` in Kotlin. The service has
no FIT writer and needs a FIT to forward to intervals.icu and Strava, so **the tablet uploads both**, as two idempotent
requests per ride, keyed by the ride id (`ride.id`, already in the record):

| Request | Body | Meaning |
|---|---|---|
| `PUT /v1/rides/{rideId}` | the ride record JSON (`ride-record.schema.json`) | store the record |
| `PUT /v1/rides/{rideId}/fit` | the FIT bytes, `application/vnd.ant.fit` | store the file |

- The ride id is **not a UUID**: the ride engine's ids are `ride-<start millis>` (ADR 0008) and the contract fixtures use others. The
  service accepts 1 to 128 characters from `A-Z a-z 0-9 . _ -`, starting with a letter or digit (`RIDE_ID_PATTERN` in
  `contracts`), and stores the id as `text`. A path that does not match is `400`.
- A ride counts as **uploaded** (and the outbox acknowledges it) only when the server has answered success for both.
- `{rideId}` must equal `ride.id` in the record; a mismatch is `422`. A second `PUT` of the same body answers `200` and changes
  nothing; a **different** body for an id already stored is `409` (a ride is immutable once stored).
- The record is validated with the schema and the structural rules of §8.2 (shared fixtures). The FIT is checked only for its
  header, CRC and size (at most 8 MiB); the service does not parse it.
- Idempotency across ride ids follows spec §5.5: a ride is identified by its **start time** as well, and a record whose start
  time matches a stored ride under another id is `409` with the stored id.
- Responses are JSON: `{ "rideId", "status": "stored" | "already-stored" }`. Errors are `{ "error": { "code", "message" } }` with
  a stable `code`.
- The sync API is versioned in the path (`/v1`). Spec §8.3 is updated to say so.

### 4. Authentication

A **long random bearer token per device**, issued by the owner and typed into Settings, Sync (already there):
`Authorization: Bearer <token>`.

- The server environment holds `DEVICE_TOKENS`, a comma-separated list of `name:token` (for example `tablet:...,companion:...`).
  Tokens are at least 32 characters; startup fails otherwise. Comparison is constant-time. The name goes into logs and the
  `ride.uploaded_by` column; the token is never logged (the logger redacts the `authorization` header).
- No token configured means every `/v1` request is `401`; `/healthz` stays open.
- `docs/` gets a one-line way to make a token (`openssl rand -base64 36`); no token is ever in the repository or a chat.
- OAuth for the MCP server (spec §5.2) is a later stage and does not change this.

### 5. The intervals.icu step comes after intake, never inside it

Intake answers as soon as the record and the file are stored. Pushing to intervals.icu is a separate step driven by a table
(`ride_destinations`: ride id, destination, state, attempts, last error, external id), retried with backoff, and idempotent.
A failure there never fails or delays the tablet's upload. The API key lives in the server environment
(`INTERVALS_API_KEY`, `INTERVALS_ATHLETE_ID`). Its details are the second half of stage P2-1, in its own ADR section when built.

### 6. The tablet

`INTERNET` permission is added to the ride app's manifest (and nothing else: no Play Services). A `HttpSyncProvider` in
`android/data` (`HttpURLConnection`, no new dependency) implements `SyncProvider`: TLS only (a plain `http://` address is
accepted in Settings for a bench on the local network and flagged in the UI), timeouts, `401` is reported as "token rejected"
and not retried until the token changes, `5xx` and network errors are `RetryLater` (the coordinator already backs off), `4xx` other
than `401`/`409`-with-same-content are permanent errors shown once in Settings, not retried in a loop. It reads the record and the
FIT from where the ride is stored; the outbox entry stays free of payload (ADR 0011).

### 7. Settled in the implementation (stage P2-1a)

- **Same body** means the same content: a record is compared by the SHA-256 of its canonical JSON (keys sorted, no whitespace),
  so a resend laid out differently is still a repeat; a reading that is `null` in one body and absent in the other is a different
  body. A FIT is compared by the SHA-256 of its bytes. Both hashes are stored (`record_sha256`, `fit_sha256`).
- **Statuses**: `201` stored, `200` identical repeat; `400` malformed JSON or ride id; `401`; `404` unknown path, or a FIT for a
  record not stored yet; `409` a different record or file for a stored id, or a stored start time under another id; `413`;
  `415`; `422` invalid record, id mismatch or invalid FIT; `500`. The `code`s are a closed list in
  `contracts/schema/api-error.schema.json` (`API_ERROR_STATUS` in `@overdrive/contracts`).
- **Limits**: 16 MiB for the record (a day-long ride is about 20 MB of JSON, an hour under 1 MB), 8 MiB for the FIT. Authentication
  runs before the body is read, so an unauthenticated request is `401` whatever its size.
- **FIT framing**: 12- or 14-byte header, `.FIT`, data size such that header + data + 2 equals the body (chained FIT files are
  refused), header CRC when it is not zero, file CRC, and at least one data byte.
- **Tokens**: from `A-Z a-z 0-9 + / = . _ ~ -` (base64 and base64url), at most 256 characters, names and tokens unique.
- **`ride_destinations.state`** is `pending`, `done` or `failed`; nothing writes the table yet.
- **Compose** passes `DATABASE_URL` (built from `POSTGRES_PASSWORD`, which must therefore be URL-safe) and `DEVICE_TOKENS`, and the
  server waits for the database to be healthy.

### 8. Settled in the implementation (stage P2-1b, the tablet)

- **Where it lives.** The provider's logic is in `core` (`HttpSyncProvider`, over an `HttpTransport` interface and a
  `RidePayloads` source, tested against a scripted transport); `android/data` has the `HttpURLConnection` transport
  (`UrlConnectionTransport`: 10 s to connect, 30 s to wait for the answer, a fixed-length streamed body, no redirects followed,
  nothing logged) and `StoredRidePayloads`. The tablet already keeps `<id>.json` and `<id>.fit` (ADR 0008), so **both are sent as
  stored**; the FIT is not regenerated. A ride whose files are gone or damaged, or larger than the limits of section 7, is a
  refusal of that ride, not a retry.
- **The ride's own id.** The outbox entry holds the file-safe form of the id; the path uses `ride.id` read from the record, which
  is what the service keys by and checks. An id the service would not accept is refused before anything is sent.
- **What counts as success.** 200 or 201 **and** a body that is the service's upload response for this ride id. A bare 200 (a
  proxy, the wrong server) is `RetryLater("That is not the Overdrive service")`, so a ride is never forgotten on a guess.
- **Status table** (beyond section 6): `401` is `TokenRejected`; `408`, `429`, `5xx`, no answer, a redirect (a `PUT` cannot follow
  one) and any other unexpected status are `RetryLater` with a plain reason; `409`, `422` and every other `4xx` are `Refused` with
  the service's message; **one exception**, a `404 ride_not_found` on the FIT right after the record was stored (the service lost
  it) is a retry, since the next attempt sends both again. A token that is not visible ASCII is `TokenRejected` without a request.
- **A rejected token** is remembered by the provider with the address and token it was rejected for, so nothing is sent while
  they are unchanged, however often the coordinator is kicked. A changed address or token, or **Send now**, asks again.
- **Refused rides** stay in the outbox (a ride is never dropped for a refusal) and are skipped by the coordinator for the life of
  the process: no retry loop, no hold-up of the rides behind. They are listed in `SyncCoordinator.report` for Settings. A new
  process tries each once more (a server fixed meanwhile may take it); a changed setting or **Send now** does too. The refusal is
  **not persisted**: the cost is one extra request pair per refused ride at each start, and the outbox journal (ADR 0012) stays as
  it is.
- **Settings take effect without a restart.** The provider reads the address and token at every upload; the container kicks the
  coordinator 1.5 s after the last change (typing a token is one change per letter, and each would otherwise be a request with a
  partial token). The last success time is kept in `filesDir/sync/last-upload` (one number, written atomically).
- **Plain `http://`.** It stays accepted and is flagged "Not encrypted" in Settings. On Android 9 and later it only works because
  the manifest sets `android:usesCleartextTraffic="true"`, so the ride app permits cleartext to the one address the owner typed
  (it makes no other request). Removing the attribute makes the upload https-only; nothing else changes.
- **Not decided here (the real tablet and server).** The Android `HttpURLConnection` is OkHttp underneath and may answer a streamed
  `401` differently from the JDK used in the tests (the status is read the same way; the body of a 401 is not needed).
  Early-closed connections from a proxy that answers before it has read the body show as "The connection to the service was cut"
  and are retried.
- **Why there was no answer is told, not guessed (v0.2.0 on the tablet said only "Service unreachable" while the tablet's browser
  reached the service and the service saw no request).** `TransportFailure` has a kind for each cause the owner can act on, and
  `TransportException.hint` carries a short technical hint, shown in brackets after the plain words:

  | Kind | Words | Comes from |
  |---|---|---|
  | `NameNotFound` | "Cannot find the service address (DNS)" | `UnknownHostException` |
  | `NetworkBlocked` | "The system blocks this app's network access" | `EACCES`, `EPERM` ("Permission denied") |
  | `NoNetwork` | "No network connection" | `ENETUNREACH`, `ENETDOWN` |
  | `Refused` | "The service refused the connection" | `ECONNREFUSED` |
  | `Dropped` | "The connection to the service was cut" | `ECONNRESET`, `ECONNABORTED`, `EPIPE` |
  | `Unreachable` | "Service unreachable" | any other socket error (the fallback) |
  | `TimedOut`, `SecureConnection`, `Other` | as before | |

  The system's error name is read from the `errno` of the `android.system.ErrnoException` in the cause chain, else from the text of
  the chain's messages (`socket failed: EACCES (Permission denied)`), else from the error's plain phrase (the JVM says only
  "Connection refused"). The hint is the exception's class and, if it is on a fixed whitelist of error names, that name:
  `SocketException · EACCES`, `UnknownHostException · EAI_NODATA`. **Never an address, a host name or message text**: those messages
  hold them. Code: `NetworkFailures` in `android/data`.

## Consequences

- One extra file per ride on the wire (the FIT), in exchange for no FIT writer in TypeScript and no regeneration on the server.
- The server can recompute anything from the record; the FIT is the artefact the integrations forward.
- Moving to another host later changes only the address typed into Settings and the Caddy file.
