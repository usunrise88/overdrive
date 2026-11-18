# ADR 0022: Training metrics

Status: accepted (Phase 3, stage P3-1, server side, 2026-11-18).

## Context

Spec §6.3 asks for the training-load part of Phase 3: per-ride load, fitness, fatigue and form, an FTP estimate that needs no test,
aerobic drift, the power curve and the W′ balance, with the acute-to-chronic workload ratio expressly not used. The companion app
(`design/companion.md`, §11 "Data the screens need") and the MCP tool `get_fitness` (spec §8.4) read them. This ADR fixes every formula,
seed and threshold, the tables, the endpoints and what is deliberately **not** claimed. The rule of `CLAUDE.md` holds throughout: the
service computes, a model only writes text from what was computed; nothing here reads Strava.

Written without real rides: all the numbers below are checked against hand-worked examples (`server/test/metrics-math.test.ts` states each
expected value from its formula) and against synthetic rides. The last section lists what only real data can settle.

## Decision

### 1. Shape

New module `server/src/metrics/`, the same layering as the plan and profile modules:

| File | Role |
|---|---|
| `power-curve.ts`, `load.ts`, `ftp.ts`, `drift.ts`, `wbal.ts` | Pure functions, no database, no clock |
| `ride-metrics.ts` | What is worked out once per ride (`computeRideMetrics`) |
| `store.ts` | PostgreSQL: `ride_metrics`, `ftp_suggestions`, `ftp_history` (migration 0006) |
| `service.ts` | `services.metrics`: computes, remembers, answers; the listeners on `ride-stored` and `profile-changed` |
| `routes.ts` | The HTTP endpoints below |
| `src/mcp/tools/fitness.ts` | `get_fitness`; `get_ride` gains the curve, drift and W′ |

Shared files touched, additively: `services.ts` (the `metrics` member and its start/stop), `app.ts` (one route registration),
`ride-workout.ts` (a `failed` flag on a lookup that failed, for `stale`), `profile/store.ts` and `events.ts` (the new `wPrimeKj` part),
the MCP server's list of tools.

### 2. Days, and where load comes from

A ride belongs to the **local day of its start in `TIMEZONE`** (`localDate` of `time.ts`): a ride that begins at 23:30 UTC is the next day's in
Paris. Daily load is the sum of the training stress score (TSS) of the day's rides; a ride's TSS is the one the server computes from the stored
record with the ride's own FTP (`ride-stats.ts`, equal to the tablet's by a test), stored in `ride_metrics.load`; null (under 30 s of power) is
0 load, the ride still counts as a ride.

Load comes from **sources**: a `LoadEntry` is `{ date, kind: "cycling" | "other", load }`. Rides are the one source built; others
register `services.metrics.registerLoadSource({ name, entries(untilDate) })` (stage P3-2 will register the heart-rate load of watch
activities, spec §6.7). `kind: "other"` adds to the day's load, fatigue and form and to the weekly `otherLoad`/`activities`, and
nowhere else: the power curve, the eFTP and the aerobic drift read rides only. A source that throws makes the answer `stale: true` and is
left out; the rest is still computed. Likewise `registerActivitySource` lets watch activities appear in `GET /v1/rides`.

### 3. Fitness, fatigue, form

For each day, in order, from the first day with any entry:

```
k_fitness = 1 - exp(-1/42)        k_fatigue = 1 - exp(-1/7)
ctl = ctl_yesterday + (load - ctl_yesterday) * k_fitness
atl = atl_yesterday + (load - atl_yesterday) * k_fatigue
tsb = ctl - atl                    (the same day, at the end of it)
```

* **Seed:** both averages are 0 the day before the first day with any load and stay 0 until then (`firstLoadDate` says which day). Rest days
  are days with load 0 and are walked like any other. Until a few weeks of data exist, fitness is small by construction; the tools say so.
* The exponential form `1 - exp(-1/τ)` is the one of the impulse-response model; the commonly seen `1/τ` differs by about 1% and is not used.
* **Form is the same-day difference.** TrainingPeaks shows the form of the morning (yesterday's fitness minus yesterday's fatigue); that is
  this series shifted by a day.
* Rounded to one decimal on output; the series is computed in full precision.
* **Not implemented, on purpose:** the acute:chronic workload ratio (spec §6.3: Impellizzeri et al. 2020 show conceptual flaws). Nothing
  in the answers or the tools computes it, and a test asserts the word is absent.

What is **claimed**: the Coggan model (NP, IF, TSS) is a practitioner standard from *Training and Racing with a Power Meter*, not a
peer-reviewed physiological law; Banister's impulse-response model (1975) is established. The 42-day and 7-day constants are the
conventional ones, not fitted to this rider.

### 4. The power curve

* **Durations:** the 25 of the companion's design (design/companion.md §6.4): 1, 2, 3, 5, 8, 10, 15, 20, 30, 45 s; 1, 1.5, 2, 3, 4, 5, 7, 10, 15,
  20, 30, 40, 60, 90, 120 min. A ride shorter than a duration has no value for it.
* **Gaps (decision):** a window is a stretch of the ride's own clock, one sample per second, and **a paused second or a second with no
  power reading counts as 0 W.** This is also what `get_ride`'s best efforts always did, so one number means one thing. The rule can
  only lower a value: a rider who stops, or whose meter drops out, is not credited for seconds nothing was measured, so the curve and the
  eFTP made from it are conservative. The alternative (joining the pieces either side of a gap) would count a rest as work. A ride with no
  power reading at all has no curve, not a row of zeros.
* **Not forced to fall:** the curve is the raw best of each duration. A window of 10 s can average more than the best 8 s when its two
  extra seconds are hard ones; on a real ride it nearly always falls, and the contract does not insist.
* **Kept per ride** in `ride_metrics.power_curve` the moment the ride is complete (the `ride-stored` event) or, for rides stored before, on the
  next read; one decimal.
* **Aggregation:** `GET /v1/metrics` gives the best of each duration among the rides of the requested range (`range`) and among all rides up
  to its end (`allTime`), each with its ride and day; a tie goes to the later ride. The aggregation reads the stored curves, never a record.

### 5. The FTP estimate (eFTP) and its suggestion

**An estimate is a suggestion only. Nothing in the service changes the profile's FTP; the rider confirms (stage P3-5), the zones move only then.**

The rule (`ftp.ts`), in the manner of the eFTP of intervals.icu but simple and inspectable:

1. The rides of the **last 42 days** (local days, today included).
2. Two candidates, each a lower bound of the FTP if the rider did not go all out: the best 20-minute power × **0.95** (Allen and Coggan's
   field test), and the best 60-minute power × **1.00**.
3. A candidate counts only if at least **2 rides** have a comparable effort at that duration (at least **90%** of the best), so that one
   unusual ride cannot move the FTP.
4. The estimate is the larger of the candidates that count, rounded half up to whole watts; its **basis** is the best effort it came from
   (duration, power, ride, day).
5. **Confidence** by how many rides back the effort: 2 low, 3 to 4 medium, 5 or more high.
6. With no candidate the answer is `null` with a reason in words ("Only 1 ride in the last 42 days has a 20-minute effort close to the best;
   2 are needed so that one unusual ride does not move the FTP", "No ride in the last 42 days has 20 minutes of power").

**When a suggestion is made** (`ftp_suggestions`): when the estimate differs from the profile's FTP by **at least 3%** (exact integer comparison,
`|value - ftp| × 100 ≥ 3 × ftp`; always when the profile has no FTP) and:

* **at most one is pending**; a new one that differs from the pending one by **3 W or more** supersedes it (status `superseded`), a smaller
  movement leaves the pending one as it is; a pending one that no longer applies (the efforts aged out, the FTP moved close to it) ends as
  `superseded` too;
* **dismissal** (`POST /v1/ftp-suggestion/dismiss {suggestionId}`, the rider keeps their FTP): status `dismissed`; nothing is suggested again
  until the estimate moves **3 W or more** from the dismissed value (design/companion.md §11). A dismissal is forgotten once the FTP has changed
  since, because the question is then another one. Dismissing again is harmless, dismissing a suggestion that is already `accepted` or
  `superseded` changes nothing and says so;
* **acceptance** is `PATCH /v1/profile {ftp}`: when the profile gets exactly the pending suggestion's value, the `profile-changed` listener marks
  it `accepted`; any other value leaves it pending (and is re-evaluated against the new FTP).

The statuses and their timestamps are kept, so the history of what was suggested and what the rider did with it is not lost. It is
re-evaluated on every `ride-stored`, every FTP change and at start; never by a GET.

**FTP history** (`ftp_history`): every change of the profile's FTP, with when, from the `profile-changed` event (clearing the FTP is a
change). An FTP that was set before the table existed is read from the profile's own `updatedAt`. `ftp.history` in `GET /v1/metrics` gives the
changes of the range with the value in force at its start first; `ftp.setAt` is the day of the last one. Accepting a suggestion is a profile
change, so accepted suggestions are in the same history. `ftp.estimates` is the estimate as of the end of each week of the range (as of
the range's last day for the last week), dated, for the companion's dots; `ftp.latestEstimate` is today's, suggested or not.

What is **not** claimed: that 95% of a 20-minute power is the rider's FTP (it is the usual field-test convention and is wrong for riders
with a strong or weak anaerobic reserve); that the estimate beats a test. The numbers are a prompt to look, which is why they only
suggest.

### 6. Aerobic drift

Pw:HR decoupling in the manner of Friel (coaching practice, not a validated clinical measure). For a ride (`drift.ts`):

* A **steady stretch** of at least **40 minutes**: the whole ride, or one lap of it (a structured workout with a long steady step); the longest
  one that qualifies is measured.
* A second **counts** when it is not paused and has a power reading and a heart rate above 0. At least **95%** of the stretch must count.
* It is **steady** when its variability index (normalised power over average power, over the counted seconds) is at most **1.05**.
* The counted seconds are split into two halves of equal length. For each half, EF = normalised power over average heart rate. **Drift
  = (EF first − EF second) / EF first × 100**, positive when heart rate rose against power. One decimal; EF three decimals.
* A ride that does not qualify is skipped **with the reason in words**, never measured badly. Other activities never get here.

The design says "45 minutes or more" in one note; the spec's Friel rule is 40, which is what is built (one constant, `DRIFT_MIN_SECONDS`).

### 7. W′ balance

Skiba et al. (2012), the integral model, computed second by second (`wbal.ts`):

```
expended(t) = max(P(t) - CP, 0)                       joules in second t
E(t)        = E(t-1) * exp(-1/tau) + expended(t)      (the integral, exactly, at 1 Hz)
balance(t)  = W′ - E(t)
tau         = 546 * exp(-0.01 * D_CP) + 316           seconds
D_CP        = CP - mean of the powers below CP        watts, 0 when no second is below
```

* A paused second and a second with no reading count as 0 W (so W′ recovers) but are left out of D_CP.
* **CP is the FTP**, which is the rider's own approximation to it: the profile's FTP, else the FTP the ride was recorded with, unless the caller
  gives `cp`. **W′** is the profile's new field `wPrimeKj` (1 to 100 kJ, null for the default), else the default of **20 kJ** (a typical value, a
  placeholder until the rider sets theirs), unless the caller gives `wPrimeKj`. Every answer says which source was used.
* Output: the time constant, D_CP, the lowest balance and its second, how much of W′ it used; the series (every 5 s on the metrics
  endpoint, every 10 s or coarser with the ride detail) only when asked. A balance below 0 means the model says W′ was exceeded, that is,
  the CP or W′ are too low for this rider.
* The 2015 differential form (Skiba et al.) is not built.

**Profile.** `wPrimeKj` is a new key of the profile contract (schema, patch, fixtures, TypeScript, Kotlin, `PATCH /v1/profile`, `get_profile` and
`update_profile`), always present on the wire, null by default. Profiles stored before it read as null. A client built before this change
that decodes the profile strictly (the Kotlin `ProfileApiJson` does) refuses the new key: the tablet's profile sync must be built against
this `core`.

### 8. The API

All under `/v1`, bearer-authenticated, `Cache-Control: no-store`. The shapes are the schemas in `contracts/` (`metrics-response`,
`ride-metrics-response`, `rides-response`, `ride-detail-response`, `ftp-suggestion-dismiss-*`), read by TypeScript validators with the rules a
schema cannot state and by Kotlin's `MetricsApiJson`, against the same fixtures. They follow the data the screens need
(design/companion.md §11); every answer carries `generatedAt` and `stale`.

* `GET /v1/metrics?from&to`: at most **366 daily points** and, by whole ISO weeks, at most 54 weeks (53 unless the range starts on a Sunday
  and ends on a Monday); `invalid_range` otherwise. Compact: nothing per ride except the best efforts and drift points.
* `GET /v1/rides?before&limit` by **cursor** (not offset: stable while rides arrive): `nextBefore` is the start of the page's last item.
  A ride is named after the workout it was played from (the copy kept when it was stored) or `Free ride`.
* `GET /v1/rides/{id}[?series=true]` reads the whole record, the only endpoint that does: summary, laps with the plan's target (the step's
  fraction × the ride's FTP, whole watts, for a steady step), seconds in zones by the profile's bounds, the plan as stored with
  `stepsRidden`/`stepsPlanned` (a step is ridden when the ride stayed in it for its planned time), `effortTarget` and `effortRange` (the **effort
  steps** are the steady steps at 90% of FTP or more, else the steady steps at the workout's highest target; the range is the lowest and
  highest average power held in them), the series at 10 s (or `ceil(duration/600)` s for a long ride: at most 600 points) with `wBal`.
* `GET /v1/rides/{id}/metrics[?wbal&series&cp&wPrimeKj]`: the ride's curve, load, drift and the W′ balance on request.
* `POST /v1/ftp-suggestion/dismiss`: above. A malformed query parameter is the new error code `invalid_query` (400); a malformed body
  `invalid_json`; an unknown ride `ride_not_found`; an unknown suggestion `not_found`.

Not in this ADR: thresholds and maximum heart rate in the profile, recovery and wellness endpoints (next stage), insights.

### 9. MCP

`get_fitness` (read): load, fitness, fatigue, form (today, with the change of fitness over 7 and 28 days where the range allows), the
weekly loads, the best efforts at 5 s, 1, 5, 20 and 60 minutes in the range and ever, the drift of steady rides, and the FTP with its
suggestion and basis. The daily series, the full curve and the weekly estimates only with `series: true`. Its description says what each
number means and that **the eFTP is only a suggestion that must not be written to the profile unasked**. `get_ride` gains `powerCurve`,
`aerobicDrift` and `wPrimeBalance`. Errors are plain words (`to 2026-11-01 is before from 2026-11-18`). Every number comes from the
service.

### 10. Storage, backfill, performance

`ride_metrics` holds one row per ride: `version` (of the formulas), `record_sha256` (the record it was computed from), the start, load, curve
(`jsonb`, 25 numbers at most) and drift. A row is trusted only when both match the stored ride, so it never serves stale numbers; a ride without
one (stored before this migration, or whose FIT has not arrived yet, or after a change of formulas, `RIDE_METRICS_VERSION`) is computed **on the
next read** (`ensureComputed`, one run shared by concurrent requests, rides read one at a time) and at start; that is the backfill, idempotent,
with no separate job. A ride that cannot be read is logged and skipped, not retried in a loop.

`GET /v1/metrics` reads the small rows only: 400 synthetic rides (a year) answer in about 60 ms on the in-process database of the tests
(`metrics-service.test.ts` asserts under a second) and the pure computation of 400 hour-long rides takes well under a second. Nothing
parses a full record except a ride's detail, `get_ride` and the first computation of a ride.

## Consequences

* The companion can be built against stable schemas: every field it needs is named in `contracts/`.
* Stage P3-2 plugs in with `registerLoadSource` and `registerActivitySource` and adds its own tables (migration 0007) without touching this code.
* The tablet's profile decoding must come from the `core` that knows `wPrimeKj`.
* Suggestions are cheap to ignore and to dismiss, and cannot move the zones; the cost is that a rider who never opens the companion never
  sees one.

## What only real data can settle

* Whether 0.95 × best 20 minutes (and 1.00 × best 60) is near this rider's FTP, and whether two comparable efforts in six weeks is the right
  bar: too strict gives no suggestions, too lax gives noise. The constants are single values in `ftp.ts`.
* Whether the default W′ of 20 kJ and CP = FTP give a plausible balance on the owner's hard intervals (a balance that goes far below 0 says the
  inputs are too low). Skiba's constants (546, 0.01, 316) are the published ones for his cohort.
* Whether 40 minutes, variability index 1.05 and 95% coverage select the rides the rider would call steady on indoor trainer data, and how
  heart-rate dropouts of the real strap look.
* Whether the 42/7-day constants describe this rider's recovery; they are conventions.
* How the Click's auto-pauses and ERG ramps show in the zero-filled power curve at the shortest durations.
