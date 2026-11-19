# ADR 0038: The live coach, service and contracts

Status: accepted (v0.4.0, stage A3, part 1: the service and the contracts, 2026-11-19). **Nothing here was heard on the bike or through the owner's
real speech service.** The tablet's side (streaming the snapshot, playing the audio, the overlay's plate and mute control) is the next stage.

## Context

Spec §12.1: during the workouts the planning agent marks, a coach on the server watches the ride as it happens and speaks short cues in the rider's
earphones. It talks and shows text; it never changes a target, the intensity or the workout. The tablet streams a compact snapshot every 5 s; **rules
on the server decide when a cue may happen**; when a trigger fires and the limits allow, a fast model writes the line, the rider's own speech service
speaks it and the audio goes to the tablet, which plays it over the video and shows the text. It needs a connection; offline it is simply off and the
ride is unaffected. The agent provides a `coach_brief` and optional `must_say` lines; every cue is logged on the ride so the agent knows afterwards what
the rider was told. The rules of `CLAUDE.md` hold: the ride app works offline and the tablet talks only to our server; the model writes text from facts the
service computed and never calculates; the repository is public (no secret, no recording, no personal data: every reading and line in a test is invented);
**no log line holds a measured value, a cue's text or a key**.

Written without the owner's speech service, a real model or a tablet: a fake model (the insights' fake, ADR 0025) and a fake speech service on local ports,
the clock and timers moved by hand. The last section lists what only the real thing settles.

## Decision

### 1. Transport: two plain HTTP connections, not one WebSocket (a settled difference from spec §12.1)

§12.1 says "over one persistent connection". The tablet is an Android app with no Google Play Services and no extra network library: only the platform's
`HttpURLConnection`. A WebSocket needs a library (or a hand-written protocol); **Server-Sent Events** need only a `GET` whose body is read line by line. So:

- **Uplink:** plain `POST`s, one snapshot every 5 s. Each is small (about 400 bytes) and independent: a lost one is the next one's problem.
- **Downlink:** `GET /v1/live/sessions/{id}/cues`, `text/event-stream`. `retry: 3000` first, a comment `: open`, an event per cue with an `id:`, a comment
  `: hb` every **15 s** so no proxy closes a quiet connection, and a last `end` event. A tablet that lost its network reconnects with `Last-Event-ID` and is
  sent the cues after it that have not expired (the last 8 are kept, each worth playing for 8 s after it was ready).
- **The service never calls the tablet.** A tablet that is offline at the start, or loses the connection, gets no cues and nothing else: the ride goes on, and
  cues resume on reconnect (a new stream, or a new session: a session the service forgot is a plain `404`).
- **Caddy:** `deploy/overdrive.llmto.day.caddy` needed no change. `reverse_proxy` flushes `text/event-stream` immediately (as Go's reverse proxy does for that
  content type), has no response timeout, and the 15 s heartbeat keeps the connection from idling out. The service sends `Cache-Control: no-cache, no-transform`
  (a layer that compresses would hold events back) and `X-Accel-Buffering: no`. `docs/deploy.md` section 21 says how to see it with `curl -N`, and what to do
  if an `encode` in the shared `common` snippet compresses event streams (exclude the type, or `flush_interval -1`). A comment in the Caddy file says so.

Routes under `/v1/live`, with the device token of every other `/v1` route (the SSE route checks it before a byte is sent):

| Request | Body | Answers |
|---|---|---|
| `POST /v1/live/sessions` | `{ rideId, workoutId?, structureHash?, variantRidden?, clock }` | `201` `{ sessionId \| null, coach: { on, reason }, serverTime, snapshotEverySeconds: 5 }` |
| `POST /v1/live/sessions/{id}/snapshots` | the snapshot (below) | `204`; `404 not_found` for a session that is not the live one |
| `GET /v1/live/sessions/{id}/cues` | none | `200` the event stream; `404 not_found` |
| `POST /v1/live/sessions/{id}/end` | optional `{ reason?, clock? }` | `200` `{ sessionId, status: "ended" \| "already-ended", cues }` |
| `POST /v1/live/sessions/{id}/cues/{cueId}/played` | `{ played, clock? }` | `204`; `404` for a cue the session never made |

**No new error code.** The Kotlin decoders have a closed list of codes and the tablet stage must not be forced to change it for this: a bad body is
`400 invalid_json` (the message names the path and the rule and never a value), an unknown session or cue `404 not_found`, a body over 16 KiB
`413 payload_too_large`, another content type than JSON `415` (plain text is a `400`: it is not a start). A start that cannot be answered is not an error
either: `coach.on: false` with the reason.

**One live session at a time** (one rider). A start ends the session before it, whatever the new one's answer is (a new ride is a new session): its
stream gets `end` (`superseded`) and closes, its snapshots are a `404`. Starts are serialised, so two at once end the old session exactly once. A session
with no snapshot for 10 minutes (the app was killed) is ended by the service (`end`: `ended`). The last 8 ended sessions are remembered, so a repeated end
answers `already-ended` and a late `played` report is still taken.

### 2. Contracts (first, `contracts/`, `contracts/README.md`)

- **The workout** gains optional `live_coach` (boolean), `coach_brief` (1 to 1000 characters, not blank) and, on a steady, ramp, free or heart-rate step (not a
  repeat), `must_say`: 1 to 3 `{ at: "start" | whole seconds from 1, text }` with `text` at most 80 characters (code points, as a cue). A line is spoken inside
  its step (`at` below its duration) and two lines of a step are not at the same moment; inside a repeat a step's lines are spoken at every pass. Additive: every
  old workout and fixture stays valid. `validateWorkout` says the mistakes plainly with their path first (the schema's own message for a nested step is only
  "must match exactly one schema"), and never quotes a line or the brief. **They are not part of a workout's structure**: `workoutHash` ignores them, so a
  session with and without a coach is the same session (§11.7); and the copy of the workout a ride keeps (what was ridden) has none (`withoutLiveCoach`).
- **The Kotlin decoders reject unknown keys**, so the fixtures are in `contracts/fixtures/live-coach/` (the way A2b-1 did for routes) and none of those Kotlin
  reads changed. Until the tablet and the companion learn the three fields, **the agent must not write them** (the server's instructions and the tool
  descriptions say what they are for; section 11 says the rollout).
- **The snapshot** (`live-snapshot.schema.json`), every field bounded: `seq` (from 0; a repeat or an older one is ignored), `clock` (the app's, ms),
  `elapsedSeconds` (the ride record's sense, pauses included), `paused`, `muted`, `step` (flattened index, type, label, duration, time left in seconds with pauses
  not counted, `targetWatts` for a steady or ramp step or `targetBpm` for a heart-rate step: the step's target, not the stall guard's), `stepCount`, `power`,
  `heartRate` and `cadence` (**means over the last 5 s**; absent when not measured), `compliancePercent` (the share of the step's judged seconds within 5% of
  target, or inside the heart-rate band), `stallGuardActive`, `events` (at most 8 of `stall-guard`, `strap-lost`, `paused`, `resumed` with how many seconds ago).
- **The cue** (`live-cue.schema.json`): `id` (from 1; also the SSE id), `text` (1 to 80), `trigger`, `audio`, `createdAt`, `expiresAt`, `ttlMs`.
  **The audio is inline, base64**, `{ format, base64 }` (`mp3` unless the owner chose another), at most 400000 bytes. Why not a URL: a cue of 80 characters is
  about 64 KB (86 KB as base64; the owner's server gave 27 KB for 34 characters), which is nothing on a tablet's wifi; inline makes **one request instead of two**
  on a connection that may be flaky, needs **no second authentication, no cache, no clean-up and no storage** on the service, and the audio is never kept.
  `ttlMs` is `expiresAt` minus `createdAt` (8000), so an app that does not trust its clock plays a cue only within `ttlMs` of receiving it.
  `trigger` is a name the tablet treats as a plain string (a later stage adds some).
- **The session**: the start's answer has `sessionId` null exactly when the coach is off, and then `reason` (`feature-off`, `no-tts`, `no-llm`, `no-workout`,
  `no-live-coach`, `structure-changed`; a reader treats one it does not know as off); the end's answer says how many cues the session made; the played
  report is `{ played, clock? }`. `stream/cues.sse.txt` is a stream as the service writes it, parsed by the contracts' tests.

### 3. A session starts, and why it may not

`POST /v1/live/sessions` checks, in this order: the owner's switch (`feature-off`), the speech service (`no-tts`: `TTS_BASE_URL`, `TTS_MODEL` and
`TTS_VOICE` all set), the model (`no-llm`: `LLM_BASE_URL` and `LLM_MODEL`, or `LIVE_COACH_LLM_MODEL`), then the workout: none, or the service does not have it
(`no-workout`: a free ride, or one that left the plan), it does not ask for the coach (`no-live-coach`), or the tablet plays another structure than the service
has (`structure-changed`). The service-level reasons come first so that the owner testing with `curl` sees the first missing piece whatever the workout; the
tablet shows "unavailable" only when its workout asked for the coach. **No model means no coach, even for a workout of only `must_say` lines**: a coach that is
half on is more confusing than one that is off (a deliberate choice; a TTS-only mode is an easy later change). The structure check accepts the hash of the
primary, of the primary as the tablet is served it (a heart-rate target as a fraction of the threshold resolved to beats, ADR 0033) and of the easier variant,
which is what a variant ride sends; a start with no hash plays the primary (or the variant when `variantRidden`).

At a start the service reads, **once**, what the cues will need so that nothing in the hot path waits for the database: the profile's FTP (watts of a step), the
coach notebook's `profile`, `what_works` and `methodology` sections (**never the game master's, and not `injuries`**), and the previous attempt at the
session through the repeats service (`GET /v1/repeats/{hash}/previous`, by the structure hash and the ride id). A failure of either read is logged
(without a value) and the coach goes on without it.

### 4. The trigger rules (`server/src/live-coach/triggers.ts`: pure, deterministic, in the time of the snapshots, no real clock)

Time is the service's clock at the receipt of each snapshot; the step's time left is the snapshot's. A line that is known beforehand is triggered
`LIVE_COACH_LEAD_SECONDS` (6) early to absorb the delay from trigger to sound: with 5 s between snapshots and about 3 s to the sound, a cue about a step that
is about to begin is heard between 3 s before it and 2 s after it begins. The thresholds below are constants of `DEFAULT_TRIGGER_SETTINGS`: first guesses for
the bike to tune (the limits are the settings the owner asked for).

| Trigger | Fires when | Notes |
|---|---|---|
| `must-say` | a line of a step is due: `start` from the lead before the step begins until 10 s into it (so an app that joined late still says it), a line at second *n* from *n* − lead to *n* + 10 s | spoken once each (a key), as written, with no model |
| `strong-finish` | the **last hard step**'s final 3 minutes, past half of it, compliance at least 80%, outside the silent final seconds | once. The last hard step, not literally the last step: a workout that ends in a cool-down still gets its finish. It replaces `two-minutes` on that step |
| `step-start` | the next step is hard and its start is within the lead; or the current hard step is within 10 s of its start (the first step, a late app) | once per step. **Hard** = a steady or ramp step whose target (a ramp's higher end) is at least 85% of FTP and which lasts 30 s or more |
| `two-minutes` | 126 s to 110 s left, in a step of at least 5 minutes, not a free step | once per step |
| `power-drift` | the **whole last 20 s** of snapshots (at least 4, reaching back 17.5 s, up to half an interval of slack either way) are more than 10% off the step's target on the same side; not in the first 30 s of a step; not while the stall guard holds the target down; a missing reading or a pause breaks it | a condition, with no key: the limits decide how often it speaks. The facts carry the direction, the means and the percent, computed by code |
| `cadence-sag` | the whole last 20 s have a cadence above 0 and below 70 rpm (or 12 below the step's cadence target); not in the first 30 s, not in a free step | a stop (0 rpm) is the auto-pause's and the stall guard's, not a sag |
| `heart-rate-climb` | in a steady step 150 s or more in, with a previous attempt that has a heart-rate figure for this step: the mean of the last 30 s is at least 7 bpm above that figure and the heart rate is higher than a minute ago, with the power on target | the repeats service gives each step's mean heart rate and boundaries, **not a series**, so "climbs faster than in earlier repeats" is "already above last time's average for this step and still rising". Only the attempt before is used |

**Priority** (what the limits are offered first): `must-say`, `strong-finish`, `step-start`, `two-minutes`, `power-drift`, `cadence-sag`,
`heart-rate-climb`: the first the limits allow is made, and a persisting condition is asked again at the next snapshot.

### 5. The limits on talking (`limits.ts`), their order, and what they bind

The limits are settings (`LIVE_COACH_*`) with the spec's defaults and are enforced **before the model is asked**, so cost and chatter are bounded by code and
not by the model's manners. **The order they are checked in is also the reason reported** for a cue that is held back (the debug log says `trigger:rule`):

| # | Rule | Setting | Default | What holds a cue back |
|---|---|---|---|---|
| 1 | `muted` | | | the rider muted the coach; **sticky for the session** (the snapshot says `muted`; once true, a later `false` does not unmute: "for the rest of the ride"); the model is not asked; a cue being made is abandoned |
| 2 | `busy` | | | a cue is being made: one at a time |
| 3 | `final-seconds` | `LIVE_COACH_SILENT_FINAL_SECONDS` | 15 | the step's time left is at most this. **A cue about the step that is about to begin is exempt** (it is triggered early, in the last seconds of the step before, on purpose); the strong finish and the two-minute warning are not even offered in the window |
| 4 | `recovery-step` | `LIVE_COACH_RECOVERY_MAX_CUES`, `LIVE_COACH_RECOVERY_BELOW_PERCENT` | 1, 60 | the step the cue is about is a recovery step (a target below 60% of FTP, a ramp whose higher end is below it, or a label that says recovery or rest) and it already has that many cues, of any trigger |
| 5 | `trigger-per-step` | `LIVE_COACH_MAX_PER_TRIGGER_PER_STEP` | 2 | the same trigger already spoke this many times in this step |
| 6 | `trigger-cooldown` | `LIVE_COACH_TRIGGER_COOLDOWN_SECONDS` | 180 | the same trigger spoke less than this long ago (in any step) |
| 7 | `gap` | `LIVE_COACH_MIN_GAP_SECONDS` | 60 | the last cue of any trigger was less than this long ago |
| 8 | `per-hour` | `LIVE_COACH_MAX_CUES_PER_HOUR` | 12 | this many cues in the last 3600 s |
| 9 | `attempt-cooldown` | | 60 s | the trigger's last try produced no cue (the model said nothing, or its line was refused): not asked again for a minute, so a condition that persists does not cost a request every 5 s. A trigger that fires once (a key) is spent after any attempt |

After the model has answered: the line's **length** (`LIVE_COACH_CUE_MAX_CHARS`, at most the contract's 80, in code points), its **numbers**, its **words**, its
**novelty**, and the **deadline**. The agent's `must_say` lines are held back by 1 to 3 and use a fixed 8 s gap instead of 7 so that two voices never overlap;
rules 4 to 6, 8 and 9 exist to bound a model's chatter and do not apply to what the agent wrote on purpose (they count towards 8 for the model's cues). A line
the agent placed in the silent final seconds would never be spoken, so `create_workout` and `update_workout` refuse it with the reason.

### 6. The model writes the line (`cue-facts.ts`, `prompt.ts`, `cue-check.ts`, `cue-maker.ts`)

- **The client is the insights' one** (`createLlmClient`, ADR 0025: `POST {LLM_BASE_URL}/chat/completions`, no redirect, nothing logged), made again for the cues with
  `maxAttempts: 1` (a cue has seconds, not minutes), `maxTokens` 400 (a reasoning model needs room before its one sentence), `LIVE_COACH_LLM_MODEL` when set (a
  small, fast model for a sentence, on the same endpoint and key). It gained one optional argument, `complete(messages, { signal, timeoutMs })`, so the deadline
  can cancel a request. The tests use the insights' fake.
- **The prompt.** System message, the same for every cue of a ride (so a provider that caches the start of a prompt answers faster): the rules (instruction, not
  filler; never repeat or rephrase a line already said; at most N characters in the language `LIVE_COACH_LANGUAGE`, default `INSIGHTS_LANGUAGE`; only numbers of the
  quotable list, never calculate; it cannot change the trainer, the targets or the workout; no diagnosis; answer exactly `NO_CUE` when nothing is worth saying),
  then the workout's name and description, the agent's brief, and the coach notebook sections (each clipped to 1200 characters: the model must answer in about a
  second). User message: the lines already said in this ride, the trigger and what it means, and `Facts (JSON):` with the document of facts last.
- **The facts** are built by code (`buildCueFacts`): where the rider is (step number of the session, label, minutes and seconds left, target, the 5-s means,
  compliance, the stall guard) and what the trigger found (the next step's label, length, target in watts and in percent of FTP, which of the hard steps it is;
  the direction and size of a drift; the heart rate against last time's, the difference computed). Every number goes through the insights' `Quotes`, so it is in
  `quotable`; a test checks that every numeric leaf of the facts of each trigger is. **The model never calculates.**
- **The check** (`checkCue`), by code, with the insights' validator generalised to any facts with a `quotable` list: the sentinel at the start or end of the answer
  (any case, any punctuation: `NO_CUE`, `no cue.`) is silence, not a failure; one line without markdown or wrapping quotes; at most the length; **no number or
  number word that is not in the facts** (a number it derived is refused), no word of the deny list (`INSIGHT_DENY_WORDS` too; "pedal stroke" is not the medical word),
  no link; and **no repeat**: the same words after punctuation and case are removed, or a Jaccard similarity of the word sets of 0.8 or more, against every line said
  in the ride including the agent's own. A refused line is **not asked again** (there is no time): the trigger waits (rule 9) or is spent. The log counts the
  violations of each kind and never says which.
- **The deadline**: a cue not ready **5 s** after its trigger (`LIVE_COACH_DEADLINE_SECONDS`) is dropped, never played late: a timer of the injected timers aborts the
  model's and the speech service's requests. The aim is under 3 s from trigger to the first sound (the model's answer is most of it; the owner's server was 0.5 s to the
  first byte). **The timings are logged** as `{ sessionId, trigger, outcome, llmMs, ttsMs, totalMs }` plus a failure's kind and status or the rejection's counts;
  `outcome` is `made`, `silence`, `rejected`, `llm-failed`, `tts-failed`, `deadline` or `aborted`.
- A cue is **dropped, not sent as text only,** when the speech fails: a plate of text that appears with no voice is chatter the rider cannot connect to anything.

### 7. The rider's own speech service (`tts.ts`)

`POST {TTS_BASE_URL}/audio/speech` with `Authorization: Bearer {TTS_API_KEY}` (when set) and `{ model, voice, input, response_format, speed }`; the answer must be 200,
audio (`audio/*` or an octet stream), not empty and at most 400000 bytes. **Settings**: `TTS_BASE_URL` (http or https, no credentials, no query: validated like
`BROUTER_URL`, because the rider's own server may be on the home network), `TTS_API_KEY`, `TTS_MODEL`, `TTS_VOICE`, `TTS_SPEED` (0.25 to 4, default 1), `TTS_FORMAT` (`mp3`,
`opus`, `aac`, `flac`, `wav`; default `mp3`; not raw PCM: the tablet cannot play it), `TTS_TIMEOUT_SECONDS` (default 4). No redirect is followed (the key and the text would go
with it), nothing is retried, and a failure is a kind and a status code and nothing else. **Only the cue's text (at most 80 characters) is sent there**: the readings go
to the language model, not to the speech service. `compose.yaml` passes every variable, `.env.example` lists them, `docs/deploy.md` section 21 describes them.

### 8. The record

- **`live_cues`** (migration 0012): every cue, keyed by the ride's id with no foreign key (the record is uploaded after the ride): trigger, source (`model` or `must-say`),
  text, the step it was about, the ride's time when it was triggered, when it was ready, and what the tablet reported (`played`: null while unknown). Written after the cue has
  gone out (the rider does not wait for the database); a database error is logged as its code only.
- **`get_ride`** (and `get_activity` for a ride) gains `liveCoach: { count, cues: [{ minute, trigger, source, step, text, played }] }`, present only when the coach spoke.
- **The facts the planning agent sees afterwards** (`insights/facts.ts`, so `get_facts` and the ride note): `liveCoach: { cues, said: [{ trigger, atMinute, step, text, played? }] }`,
  present only when it spoke, so the golden facts of every other ride are unchanged; every number is registered as quotable. The ride note's prompt (version 3) tells the
  model what it is and never to quote a line word for word.
- **MCP.** `create_workout` accepts `live_coach`, `coach_brief` and `must_say` in the workout; `update_workout` accepts `live_coach` and `coach_brief` and `must_say` in `steps`.
  The descriptions tell a model: set `live_coach` for key or strict sessions; write a brief (what the session is for, what to watch in this rider, what tone); use `must_say`
  sparingly, for what the coach could not know; a line in the final 15 s of its step is refused. They warn when the coach is off on this server (the settings are still saved).
  The server's instructions say the same and say that `get_ride` and `get_facts` show what the rider was told. `list_planned_workouts` marks `liveCoach: true`.
- **They live only in the service**: the `planned_workout_extras` of ADR 0036 gained `live_coach`, `coach_brief` and `must_say` (by the position of its step, valid while the steps keep
  the fingerprint they had: it now ignores the lines too). They are laid over the workout when the plan is read, so `GET /v1/workouts` carries them and the tablet reads them; **never
  written to intervals.icu or Google Calendar** (a test searches the event the fake received). The row follows the workout when it moves, and goes when it is deleted or its day has
  passed. The **ride's copy of the workout has none of them.**

### 9. Privacy

A snapshot is the rider's power, heart rate and cadence; the prompt holds them with the brief and three notebook sections. **They go to the language model's endpoint (`LLM_BASE_URL`) and
nowhere else**; the speech service gets only the cue's text; Telegram and Strava never see any of it. **No log line holds a reading, a line, a brief, a notebook text, a key or an
address**: the trigger's name, the outcome, milliseconds, kinds and status codes, counts. A test runs a session with distinctive readings, a refused line and a failed speech service and
searches the log for every one of them. The cue's text is stored on the ride, like an insight's (it is derived from the ride; the backups hold it); the audio is not kept.

## Deviations from the brief

- The reasons of a start are checked service first, then workout (section 3). `no-llm` makes the coach off even for a workout of only `must_say` lines.
- `must_say` is **not** subject to the model's limits 4 to 6, 8 and 9 (section 5).
- Added settings: `LIVE_COACH_ENABLED`, `LIVE_COACH_LLM_MODEL`, `LIVE_COACH_LEAD_SECONDS`, `LIVE_COACH_DEADLINE_SECONDS`, `LIVE_COACH_RECOVERY_BELOW_PERCENT`, `TTS_TIMEOUT_SECONDS`.
- A refused line is not retried. A cue whose speech fails is dropped, not sent as text.
- The heart-rate trigger compares with the previous attempt's per-step mean (the repeats service has no series).

## Not built, on purpose

The tablet's side; `must_say` for a workout without the model; a text-only cue; a second language per cue; the coach reacting to what the rider says; any control of the trainer;
a notebook entry written by the coach; a replay of the cues to the companion; per-trigger settings in the environment (the thresholds of section 4 are constants); the planned workouts'
`must_say` in the calendar text.

## What only real use settles

Whether the owner's speech service answers `speed` and `response_format` as sent, how long it takes for 80 characters (0.5 s to the first byte was measured for a short text) and whether
Android's `MediaPlayer` plays what it returns; the model's real latency and whether a reasoning model fits in 5 s with 400 tokens (a small non-reasoning one is expected); how often the
check refuses a line (the log's `rejected` outcomes say, with the violations by kind); whether the thresholds of section 4 (10% for 20 s, 70 rpm, 7 bpm, 85% of FTP as hard) and the lead of
6 s suit the bike and the D500's 4 to 9 s response (ADR 0033); whether 12 an hour and 60 s between cues are too much or too little; whether a proxy in front of the real service buffers the
stream (`curl -N`, `docs/deploy.md` section 21); whether Caddy's `common` snippet compresses event streams; how the stream behaves across the tablet's wifi sleep.

## Consequences

- The tablet stage has a complete, fixtured contract to build against and a service it can test with `curl` (`docs/deploy.md` section 21).
- `Services` has `liveCoach`; `ExtrasStore` has `setCoach` and its rows carry the three fields; `LlmClient.complete` takes an optional `{ signal, timeoutMs }`; the insights' validator takes
  any facts with a `quotable` list.
- **What the tablet stage must implement:** read `live_coach` (and keep `coach_brief` and `must_say` out of the UI) on the planned workout, ignore them in its own structure hash; at the start of
  a ride from a workout with `live_coach`, `POST /v1/live/sessions` (the workout id, the `structureHash` it was served, `variantRidden`, its clock) and stop there when `coach.on` is false (and
  say "Live coach unavailable" for a reason that is not `no-live-coach`); stream a snapshot every 5 s with the means of the last 5 s; open the stream with `HttpURLConnection` (no read timeout longer
  than 30 s, reconnect after `retry` with `Last-Event-ID`, stop on `end`, start again after `shutdown`); decode `cue` events, play the audio with transient audio focus **only within `ttlMs` of
  receiving it**, show the text on the plate under the overlay, `POST …/played` (optional); an overlay control that mutes the coach for the rest of the ride (the snapshots then say `muted: true`);
  `POST …/end` when the ride ends; treat a `404` on a snapshot as "the session is gone" and start a new one at most every 30 s; do nothing and show nothing when offline.
- **Rollout order:** (1) this stage's service (the contract is additive: nothing changes for a tablet or companion that knows nothing of it); (2) the tablet's and the companion's Kotlin models learn
  `live_coach`, `coach_brief` and `must_say` (their decoders reject unknown keys: `GET /v1/workouts` carries them once the agent writes one, and the companion's ride detail does not, because the ride's
  copy has none); (3) only then does the coach agent write them; (4) the owner sets `TTS_*` and `LLM_*` and `LIVE_COACH_*` in `.env` (section 21 of the deploy guide).
