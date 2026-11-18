# ADR 0020: The Strava upload and its description

Status: accepted (Phase 2, stage P2-5, server side, 2026-11-18).

## Context

Spec §5.5 says each ride is uploaded to Strava "as a virtual ride, with a text description of the session", that nothing is
read from Strava (its API terms forbid using its data in AI models, and CLAUDE.md makes that a rule: **Strava is write-only**),
and that uploads are idempotent. §5.6 says photos are not possible and a map is "to be verified". ADR 0014 and 0016 built the
queue (`ride_destinations`), the `ride-stored` event and the intervals.icu worker that this one follows. This ADR records how the
server does it, written **without a Strava account**: the endpoints and parameters come from Strava's public documentation
(developers.strava.com: *Authentication*, *Uploading to Strava*, *Rate Limits*, *Getting Started*, read on 2026-11-18, nothing
from it is committed) and every test runs against a fake Strava that we wrote from the same pages
(`server/test/helpers/fake-strava.ts`), so the tests prove the code agrees with the notes, not with the real service. The last
section lists what only a real account settles.

## Decision

### 1. The write-only boundary

The module is `server/src/strava/`. It sends **three kinds of request and nothing else**:

| Request | What for | What is read from the answer |
|---|---|---|
| `POST /oauth/token` (grant `refresh_token`) | a new access token | `access_token`, `refresh_token`, `expires_at` or `expires_in`. A token response may also carry a summary of the athlete: it is not in the parsing schema, so it is dropped unread, never stored, never logged |
| `POST /api/v3/uploads` | post one ride | the **upload's own** reply: `id` (`id_str`), `status`, `error`, `activity_id`, and the rate-limit headers |
| `GET /api/v3/uploads/{id}` | see whether *our* upload is done | the same reply; `{id}` is an id Strava gave us for a post of ours. **This is the only GET** |

The only response data the service uses is what the upload protocol hands back about **our own upload**: its id, its status
words, the error that says why it failed, and the id of the activity it became (stored as `external_id`, the proof that it
succeeded). A duplicate is reported in that same `error` as `<file> duplicate of activity 123`: the number is the same kind of
answer about our own upload, is recorded as `external_id` and is used for nothing else. The rate-limit headers describe our own
application's use of the API, not anything Strava holds about the rider. The scope requested is **`activity:write` only**
(Strava's description: create uploads and manual activities; it has no read right, and no narrower scope exists for uploads). The
module has no method that could read an athlete, an activity, a stream, a segment, a route or a club, and no code path calls one.

This is enforced by tests (`server/test/strava-write-only.test.ts`): the fake Strava records **every** request it receives over
a run that exercises success, polling, a repeat, a renewed token, a 429, a 5xx, a refusal and a forbidden account, and the test
fails on any path other than the three above or any query string; a second test reads the module's source and fails on any
Strava address other than the token, the authorisation page and the upload and its status, on any HTTP method but GET and POST,
on any scope but `activity:write`, and on a `fetch` that does not refuse redirects. There is no webhook either (it would need
`activity:read`).

Photos are not possible (spec §5.6: attaching images on upload is limited to Strava partners). The description is written by
**code** from our own ride record and the linked plan workout (section 6); no model is involved, and the Phase 3 insights are not
built.

### 2. Configuration and the tokens

`STRAVA_CLIENT_ID`, `STRAVA_CLIENT_SECRET`, `STRAVA_REFRESH_TOKEN` (`src/config-strava.ts`). **The feature is off unless all three
are set**; with only some set it is off and the log says which are missing (a warning, no value). Also `STRAVA_BACKFILL` (default
`false`, section 3) and `STRAVA_BASE_URL` (default `https://www.strava.com`; the tests point it at the fake; one address serves
`/oauth/token` and `/api/v3`). Validation never prints a value; blank means unset (compose passes `${VAR:-}`).

**The access token** lives in memory only (six hours; renewed five minutes early, and on a 401). Calls that need one at the same
moment share one request. **The refresh token rotates** (a refresh may answer with a new one and the old one stops working at
once), so the newest is kept in the table `strava_auth` (migration 0005, one row). The environment's `STRAVA_REFRESH_TOKEN` only
**seeds** the first run:

- The row also records the SHA-256 of the environment value it was seeded from. At start, if the environment value still has that
  hash, **the table's token is used** (the rotated one); if the owner has put another value in the environment (they ran
  `strava-auth` again), the hashes differ and **the environment wins** and replaces the table's. So the owner never edits the table:
  after any trouble they run `strava-auth`, put the new line in `.env` and restart.
- If the database cannot store a new token the one in memory goes on working, and the log says (once per refresh, without the token
  or the database's message, which can quote the row) that a restart before the next refresh would need `strava-auth` again.
- A refused refresh token, or a refused client id and secret, is a configuration error (section 5), not a retry.
- One instance only: two servers sharing a database would race to rotate the token. The service is one deployable (ADR 0014).

`pnpm --filter @overdrive/server run strava-auth` (or `node dist/strava-auth.js` in the image) is the owner's one-time helper:
it asks for the Client ID and Secret (the secret without echo; or from `STRAVA_CLIENT_ID` and `STRAVA_CLIENT_SECRET` for the one
command), prints the authorisation address with `scope=activity:write` and `redirect_uri=http://localhost/overdrive-strava`
(Strava allows `localhost` as the Authorization Callback Domain), and reads the address the browser lands on after the owner
approves (the page does not load; the address bar holds the code: the paste-the-code flow, which also works over SSH). It checks
that Strava granted `activity:write`, exchanges the code and prints `STRAVA_REFRESH_TOKEN=…` **to the terminal only**: it refuses
to run when stdin or stdout is not a terminal, so a redirect cannot put the token in a file. No loopback listener: a server on the
owner's laptop would not be reachable from a remote host's terminal, and pasting is one step.

### 3. The queue and idempotency

- **Destination `strava`** in `ride_destinations`, the same table and states as ADR 0014/0016. While the feature is on,
  `services.destinations` includes `strava`, so storing a ride's FIT queues it **in the same transaction**, and `ride-stored`
  wakes the worker. While it is off nothing is queued.
- **Backfill.** Rides stored before Strava was turned on have no row and are **not** sent: turning it on must not post the whole
  history. `STRAVA_BACKFILL=true` queues every stored ride with a FIT that has no row, at start (oldest first, `ON CONFLICT DO
  NOTHING`, so it can stay on; the rate limits pace it). The owner sets it, restarts, waits, and may set it back.
- **Worker** (`uploads.ts`, as ADR 0016): rows that are due, **one at a time**, oldest first; a row is claimed by moving its
  `next_attempt_at` ten minutes ahead (a lease), so a crash resumes and two passes never post one ride; every wait is persisted in
  `next_attempt_at`; at start every waiting row is made due.
- **Never posted twice.** A ride is identified by its start time and id (the intake guarantees both are unique, ADR 0014). Before
  posting, the worker looks in `strava_uploads` (migration 0005: the upload id Strava answered with and the number of checks made)
  and, if the ride is there, **does not post: it goes on checking that upload**, even after a restart. The row is written
  immediately after Strava accepts the post. If the process dies between Strava accepting and the row being written, or the
  answer is lost, the retry posts again and Strava answers with a **duplicate**, which counts as success: the file is posted with
  `external_id` = the ride's id and the file name `<ride id>.fit` (both are how Strava recognises a repeat), and the same bytes
  as stored, so the file's own start time repeats too.
- **Done** has the activity id in `external_id`; **failed** is given up with the reason in `last_error`; **pending** is a try to
  come, with its reason.

### 4. What is posted

`POST /api/v3/uploads`, multipart: `file` (the FIT as stored, named `<ride id>.fit`), `data_type=fit`, `name`, `description`,
`sport_type=VirtualRide`, `trainer=1`, `commute=0`, `external_id=<ride id>`. Nothing in the address. `sport_type` is the current
parameter (`activity_type` is deprecated and ignored when `sport_type` is present). `trainer=1` is sent although a file without
positions is marked as on a trainer by Strava anyway ("activities without lat/lng are auto-marked stationary"), so that the flag
does not depend on that rule. Redirects are refused (they would carry the token elsewhere). Timeouts: 20 s a request, 60 s for the
post.

### 5. Checking the upload, errors and limits

- **Polling.** The post is answered `201` and the activity does not exist until Strava has processed the file (mean time under two
  seconds). The worker waits 2 s (Strava: no more than once a second), checks the upload, and up to **4 checks** in a row; if the
  upload is still processing the row goes `pending` with a persisted wait of 30 s, 60 s, 2 min, 4 min between rounds, and the
  number of checks is persisted (`strava_uploads.polls`). After **20 checks** the upload is given up as stuck (`failed`, saying it
  may still appear on Strava and will not be posted again; setting the row back to `pending` and `strava_uploads.polls` to 0
  resumes the checking, never a second post). Sleeps are injected; tests wait for nothing.
- **Verdict** of a reply, in this order: an `activity_id` is success; an `error` that says duplicate (`duplicate of activity N`,
  `duplicate activity`, `already uploaded/exists`) is success; any other `error` is `failed` with Strava's words (markup removed,
  200 characters); the status "The created activity has been deleted." is `failed` (the owner removed it; it is not posted again);
  otherwise still processing. A post refused as a duplicate with a `400` is the same success.
- **401.** The access token is renewed once and the request repeated once. A second 401, a 403, or a refused refresh token are
  **configuration errors**: the scope is missing, the application was deauthorised, or the account may not upload (the likely reason
  for a 403 is the subscription, section 8). One line in the log with the reason, **`last_error` on every waiting ride** (also on
  rides stored later), and **no request for six hours**, or until the server restarts, which is when the owner has fixed it; after
  six hours one probe goes out and the same failure is not logged again. Never a loop, and never `failed`: nothing is lost.
- **429.** Strava's limits are 200 requests per 15 minutes and 2,000 a day overall, and 100 and 1,000 for reads, which includes the
  status checks; the 15-minute windows end at :00, :15, :30, :45, the daily one at midnight UTC; a request over a limit still counts
  toward the daily one. Every answer carries `X-RateLimit-Limit/Usage` and `X-ReadRateLimit-*`. The worker **stops at the limit
  instead of finding it out**: when a window is used up nothing is asked until its end (plus 5 s). A 429 waits for `Retry-After`
  if present (seconds or a date), for the end of the window the headers say is used up, and otherwise for the end of this
  15-minute window; each 429 in a row doubles that (at least 15 min, 30, 60, up to 6 h). **A wait is not held against the ride**:
  its `attempts` do not change, nothing else is taken meanwhile, and the wait is persisted. (Strava's pages do not mention
  `Retry-After`; it is honoured if it comes.)
- **5xx, no answer, a body that cannot be read:** `pending`, backoff 1, 2, 4 … minutes up to 6 hours, **given up after 20 failed
  tries**. A refusal of the file itself (a 4xx that is not a duplicate; processing that reports an error) is `failed` at once.

### 6. The name and the description

Written by `src/strava/description.ts` from the ride record (the summary the tablet computed, the same numbers `list_rides` shows)
and the workout the ride was played from (`services.rideWorkouts`, copied when the ride is stored), by code. Plain text, lines
separated by a newline, no emoji, no hype, no claim about health or fitness:

```
Sweet spot 3x10: 10 min ramp 45 to 70% FTP, 3 x (10 min at 88% FTP + 5 min at 50% FTP), 5 min free, 5 min ramp 55 to 40% FTP.
Duration 1 h 05 min, moving 1 h 02 min.
Average power 182 W, normalised power 191 W.
Average heart rate 141 bpm.
Work 712 kJ, load 63.
Planned 1 h 05 min, ridden 1 h 02 min; 8 of 9 steps completed. Target power planned 175 W, ridden 169 W (-3%).
FTP 250 W.
```

A free ride starts with `Free ride.` and has no plan line. A part with no value is left out (no heart rate: no line). Small
numbers keep one decimal (`Work 0.5 kJ`). The moving time is said only when the ride was paused a minute or more. The plan line
uses `rideCompliance` of `src/ride-stats.ts` and is left out when the ride recorded no steps. A workout whose steps would not fit
a line is said by its size (`Long one: 12 steps, 3 h 06 min.`). **The activity name** is the planned workout's name, or
`Virtual ride 2026-11-18` (the date in `TIMEZONE`) for a free ride or when the workout cannot be found. Snapshot tests cover every
valid ride fixture of `contracts/` free, and one planned. If the text cannot be built the ride is posted with the plain name and no
description, and the log says so: a description is never a reason to hold a ride back.

### 7. The map, and what a first real upload should check

No route feature is built (spec §5.6: "to be verified"). The FIT of the tablet has no positions, so a virtual ride has no
track. The first real upload should look at: whether the activity appears as a **Virtual Ride** with the right start time and
duration, whether the **description and name** show as written (line breaks included, the upload sends them as CRLF in the
multipart body), whether the power, heart rate and cadence streams are there, whether Strava shows **any map** (expected: none),
and what a free ride would need (positions along a route in the FIT) for one to render; only if it does is a route feature worth
specifying.

### 8. What only a real Strava account settles

Nothing below was verified against the service:

1. **The subscription.** Strava's *Getting Started* page says creating an API application needs an active subscription, and spec
   §5.5 and the forum thread it cites say the 2026 terms demand one for standard-tier access. Whether an upload by an application of
   a subscriber needs more, and what exactly a lapsed subscription answers (we assume a 403, reported as in section 5).
2. **The exact answer to a post.** That `201` carries `id`, `id_str`, `status`, `error`, `activity_id` as documented, and **how a
   duplicate is reported**: the documentation shows it in `error` of the upload object ("`<file> duplicate of activity N`"), which
   may come in the answer to the post or only when it is checked; a `400` is also treated as a duplicate when its text says so. We
   do not know whether Strava also tells repeats by `external_id`, or by the start time only (the TrainerRoad forum reports start
   time), and what it does with a different file for the same start time.
3. **`sport_type=VirtualRide` and `trainer=1`** on a FIT upload: that the activity is a Virtual Ride and not changed by the file's
   own sport, and that `commute=0` is accepted.
4. **Limits.** That the figures hold for a new application (200 per 15 minutes, 2,000 a day, 100 and 1,000 for reads), that the
   headers come on every answer including errors, and whether a 429 carries `Retry-After`.
5. **The scope in practice.** That a token with only `activity:write` can create an upload and read its status (`GET /uploads/{id}`
   is documented under the same scope), and what Strava answers to a token without it (we treat 401 and 403 alike).
6. **Rotation.** That refresh tokens rotate in practice or stay the same (both work here), and that a deauthorised application
   answers 400 or 401 to the refresh (both are treated alike).
7. **The map and the streams** (section 7), and whether the name, description and line breaks arrive as written.

## Deviations from the brief

- Backfill rows are queued at start (`STRAVA_BACKFILL`), and new rides by the intake's own transaction (the `destinations` list)
  rather than by a listener of `ride-stored`: the row is then in the same transaction as the FIT, as for intervals.icu. The event
  only wakes the worker.
- The queue code (claim, lease, backoff) is a second copy of `src/intervals/uploads.ts`, not shared: the two workers differ in
  what a try is (one request against post-and-check), in waits that are no one's failure, and in the stop at a limit; a shared
  queue module is the next step if a third destination appears.
- `strava_uploads` and `strava_auth` have no foreign key to `rides` (as `ride_workouts`, ADR 0018): rides are never deleted and
  a reference would make every `TRUNCATE rides` name them too.
- A new variable, `STRAVA_BASE_URL`, and `strava-auth` as a script that runs in the image too (`dist/strava-auth.js`).

## Consequences

- With the three variables set, every ride the tablet uploads from then on reaches Strava as a virtual ride with a short text, and a
  Strava outage or a limit delays it and loses nothing; the tablet never sees any of it.
- A wrong credential or a missing subscription shows in `ride_destinations.last_error` and in the log once, and costs no more
  requests until the owner restarts.
- The write-only rule is a test, not a promise: adding a read of Strava fails `strava-write-only.test.ts`.
- Strava's data is never an input to the Phase 3 analysis; our own files are.
