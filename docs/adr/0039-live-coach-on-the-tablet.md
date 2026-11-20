# ADR 0039: The live coach on the tablet

Status: accepted (v0.4.0, stage A3 part 2: the ride app's side, 2026-11-19; Lific OVD-21). The service and the contracts are ADR 0038. **Nothing here was heard on the bike, through
the owner's real speech service or on the tablet's `MediaPlayer`**: it was written and tested with a fake speech player, a local server that writes `text/event-stream` and a JVM. The
last section lists what only the real thing settles.

## Context

ADR 0038 ends with what the tablet must implement: read `live_coach`, ask for a session, stream a snapshot every 5 s, listen to the cues, play them with transient audio focus within their
`ttlMs`, show the text, let the rider mute for the rest of the ride, end the session, and do nothing at all without a connection. The rules of `CLAUDE.md` bound the answer: the ride app works
fully offline, has no Google Play Services, talks only to our server and adds no network library (the platform's `HttpURLConnection`); the overlay recomposes at most once a second at rest and
has no continuous animation; BLE and the engine stay off the main thread. The coach only talks: it never changes a target, the intensity or the workout.

## Decision

### 1. The decisions are in `core`, pure, and the Android side is plumbing

| Piece (`app.overdrive.core.live`) | What it decides | Tested with |
|---|---|---|
| `LiveCoachApi` (`LiveCoachJson`) | The wire bodies, read as strictly as the schemas (unknown keys, a text where a number is meant, every bound and rule) | every fixture of `contracts/fixtures/live-coach` (`LiveCoachContractTest`) |
| `SseParser` | Server-Sent Events by the standard: CRLF, LF and lone CR, comments, multi-line `data`, `retry`, `id` with a NUL ignored, a byte order mark, an event cut short never dispatched, limits | tables, cut in every place, the service's own stream |
| `CueStream` | Keeps the stream open: reconnects after `retry` (held between 1 and 60 s) with `Last-Event-ID`, doubles the wait for each connection that gave nothing up to 30 s, stops on `end`, a `404` or a rejected token | a scripted source in virtual time |
| `LiveSnapshotBuilder`, `LiveCompliance` | The snapshot and the compliance percent (section 3) | invented rides |
| `LiveSessionController` | **When**: start, snapshot, restart, stop, mute (section 2) | a table over virtual time |
| `CuePlayback`, `CuePlayer` | Whether and when a cue is spoken (section 4) | a fake player in virtual time |
| `LiveCoachClient` | The POSTs and the stream, **through the one `ServiceClient`** (address and token read at every request, its timeouts, its remembered rejection of a token) | a fake transport |

`UrlConnectionTransport` (`android/data`) is the `HttpStreamTransport` beside GET, POST and PATCH: the same class, stack and rules for secrets. `app.overdrive.ride.live.LiveCoach` joins the ride
to the service (feeds the snapshots to the controller, carries out what it asks for, wakes it when it asks to be woken); `AndroidCuePlayer` is the thin `MediaPlayer` part.

### 2. The session flow

- **A ride has a controller only if the coach is wanted**: the planned workout asks for it (`live_coach`), the rider has **Live coach** on in Settings (read when the ride starts) and the service
  is set up. Otherwise nothing runs and the overlay says nothing. The ride's own workout, the engine's and the record's, is `withoutLiveCoach()`: no brief and no line reaches the engine or any screen.
- **Start**: at the first moment the ride is live, `POST /v1/live/sessions` with the ride's id (the record's), the workout's id, the structure hash it was served (the easier version's when that is ridden)
  and `variantRidden`. The ride never waits for the answer.
- **The coach is off** (`coach.on` false): it stops for the ride. For every reason except `no-live-coach` the overlay says **"Live coach unavailable"**, once, for 8 s; for `no-live-coach` nothing is said.
- **No answer** (offline, a failing service): nothing is said or shown, and **the start is tried again every 30 s while the ride lasts** (a decision beyond the brief: wifi that comes back
  after the start gets its coach, in the spirit of "cues resume on reconnect"; spec §12.1 says an offline start ignores the flag and says it is unavailable, ADR 0038 says to show nothing: this follows ADR 0038).
  An answer that will not change (a service without the route, a rejected token, nothing set up, a reason this build does not know) is not asked again.
- **Snapshots** every 5 s, the first at once, `seq` from 0 in each session, at most one in flight (a slow network skips one). A **`404`** (on a snapshot or the stream) means the session is gone: a new one,
  **at most one start every 30 s**. The stream's `end`: `ended` and `superseded` stop the coach for the ride, `shutdown` starts a new session (under the same limit), a rejected token or nothing set up stops it.
- **Mute** is one tap or one Click press and is **for the rest of the ride and cannot be undone**: the service keeps a muted session muted (ADR 0038 §5), so an undo would be a lie. The snapshots
  carry `muted: true` at once, every cue that arrives is ignored, what is being spoken is cut off.
- **The end of the ride** (finished, or the rider stopped it) ends the session (`finished` when the workout ran to its end, else `stopped`) and closes the stream.
- **Nothing here can raise an error on the riding screen**: a failed request is an answer the controller has a rule for, every entry into the driver is guarded, and nothing logs a reading, a cue's text or a token.

### 3. The snapshot, and the compliance percent (documented in `LiveCompliance`)

`RideSnapshot` gained **`rideSeconds`**, the ride's own clock with the pauses in it (the offset of the latest sample): it is the live `elapsedSeconds` and what tells two snapshots of one tick apart (the engine
also publishes after every command). The means of power, heart rate and cadence are over the last 5 ticks, in whole numbers, absent when not measured (a "heart rate" under 20 bpm is absent). `stepCount` is
exactly the flattened workout's step count; the target is the step's (scaled by the intensity, **never the stall guard's cut**), or in a heart-rate step the heart rate the controller holds.

**Compliance** = the share of the current step's **judged** seconds that were on target, whole percent, halves up, none before the first judged second and none in a free step. A second is judged when the ride is
riding (not paused), the step has been going at least a second (the tick that enters a step still carries the readings of the one before), at least 5 s have passed since a resume (the stall guard does not judge them either),
the stall guard is not holding the target down, and there is a reading to judge. **On target**: power within 5% of the target the trainer is asked for; in a heart-rate step the controller's own verdict (inside the band, once
its start ramp is over and the strap has a signal). It starts over when the ride moves to another step or back to the start of this one. **Events** (newest first, at most 8, at most 10 minutes old): the stall guard taking
hold, a pause, a resume, and the strap lost (a heart rate that was there is gone for 15 s; reported once per loss).

### 4. Playing a cue (`CuePlayback` over `CuePlayer`; `AndroidCuePlayer`)

- **Only within `ttlMs` of receipt** (the app does not trust its clock against the service's): a cue that waited behind another past its time is dropped, the player is never asked, nothing is shown. One at a time;
  at most 2 wait behind the one being spoken (the oldest waiting is dropped). A cue number at or below the last seen (a stream that came back and resent it) is ignored. Focus refused, an error or a cue that cannot be
  decoded drops the cue and the next is tried.
- **The plate** (the cue's text) appears when the sound **begins** and goes **6 s** after it ends, in one step each way; a cue that is not spoken has none.
- **Every cue is reported** with `POST …/played` (`true` if it was heard to its end, `false` for every other fate: expired, muted, focus refused, error): the log of the ride then tells the agent whether the rider was told.
- **Audio focus** is `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` and `CONTENT_TYPE_SPEECH`: the pair a video app lowers its volume for and raises again when the focus is abandoned (a
  map's spoken directions are the same). Not `GAIN_TRANSIENT` (it pauses the video) and not `USAGE_MEDIA` (the video does not duck for a peer). Delayed focus is not accepted: a cue is worth hearing now or not at all. Losing
  the focus while speaking stops the cue (a call). The speech is given to `MediaPlayer` from memory (a `MediaDataSource` over the decoded bytes): **no file is written**, nothing is left to clean up, and the bytes
  go when the player is released. The player and the focus are released on every way out, including a cancel by a mute or the end of the ride, and a cue is cut off after 30 s whatever the player says.
- **It lives in the process, not the activity**: the container owns it, the foreground service of a live ride keeps the process alive, so the video app can be in front. A `AudioFocusRequest` and a `MediaPlayer` work from the
  main thread, where `AndroidCuePlayer` runs them; the decisions and the network are on one thread of their own.

### 5. The SSE client (`UrlConnectionTransport.stream`)

A `GET` with `Accept: text/event-stream`, the bearer token and `Last-Event-ID`; **read timeout 25 s** (longer than the 15 s heartbeat, within ADR 0038's 30 s); the response is decoded as UTF-8 by a reader that holds half a
character until its other half arrives; a status other than 200 is the answer. **The blocking read runs apart from the caller**: closing a connection under a waiting read wakes it on Android (OkHttp cancels the call) but not on the JDK,
so cancelling the coroutine returns at once and closes the connection from another coroutine, and a read still blocked ends with its timeout (a test proves cancelling an idle stream takes milliseconds). Reconnection, backoff and the
stop are `CueStream`'s; a ride's end or the app leaving a live ride cancels it. No busy loop: the shortest wait between two connections is 1 s whatever `retry` says.

### 6. The overlay, the Click, the settings

- **Under the panel and apart from it**, so the panel's geometry (84, 108, 282, 306 dp) is unchanged: a **mute control** (132 × 40 dp, "Coach on" / "Coach muted", a muted coach in primary text like "Paused"), present only while a session is on; the
  one-time **"Live coach unavailable"** (the same height, 8 s); and the **plate** (464 dp wide, body text, one or two lines, below the control so the control never moves). The coach's state is its own flow, not the ride's: a cue or a mute
  recomposes the window once, never per second, and a hundred ride snapshots at rest with the coach on recompose nothing. A tap that goes down on the control mutes; any other tap toggles the mode. Design/system.md §5.7.
- **The Click**: `ClickAction.MUTE_COACH`, the eleventh action, **on no button by default**. The default map and its stored text are unchanged (`ButtonMapping.DEFAULT`, five fields); the two tables that counted ten actions now say ten and an
  eleventh. A sixth button can be given under Devices (**Live coach · Set / Change / Clear**, `CoachButtonLearner`): it fires on the press, once, with no hold. Learning the five roles again keeps it unless a role takes its button.
- **Settings, Ride**: **Live coach**, on by default, with its explanation (spoken cues from the service, needs the connection, never changes targets). **The Ready screen's plan row** says **"Live coach"** (and "needs the connection" when the last refresh could
  not reach the service; "Live coach off" when the rider switched it off); the brief and the lines are in no text.

### 7. The companion

`app-companion` decodes `GET /v1/workouts` and a ride's detail through the same `core` classes, so it reads `live_coach`, `coach_brief` and `must_say` as of this change; a test (`LiveCoachWorkoutsTest`) feeds its container a plan whose workouts carry all three
and checks Today says what it said before and shows neither the brief nor a line. **Install the tablet and the companion of this release before the coach agent writes any of them**: an older app refuses the whole plan answer ("That is not the Overdrive service").

### Order of the rollout

(1) ADR 0038's service, (2) **this release on the tablet and the companion**, (3) only then does the coach agent write `live_coach`, `coach_brief` and `must_say`, (4) the owner sets `TTS_*`, `LLM_*` and `LIVE_COACH_*` in `.env` (deploy guide section 21) and
checks with the bench steps below. A service that predates a client, or a client that predates the service's keys, costs only the feature: the ride is never affected.

## Not built, on purpose

An unmute (the service would not hear it); a text-only cue when the speech fails (the service drops it); the coach's state on the ride screen (the overlay is where it lives; the ride screen's controls are the rider's); the coach's lines in the ride record (ADR 0038: the
service keeps them); a setting for how long the plate stays (6 s); a per-cue volume; any control of the trainer.

## What only the tablet and the bike settle

Whether `MediaPlayer` plays what the owner's speech service returns (mp3 from memory, and the `opus`, `aac`, `flac`, `wav` the service can be told to write); that the video **ducks and comes back** (YouTube and the other apps differ: some pause for a transient request); how
late a cue is after its trigger on the tablet's wifi; whether the plate reads at arm's length over bright video and whether 8 s and 6 s are right; that the Click button mutes with the video app in front; that the stream survives the tablet's wifi sleep and the
read timeout of 25 s suits ZUI; that the 5 s snapshots and the open stream cost no noticeable battery over an hour (spec §7.3). Bench: `docs/bench-checklist.md` session 14.

## Consequences

- `core` gains `app.overdrive.core.live`, `Workout.liveCoach` / `coachBrief` and `LeafStep.mustSay`, `RideSnapshot.rideSeconds`, `ClickAction.MUTE_COACH`, `ButtonMapping.coachMuteMask`, `CoachControl`, `HttpStreamTransport` and `ServiceClient.stream`.
- `UrlConnectionTransport` implements one more interface; `RideHost` takes a `LiveCoachSink` and tells it of every snapshot; `OverlayModel` gains `coach` and `muteCoach()` (defaults, so a fake need not know);
  `ui-kit` gains `OverlayCoach` and the `overlay.coach` tokens (`design/tokens.json`, regenerated).
- Nothing in `contracts/` or the server changed.
