# ADR 0018: The MCP server, its OAuth server, and the profile

Status: accepted (Phase 2, stage P2-3, server side, 2026-11-18).

## Context

Spec §5.2 asks for a remote MCP server, reachable from desktop and mobile AI clients, with the Phase 2 tools of §8.4 (plan,
review, profile), and §8.3 lists `GET`/`PATCH /v1/profile`. This ADR records how the service does both, written **without a real
MCP client or account to try it on**: the tests drive the server with the official SDK's own client (`@modelcontextprotocol/sdk`
1.32.2, which does discovery, dynamic registration, PKCE and refresh the way a client built on it would) against a fake
intervals.icu (ADR 0016). That proves the server agrees with the specifications and with the SDK, not with Claude's apps. The last
section lists what only a real client settles.

The specifications it follows: the MCP authorization specification (revision 2025-11-25: protected resource metadata, resource
indicators, PKCE, redirect rules, token audience), OAuth 2.1 (draft 13), RFC 9728 (protected resource metadata), RFC 8414
(authorization server metadata), RFC 7591 (dynamic client registration), RFC 7636 (PKCE), RFC 8707 (resource indicators), RFC 7009
(revocation), RFC 9207 (the `iss` parameter), RFC 8252 (loopback redirects), RFC 6750 (bearer challenges).

## Decision

### 1. Shape of the code

One deployable, new modules next to the existing ones, none importing another's insides (`server/README.md`, "Modules"):

| Module | Role |
|---|---|
| `src/mcp/` | `index.ts` (the plugin and the on/off switch), `routes.ts` (`/mcp` and its bearer check), `server.ts` (one `McpServer` per request), `tool.ts` (`defineTool`, `ToolError`), `schemas.ts` (the workout shape for tools), `tools/plan.ts`, `tools/rides.ts`, `tools/profile.ts` |
| `src/oauth/` | `routes.ts` (metadata, registration, authorization page, token, revocation), `store.ts` (PostgreSQL), `passphrase.ts` (scrypt), `throttle.ts` (lockout and rate limit), `redirect.ts`, `pages.ts` |
| `src/profile/` | `store.ts` (`ProfileStore`), `routes.ts` (`GET`/`PATCH /v1/profile`) |
| `src/ride-store.ts`, `src/ride-workout.ts`, `src/ride-stats.ts` | Reads of stored rides and the copy of the workout a ride was played from; the pure statistics and compliance code |
| `src/plan/entries.ts`, `src/plan/summary.ts` | One reading of a calendar event as a plan entry (the refresher and the tools use it); duration, load estimate and outline of a native workout |

`services.ts` gains three members, nothing else changes in it: `profile` (`ProfileStore`), `rides` (`RideStore`) and `rideWorkouts`.
`events.ts` gains `profile-changed` (`{ fields }`, the top-level parts that changed). The intervals.icu client gains `getEvent` (one
event of any category, `null` for a 404). The OAuth server does not use the SDK's Express-based `mcpAuthRouter`: it is a few hundred
lines over Fastify and Postgres with the service's own error handling, and keeps Express out of the request path (the SDK still
brings its dependencies into the image; the SDK's Streamable HTTP transport takes Node's `IncomingMessage`/`ServerResponse`, so
the route hands it `request.raw` and `reply.raw`).

### 2. The switch

The MCP server and the OAuth server exist **only when `PUBLIC_BASE_URL` and `OWNER_PASSPHRASE_HASH` are both set**. Otherwise no route
of theirs exists (`/mcp`, `/.well-known/oauth-*`, `/oauth/*` are the ordinary `404 not_found`) and the log says why once:
`MCP server is off: OWNER_PASSPHRASE_HASH is not set (see docs/deploy.md to turn it on)`. `MCP_STATIC_TOKENS` alone does not turn
it on. With it on, the log says where (`MCP server is on`, the endpoint, the number of static tokens). `PUBLIC_BASE_URL` is the
issuer and the origin of every address in the metadata (`https://overdrive.llmto.day`): `https`, or `http` for `localhost`,
`127.0.0.1` and `[::1]` only (the tests and development); an origin with no path, query, fragment or credentials.

The plain passphrase is **never configured**. The brief named `OWNER_PASSPHRASE` beside the hash; a plain value in the environment is
what a hash is there to avoid, so the server reads only `OWNER_PASSPHRASE_HASH`, and warns at start if `OWNER_PASSPHRASE` is set
(never printing it).

### 3. The transport

Streamable HTTP at `POST /mcp`, **stateless**: no session id, a new `McpServer` and transport for each request, answers as plain
JSON (`enableJsonResponse`) rather than an SSE stream. `GET` and `DELETE` answer `405` with `Allow: POST` (after authentication): there
is no session to end and the server starts no stream of its own. The body is read as text (1 MiB at most) and parsed in the route, so
a body that is not JSON is answered in JSON-RPC's words (`-32700`), not the service's error body. No `Origin` check and no CORS on
`/mcp`: it needs a bearer token a browser page does not have, and the clients meant here (Claude's apps, native clients) are not
browser pages. The discovery documents do carry `Access-Control-Allow-Origin: *`; a browser-based client (the MCP Inspector) is not
supported and would need CORS on the other endpoints.

### 4. The OAuth server

One owner, so there is one user and no user database: the owner approves a client by typing a passphrase.

| Endpoint | |
|---|---|
| `GET /.well-known/oauth-protected-resource` and `/mcp`-suffixed | RFC 9728 metadata: `resource` is `<base>/mcp`, `authorization_servers` is `[<base>]`, `scopes_supported` `["mcp"]`, `bearer_methods_supported` `["header"]` |
| `GET /.well-known/oauth-authorization-server` | RFC 8414 metadata: the endpoints below, `response_types_supported ["code"]`, `grant_types_supported ["authorization_code","refresh_token"]`, **`code_challenge_methods_supported ["S256"]`**, token and revocation authentication `none`, `client_secret_basic`, `client_secret_post`, `authorization_response_iss_parameter_supported true`, `client_id_metadata_document_supported false` |
| `POST /oauth/register` | RFC 7591 dynamic registration, open to the internet and therefore limited (below) |
| `GET`/`POST /oauth/authorize` | The passphrase page and its answer |
| `POST /oauth/token` | `authorization_code` (with `code_verifier`) and `refresh_token` |
| `POST /oauth/revoke` | RFC 7009 |

An unauthenticated or wrongly authenticated request to `/mcp` is `401` with
`WWW-Authenticate: Bearer resource_metadata="<base>/.well-known/oauth-protected-resource/mcp", scope="mcp"` (plus
`error="invalid_token"` when a token was sent), which is how a client finds the rest. Both well-known addresses of the resource
serve the same document, whose `resource` is the MCP endpoint (a client checks it against the address it was given; the SDK's does).

**Registration.** `redirect_uris` (1 to 5) must be `https`, or `http` on `localhost`/`127.0.0.1`/`[::1]` (the specification's rule): a
custom scheme (`cursor://…`) is refused. `token_endpoint_auth_method` is `none`, `client_secret_basic` (the RFC's default when
unsaid) or `client_secret_post`; a confidential client gets a secret, shown once and stored as a SHA-256 digest. `grant_types`,
`response_types` and `scope` may only name what exists. The client's name is shown to the owner, so control characters are
removed and it is cut to 100 characters (and escaped in the page). Limits: 30 registrations an hour (global), at most 100
registered clients that were never authorised, and such clients are forgotten after a day. A client's `grant_types` are recorded
but not enforced: every client may refresh.

**Redirect addresses** are matched exactly against what the client registered, except the **port of a loopback address**, which is
free (RFC 8252 section 7.3: an app on the same machine picks a free port each time). An unknown client or an unregistered address is
answered with a page, never a redirect. Every other mistake of the request is redirected back to the client as an OAuth error
(with `state` and `iss`).

**PKCE** is required and only `S256` is accepted (a request for `plain` or with no challenge is `invalid_request`). At the token
endpoint a wrong `code_verifier` uses the code up: a guess gets one try.

**Resource indicator.** A request may carry `resource` (RFC 8707); it must be `<base>/mcp` or `<base>` (trailing slash tolerated),
otherwise `invalid_target`. One is not required (the specification asks the *client* to send it; this server issues tokens for
exactly one resource anyway). Every token is stored with the resource `<base>/mcp`, and `/mcp` accepts only a token issued for it and
carrying the scope `mcp` (so a token never reaches another resource, and nothing else's token is accepted). The scope is one,
`mcp`; asking for another is `invalid_scope`.

**Codes and tokens** are opaque 256-bit random values with a recognisable prefix (`odcode_`, `odat_`, `odrt_`, `odc_` for a client id,
`ods_` for a secret), kept only as **SHA-256 digests** in `oauth_codes`, `oauth_tokens` and `oauth_clients` (migration 0003): a read
of the database gives nothing to present. A code lives 5 minutes and is single use; an access token 1 hour; a refresh token 30 days,
starting again at each refresh (no absolute cap: one that is stolen and used is caught by the reuse rule). Everything that depends on
the time takes the clock from the caller, so the tests move it instead of waiting.

**Refresh rotation.** Each refresh replaces the refresh token; the old one is marked used. A code used twice, or a used refresh
token presented again, **revokes the whole family** (everything descended from that authorization) and is logged, as OAuth 2.1
asks. One deliberate softening: a refresh token that was used **less than 15 seconds ago** is exchanged again for another pair
instead of ending the family. A client whose access token has just expired typically has several requests in flight, and each
refreshes with the same token; the SDK's own client does exactly that, and without the grace a harmless race ended its sign-in (a
test found it). Both pairs stay valid; the window counts from the first use and does not slide. `refreshReuseGraceSeconds` is the
knob. An access token that is revoked, or whose family ends, stops working at once.

**Revocation** takes the client's authentication, answers `200` whatever the token (RFC 7009 section 2.2: unknown, expired, or another
client's changes nothing), revokes an access token alone and a refresh token with its whole family.

**Expired rows** are forgotten opportunistically (codes an hour after they expire, tokens a week after, so that a reused refresh
token is still recognised); there is no timer.

**Not built, on purpose.** OAuth Client ID Metadata Documents (the specification's preferred registration, `client_id` is an https URL
the server fetches): fetching an address a stranger names needs SSRF protections that a one-owner service has no use for until a
client needs it, and the metadata says `client_id_metadata_document_supported: false` so a client falls back to dynamic
registration. A consent screen with scopes (one scope). Token introspection (RFC 7662). Multiple users (ADR 0015: the last stage).

### 5. The owner's passphrase

The server holds a **scrypt hash**, `OWNER_PASSPHRASE_HASH=scrypt:N:r:p:<salt>:<hash>` (base64url; **colons, not `$`**, because
docker compose reads a dollar sign in `.env` as a variable). The parameters travel with the hash (`N` a power of two from 2^10 to
2^20, `r` 1 to 32, `p` 1 to 16, so a mistyped value cannot ask for a gigabyte), the default made is `N=32768, r=8, p=1` (32 MiB, about
100 ms), and the passphrase is normalised to NFKC first so the same words typed on another keyboard are the same. `pnpm --filter
@overdrive/server run hash-passphrase` (inside the image: `node dist/hash-passphrase.js`) reads the passphrase (hidden and asked
twice on a terminal; all of stdin from a pipe, one trailing newline dropped), refuses fewer than 12 characters, and prints only the
`OWNER_PASSPHRASE_HASH=…` line. The comparison is `crypto.timingSafeEqual` of the derived keys. Verification runs **one at a
time**, since each costs tens of megabytes and the page is open to the internet.

**Lockout.** Five wrong passphrases in a row lock the page for 15 minutes; each lock after that is twice as long, up to a day; a right
passphrase clears it. While it is locked no passphrase is even tried (the owner's own gets the same `429` and a `Retry-After`), so
guessing costs only the wait. The counters are **global, not per address**: behind Caddy every request has the proxy's address and a
forwarding header can be forged, and one owner has no use for a per-caller count. They are in memory (a restart clears them, which
only the owner can cause). The price is that anyone can lock the owner out of **authorizing a new client** by guessing wrongly;
tokens already issued keep working and the lock ends by itself. Registration is limited separately (above). The page has
`X-Frame-Options: DENY` and a CSP with `frame-ancestors 'none'`, no script, `Referrer-Policy: no-referrer`; a Deny button answers
`access_denied` without asking for the passphrase.

### 6. Static tokens

`MCP_STATIC_TOKENS` (`name:token,name:token`, at least 32 characters each, the same syntax and checks as `DEVICE_TOKENS`) lets a client
that can only send a fixed `Authorization` header use `/mcp`: compared as SHA-256 digests in constant time, the name (never the
token) in the log. They open **only `/mcp`**: a device token is not an MCP token and the reverse. Unset by default. A static token
cannot be revoked except by removing it and restarting, and never expires: use OAuth where the client can.

### 7. The tools

Eight tools (Phase 2 of §8.4); `get_fitness`, `get_wellness` and `get_recovery` are Phase 3. Each has a precise description (units,
limits, what it returns, when not to use it), a Zod input shape, MCP annotations (read-only, destructive, open-world) and the server's
`instructions` explain the conventions once (fractions of FTP, whole seconds, local days, numbers computed by the server).

- **Results** are one compact JSON document in a text block; no `structuredContent` or `outputSchema` (the clients meant here read the
  text, and the same data twice costs a model's context). **Failures are tool errors** (`isError`) in plain words: `plan source not
  configured: …`, `workout does not validate: /steps/0/duration must be integer`, `there is no workout icu-5 in the calendar`,
  `intervals.icu refused the request (HTTP 422: …); nothing was changed`. An unexpected failure is logged and answered without detail.
  The SDK turns an argument that fails its Zod shape into a tool error too (`Input validation error: … at ftp`).
- **Numbers come from code.** Durations, the estimated load (`seconds x fraction^2 / 36` per step, a ramp as its mean square, free
  steps left out and reported as `freeSeconds`), outlines, zone ranges, statistics, compliance: none is left to a model.
- **The workout shape** is the contract's. `create_workout`'s `workout` argument is advertised to the model as exactly
  `workout.schema.json` (its `$ref`s written out, `id` and `schema` made optional because the server supplies them), by attaching the
  JSON Schema to a loose Zod type; the **contract's own validator** (Ajv) is what accepts or refuses it, so the message carries the
  path in the workout and the one definition of a valid workout stays in `contracts/`. The id the model sends is ignored: the entry is
  `icu-<calendar id>`.
- **`list_planned_workouts`** (`from`, `to`, `detail summary|full`, `refresh`): default today to +13 days, at most 62 days. A range inside
  today..+14 is answered from the plan cache (with `generatedAt` and `stale` as the HTTP endpoint has them); a range reaching outside
  it (the past, or beyond the horizon) is read **from intervals.icu directly**, and nothing is cached, because the cache holds only the
  horizon (spec §5.1). `refresh` reads the calendar first. Each workout: id, date, name, `durationSeconds`, `estimatedTss`,
  `freeSeconds`, `outline`, with the steps on `detail: "full"`; workouts the tablet cannot play are under `unsupported` with the
  reason (ADR 0016 promised the agent would be told); `totals` add up the list.
- **`create_workout`** (`date`, `workout`): validate, `workoutToEventPayload`, `createEvent`, then **the cache is made to say what
  intervals.icu answered** (the event converted the way the refresher converts it, so the stored result is what a later refresh
  would produce), `plan-changed` is emitted, and the stored workout is returned in full. If the answer cannot be read back, the
  workout that was sent is stored and returned with a `warnings` entry; if intervals.icu has the steps differently from what was sent
  (compared without labels), what it has is stored and a warning says so. If only the cache fails after the calendar write, the error
  says the entry **was** saved in intervals.icu and will be caught up at the next refresh.
- **`update_workout`** (`id`, and at least one of `name`, `date`, `steps`): reads the event first (it must exist and be a WORKOUT,
  never a note or a race), sends **only the fields that change** (`PUT`), keeps the time of day when moving it, writes new steps as
  the calendar's text (which drops the old description), converts the answer and stores it. It can repair a workout the tablet could
  not play.
- **`delete_workout`** (`id`): reads the event, refuses anything that is not a WORKOUT, deletes, removes it from the cache. A workout
  already gone is a success (`deleted: false`) and is forgotten from the cache.
- **`list_rides`** (`from`, `to`, `limit` at most 100, `offset`): newest first, local days (`dayStartMillis` finds local midnight in
  `TIMEZONE`, clock changes included), the summary **the tablet recorded** (average, normalised, maximum power, heart rate, work, intensity
  factor, load), the planned workout's id and name (from the cache or the copy kept; null for a free ride), `total` and `hasMore`.
- **`get_ride`** (`rideId`, `series`, `seriesStepSeconds`): statistics **recomputed from the samples** with the ride app's definitions
  (`SummaryAccumulator`: moving seconds only, normalised power from 30-sample rolling averages of the powers measured; a test
  checks every fixture against the summary the tablet wrote) plus variability index, best 5 s, 1, 5 and 20 minutes, seconds in each
  of the seven zones **by the ride's FTP and the profile's zone bounds**, heart-rate and cadence statistics; the laps (with the step's
  name when the workout is known); and **compliance** with the plan, per step and in total, in the shape `{ available, … }`. The
  series are off unless asked, averaged in steps (default 10 s) and never longer than 1200 points (the step grows for a long ride).
- **The workout a ride was played from is kept.** Compliance needs the workout *as it was*: the plan cache forgets days that have
  passed and the owner may edit or delete the event later, while the ride's laps index the steps of exactly that workout.
  `ride_workouts` (migration 0003, no foreign key so that `TRUNCATE rides` stays simple) holds a copy per ride, taken **when the ride is
  stored** (a listener on `ride-stored`) from the plan cache, else from intervals.icu (`getEvent`), and, if a ride is asked about
  before any copy exists, **at that moment** and kept. Without a workout (a free ride, a deleted event, intervals.icu unreachable)
  `compliance` says `available: false` and why.
- **`get_profile` / `update_profile`**: the profile plus the numbers derived from it (zones in watts, the week's total minutes, how
  many weekdays are unknown). `update_profile` takes the patch of the next section, trims goals, and returns the stored profile and
  what changed.

### 8. The profile (`GET`/`PATCH /v1/profile`)

Contract first (`contracts/schema/profile.schema.json`, `profile-patch.schema.json`, fixtures under `fixtures/profile/`, TypeScript in
`contracts/src/profile.ts`, Kotlin `app.overdrive.core.profile` with `ProfileContractTest`): `{ ftp, zones, weeklyAvailability, goals,
physics, updatedAt }`, every key always written.

- **`ftp`** in watts, 30 to 2000, **or `null`** while the owner has not set one: a default of 200 would be a guess that plans
  silently trust (the tablet then keeps its own setting).
- **`zones.upperPercentOfFtp`**: the inclusive upper bounds of zones 1 to 6 in whole percent of FTP, strictly increasing, **Coggan's
  `55 75 90 105 120 150` by default** (the ride app's `PowerZone`) and overridable; zone 7 has none. The watts of a zone are **derived,
  never stored and not on the wire**: `powerZoneRanges(ftp, bounds)` in TypeScript and `PowerZones.ranges(ftp)` in Kotlin, pinned by
  `zone-ranges.json` (and, for Coggan's bounds, against `PowerZone.of` for every watt from 0 to 2500). Power is whole watts, so
  55% of 250 W (137.5) is 137 W and zone 2 starts at 138.
- **`weeklyAvailability`**: minutes (0 to 1440) for `mon` to `sun`, `null` where unknown (0 is a rest day).
- **`goals`**: at most 10 short lines, 200 characters each, none blank.
- **`physics`**: `systemMassKg`, `cdA`, `crr`, `airDensity`, `drivetrainEfficiency`: **the names and defaults of the ride engine's
  `PhysicsSettings`**, with bounds tighter than the engine's (which only asks for positive values). The engine has one mass, rider,
  bike and kit together, so there is no separate rider and bike mass; the gear settings are not part of the profile (spec §8.3 says
  "FTP, zones, physics settings").
- **`updatedAt`**: UTC, `null` while it is the defaults.
- **`PATCH`** is a merge patch with explicit rules per field (`ftp` replaced or `null` clears; `zones` replaced as a whole or `null`
  resets to Coggan; `weeklyAvailability` changes the named days, `null` forgets one; `goals` replaced; `physics` changes the named
  constants, none `null`; `updatedAt` cannot be sent). It is applied in a transaction on the locked row, and **validated as the
  profile it would make**; a patch that makes an invalid profile changes nothing and is `422 invalid_profile` (a new code of the closed
  list, `api-error.schema.json` and Kotlin's `ApiErrorCode`), a body that is not JSON `400 invalid_json`, a body of another type `415`
  (`application/json` and `application/merge-patch+json`). A patch that changes nothing stores and announces nothing. The same
  `before`/`patch`/`after` fixtures are run by contracts, by core and, through HTTP, by the service. A change emits `profile-changed`.
- Storage: table `profile`, one row (`id boolean PRIMARY KEY CHECK (id)`), the profile as `jsonb`; no row means the defaults. A stored
  row that no longer validates is not served (`500`), not silently repaired.

### 9. Configuration

| Variable | |
|---|---|
| `PUBLIC_BASE_URL` | `https://overdrive.llmto.day`. With the hash, turns the MCP server on |
| `OWNER_PASSPHRASE_HASH` | `scrypt:…`, from `hash-passphrase`. With the address, turns it on |
| `MCP_STATIC_TOKENS` | `name:token,…`, at least 32 characters each; optional |

Validation is in `config.ts` and never prints a value; blank means unset (compose passes `${VAR:-}`). Caddy needs no change: the site
file proxies everything to the server. The `/v1` device tokens do not open `/mcp`.

## Deviations from the brief

- `OWNER_PASSPHRASE` (plain) is not read, only the hash (section 2).
- The brief asks for tools to return "the stored result" of a write: they do, and where intervals.icu's copy differs from what was
  sent they say so in `warnings` rather than hide it.
- `GET /v1/profile` carries no derived zone watts (section 8); they are in `get_profile`.
- `list_planned_workouts` reads intervals.icu directly for ranges beyond the cache's 15 days (section 7); the brief only said "date range".
- A **15-second grace** on refresh-token reuse (section 4), where OAuth 2.1 says to rotate and treat reuse as theft.
- The vitest configuration resolves `@overdrive/contracts` with an alias to its sources instead of the `source` export condition,
  because the SDK client's own dependency (`eventsource-parser`) uses that condition for `.ts` files Node will not load from
  `node_modules`. The dev script is unchanged.

## What only a real MCP client and a real account can settle

Nothing below was verified against a real client. Each is a place where the server, the SDK's client and the tests share an assumption.

1. **Registration.** Which clients register dynamically (this server) and which only use Client ID Metadata Documents
   (`client_id_metadata_document_supported` is `false`; if Claude's apps need it, it is a later addition with an SSRF-safe fetch);
   which `token_endpoint_auth_method` they ask for (a `none` client is public and needs PKCE only, the others get a secret); whether
   they send `grant_types` with `refresh_token`.
2. **Redirect addresses.** That every client's callback is `https` or loopback. Claude's web and mobile apps are expected to call
   back to an `https://claude.ai/…` address and its desktop or CLI clients to `localhost`; clients that use a custom scheme
   (`cursor://…`) are **refused at registration** by the specification's rule, and only a real attempt shows whether one matters.
3. **The consent page.** How it looks and behaves in the clients' in-app browsers on a phone (a password field, two buttons,
   no script), and whether a password manager fills it.
4. **Discovery.** That each client follows the `401` challenge and both well-known addresses, sends `resource` and PKCE `S256`, reads
   `iss`, and copes with `scopes_supported ["mcp"]` and the scope `mcp` it will be asked to request.
5. **Refresh.** That each client keeps the **new** refresh token it is given (rotation), copes with `invalid_grant` by asking the owner
   again (the SDK's client leaves that to the application), and whether 15 seconds of grace is enough for its parallel requests.
   If a client refreshes in parallel for longer, the whole sign-in ends on the next refresh and the log says
   `oauth refresh token reused`.
6. **Transport.** That `POST`-only stateless JSON answers (no SSE, `405` for `GET`/`DELETE`) are accepted by every client; that none
   insists on an `Mcp-Session-Id` or on `Origin` handling; the largest tool result a client takes (`get_ride` with `series` is the one
   to watch).
7. **Tools.** How each client shows the descriptions, the annotations (read-only, destructive) and the JSON Schema of
   `create_workout`'s `workout` (it is large, with `oneOf`), and whether models write valid workouts first try.
8. **intervals.icu** (in addition to ADR 0016's list, which still stands): that `GET /api/v1/athlete/{id}/events/{id}` returns the
   event (the fake does); that `PUT` changes only the fields it is sent; that a new description is parsed again into `workout_doc`
   (the fake assumes it and drops the old parsed steps; if the real answer still carries the old ones, `update_workout` says so in
   `warnings`); that the answer to `POST` and `PUT` has the same shape as the list; what `DELETE` does to an event paired with an
   activity; that `category` of an event we did not create is what we filter on (`WORKOUT`, `NOTE`, `RACE_A`, …).
9. **Time.** That `TIMEZONE` and the owner's idea of "today" agree, since the tools' default ranges and the day of a ride use it.

## Consequences

- An AI client can read the plan and the rides and change the plan and the profile, from anywhere, with the owner's passphrase as the
  only credential, and the calendar remains intervals.icu's.
- The tablet can read the profile (`GET /v1/profile`); using it (FTP, physics) is stage P2-3's tablet half or later, and does not touch
  the server again.
- The Telegram, Google Calendar and Strava modules read the profile and rides through `app.services` and hear `plan-changed` and
  `profile-changed`.
- A stolen device is revoked by removing its token; a stolen MCP grant by revoking the client (`POST /oauth/revoke`) or, for all of
  them, `DELETE FROM oauth_tokens;` (`server/README.md`). Changing the passphrase hash does not end grants already issued.
- The image grows by the SDK's dependencies (Express, Hono and others it ships for its own server helpers), which the service does not
  use.
