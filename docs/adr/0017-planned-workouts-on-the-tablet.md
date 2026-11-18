# ADR 0017: Planned workouts on the tablet

Status: accepted (Phase 2, stage P2-2b, 2026-11-18). The service's half of the stage (`GET /v1/workouts`, the plan cache, the
conversion from intervals.icu) is stage P2-2a and has its own ADR; this one is the ride app's.

## Context

Stage P2-2 puts the rider's plan on the tablet (spec §5.7): the workouts planned in intervals.icu appear on the Ready screen,
starting one plays it like any workout, and the ride is linked to the plan. The contract is fixed in `contracts/`
(`workouts-response.schema.json`, `core`'s `PlanApi.kt`): the service answers `GET /v1/workouts?from&to` with workouts already
in the native format, a `stale` flag (the service's last refresh of intervals.icu failed) and a `configured` flag (it has no plan
source). The tablet reads the service the ride upload already talks to (ADR 0014), and the app works fully offline (CLAUDE.md).

## Decision

### 1. Where it lives

- **`core`** (`app.overdrive.core.plan`, no Android): `PlanRepository` (fetch, cache, state), `PlanCache` and `PlanCacheFormat`
  (what the cache file holds), `PlanAgenda` (today and the days after, read through the clock), `PlanCalendar` (date arithmetic:
  core has no calendar), and `WorkoutChecks` (can the player run it; the intensity hint). The transport is an interface,
  `HttpGetTransport` in `core.sync` beside `HttpTransport`, so the repository is tested against a scripted one and the fixtures
  of `contracts/fixtures/plan` with no network and no Android.
- **`android/data`**: `UrlConnectionTransport` now implements both interfaces (one class, one stack, the same timeouts, no
  redirects, nothing logged, no new dependency) and `FilePlanCache` is the cache on disk. `StoredRide` gained `workoutId`.
- **`android/ui-kit`**: two list rows, `ValueRow` and `DisclosureRow` (design/system.md §7.5).
- **`android/app-ride`**: the container owns the repository, `PlanUi` turns its state into the words of the screen,
  `PlannedSection` draws it, the Ready screen shows it.

### 2. When it asks, and what

- The range is **today to today plus 13 days**, in the tablet's local dates (the service caches a fortnight, spec §5.1). The
  Ready screen lists today and the six days after; the rest is fetched so that a tablet that is offline for a few days still
  has the coming week in its cache, and the cache is read through the clock (`PlanAgenda`), not at the time it was written.
- When: at start (once the settings have been read from disk, because before that the address is empty); when the Ready screen
  comes on screen, **at most once a minute** (counted from the last request, whether it worked or not, so an offline tablet does
  not retry at every glance); when the address or token has changed and **settled for 1.5 s** (the same rule as the upload); and
  when the rider presses the refresh button, which always asks. One request at a time: a refresh that arrives during one is
  dropped (the one under way answers for it), except a change of settings, which is made again afterwards because the one under
  way used the old address or token.
  A change is written down before the lock is tried and looked at again by whoever lets go of it, so none is lost in the instant
  between a request's last look and its letting go; and a change that finds the last request made with these very address and
  token asks nothing (the start and the first settle of the settings can both say so). **The plan follows the settings file
  itself**, as a flow that is empty until the file has been read, and not the container's `settings` copy, which starts as the
  defaults and can be a step behind the file: the first value read starts the plan (the cache is shown, then one request), every
  later one is a change, and a change during a request abandons that request.
- Nothing runs on the main thread: the request is the transport's (`Dispatchers.IO`), the repository runs on the container's
  scope, and the screen only reads a `StateFlow`. The Ready screen makes its rows again when the plan or the stored rides change
  and not otherwise, so nothing recomposes at rest. No timer re-reads the list at midnight; the Ready screen is entered again
  after every ride and every visit to Settings, which does.
- **Same rules for secrets as the upload** (ADR 0014 section 8): address and token are read from the settings at every
  request, the token is only in the header (`HttpGetRequest.toString` leaves it out), nothing logs, the state and the problems
  never hold it, and the cache file holds the answer and a checksum of the address, no token.

### 3. What every answer comes to

| Answer | Shown | List |
|---|---|---|
| 200, a valid answer of the contract | "Updated 07:30" (or "Out of date · updated 07:30" when `stale`) | replaced, and kept on disk |
| 200 with `configured: false` | **nothing: the section is not drawn** | none (the cache says so too) |
| 200, anything that is not the contract's answer | problem "That is not the Overdrive service" | the last good one stays |
| 401, or a token that cannot be a header | "The service did not accept the token. Check the token in Settings" | the last good one stays |
| 404 `not_found` (a service without the route) | "The service has no planned workouts" | the last good one stays |
| other 4xx, 5xx, 408, 429, a redirect | "The service refused the request (n)", "Service error (n)", … | the last good one stays |
| no answer (unreachable, timeout, TLS, an answer over 1 MiB) | "Service unreachable", … | the last good one stays |
| no address or no token | **nothing, and nothing is asked** | none |

- **A rejected token is remembered** with the address and token it was rejected for: nothing is asked again until either
  changes or the rider presses refresh, exactly like the upload (a stuck token is not retried at every glance).
- **The stale marker** is shown when the list may be out of date: the service said so, or the last request failed. The time is the
  `generatedAt` of the list on screen (when the service last read intervals.icu), because that is how old the list is; a list
  that the service never refreshed says "Out of date · not updated yet".
- **"Not set up" is not an error.** The section only exists when there is something to say: a list, a problem, or a request
  under way. A tablet that was never given an address behaves exactly as before this stage.
- The answer is read strictly with `PlanApiJson` (the schema's rules: dates, ranges, unique ids, `workout.id` equal to the
  entry's id). One entry the model refuses makes the whole answer "not the service": the service is the one that leaves out
  what it cannot convert, so a refusal here is a contract violation to see, not to paper over.

### 4. The cache file

`filesDir/plan/workouts`: one header line, `overdrive-plan 1 <source> <fetched at> <crc32 of the body>`, then the answer exactly
as the service sent it. `<source>` is a CRC-32 of the service address (an answer cached for another address is not this rider's
plan, so a changed address leaves it unused and a new answer replaces it). It is written through a temporary file that is forced
to storage and renamed over the cache (an atomic move), so a crash at any instant leaves the old file or the whole new one.
It is read strictly: a missing file, a truncated or edited one, an unknown version, a wrong checksum, and a body that is not a
valid answer of the contract all read as **no cache**, never as an exception and never as half a list. A write that fails costs
the cache and nothing else.

### 5. Starting a planned workout

A planned row calls the same `RideHost.select(RideChoice.Planned(workout))` as every local workout, with the `Workout` of the
plan entry. The ride engine already records `ride.workoutId = workout.id`, and the contract makes that equal to the entry's id,
so the link needs **no mapping and no change to the engine**. The stored ride keeps the id (the small `.meta` file beside the
record gained a `workoutId` line; a ride stored before it reads as having none, which is true: nothing was planned then), and
the record that is uploaded carries it. `RideLibrary.ridden` lists, from the scan at start and each ride as it is stored, which
workout each stored ride was made from.

- **Done.** A planned workout is marked "Done 18:04" (a check and the time of the newest stored ride that carries its id; the
  date too, "Done 17 Nov 19:15", when that ride was not on the day it is planned for) and can still be ridden: the row stays a
  button. Matching is by id alone, because a planned workout is one calendar event: a ride of Friday's workout done on Thursday
  is Friday's workout done. A ride that was started and stopped at once counts like any other; the rider can ride it again.
- **What the player cannot run.** Every workout the schema accepts uses steps the player knows (steady, ramp, repeat, free), so
  what is left to refuse is a workout the **ride** cannot hold: more than **12 hours** (a ride record of that length is about
  10 MB of JSON, under the service's 16 MiB limit, ADR 0014 section 7) or more than **2000 steps** once the repeats are expanded.
  `Workout.playability()` decides it by arithmetic on the numbers in the steps (saturating, in `Long`), and **never expands the
  repeats**: a repeat of two billion rounds is refused without `flatSteps` ever running (it would run out of memory, and its start
  times overflow an `Int`). Such a row is shown disabled, with the reason in place of the description, and takes no press or
  focus. It is never a crash.
- **Intensity hint.** The trailing "IF 0.76" is the normalised power of the workout's own targets (30 s rolling average, fourth
  power mean, fourth root; free-ride steps prescribe nothing and are left out) over FTP, which is what a rider calls IF; the
  targets are fractions of FTP, so no FTP is needed. No hint for a workout with less than 30 s of prescribed power.
- **The description** is the workout's own, its first non-empty line; without one, the kinds of step it has ("Steady, ramp,
  intervals").

### 6. The Ready screen, and the Click

The **Planned** section is in the left column above **Ride** (design/system.md §6.1): label and a 48 dp refresh button, one line
of state, today's rows, then **This week** as a collapsed row (its count; the days open under it, "Tomorrow", "Sun 22 Nov"). It is
made of `ValueRow` (a selection row with the length and the hint on the right) and `DisclosureRow`; no new visual language.

**Reaching it.** The rows are ordinary clickable controls, so they take focus in the order they are on screen (refresh, today's
rows, **This week**, the days, then the local list) and the key that activates a focused control (Enter, the d-pad centre) chooses
them; a focused row draws the 2 dp ring that design/system.md §7.1 specifies for focus. Compose lets a clickable take focus only in
keyboard input mode, which the first hardware key switches on; the tests do the same. **That is what "reachable from the Click"
comes to on this screen, and it is the same as for every other item of it**: the Click is not a keyboard. It reaches the tablet as
a Bluetooth LE controller whose presses the app translates into the **ten ride actions** (spec §4.7), none of which moves focus or
selects, and the Ready screen has never been operated by it (Start, the workout list and the Settings buttons are touch). Making
the Click choose a planned workout would mean giving some of its ten actions a meaning on the Ready screen (for example the two
interval buttons moving the selection and a long press starting the ride), which is a decision about the handlebar's design, not
about this stage, so it is **not** done here and is listed below.

### 7. Not done, deliberately

- No pull-to-refresh gesture: it is a touch gesture over a scrolling list, needs a dependency, and the refresh **button** does the
  job and is reachable by focus.
- No background refresh (no WorkManager, no alarms): the app asks when it is opened, and a plan seen once stays in the cache.
- No creation, editing or deleting of planned workouts on the tablet (the AI agent and the MCP tools do that, stage P2-3).
- No Strava, no Google Calendar (stages P2-4 and P2-5). Nothing here reads Strava.
- `docs/spec.md` §8.3 and the roadmap are the service stage's and the orchestrator's to update; spec §5.7 has the tablet's note.

## What only the real tablet and the real service can settle (bench 7.10)

- How long the section takes to appear after the app opens, over the home Wi-Fi, and whether the first request at start (the
  settings are read from disk first) is ever slower than the Ready screen's first frame.
- Whether `HttpURLConnection` on this tablet answers a `GET` with a 401 the way the JDK of the tests does (status first; the body
  of an error is read when there is one).
- What the real intervals.icu workouts look like once converted: whether descriptions read well as one line, whether the intensity
  hint matches what intervals.icu shows for the same workout, and whether any workout the service sends is one the player refuses.
- Whether the **Click** can be made to move focus at all on this tablet (it is presumably not a HID device to Android) and what
  riders would want it to do on the Ready screen: the decision in section 6, with real use to inform it.
- Whether the ring and the rows read well at arm's length on the bike, and whether "This week" collapsed is the right default.
- Whether a ride that was only started and stopped should count as "Done" (it does today); a rule such as "at least half of the
  workout" is one line in `PlanUi`.
- The pairing of the uploaded ride with the planned workout in intervals.icu (spec §5.5) is the service's stage; the tablet only
  guarantees the id is in the record.

## Consequences

- The ride app has one more network call, a `GET` to the address the owner typed, with the token the upload already uses. With no
  address or token it makes none, and with no network everything but the list's freshness works.
- The plan is data the rider chose to plan, kept in a private file of the app (not backed up, `allowBackup` is off).
- A change of the plan format is a change to `contracts/` first; the tablet's reading is as strict as the schema.
- Local workout files, the bundled workouts and the ride engine are untouched; the only change to a stored ride is one more line
  in its small `.meta` file.
