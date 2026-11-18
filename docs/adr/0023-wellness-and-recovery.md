# ADR 0023: Wellness, recovery and the watch's other activities

Status: accepted (Phase 3, stage P3-2, server side, 2026-11-18).

## Context

Spec §6.2 puts wellness input behind one `WellnessSource` interface; §6.4 asks for a personal baseline per metric, a daily score
of how far each metric is from it, and one of ready, caution or rest from rules that are explicit and inspectable, with no
opaque score; §6.7 asks for the workouts recorded on the watch (VR sessions and the like) to count toward fatigue and recovery
but not toward the power curve, FTP or aerobic drift; §12.5 (added 2026-11-18) adds body weight from Health Connect. The
companion app (`design/companion.md` §4.1, §4.5, §7, §11) reads all of it. The rules of `CLAUDE.md` hold: the service computes
and a model only writes text from what was computed (this stage calls no model at all); nothing is read from Strava; health data
leaves the server only to endpoints the owner chose.

Written without real data: no real Health Connect batch, no real intervals.icu wellness and no recording of a watch was available.
Every number below is a first guess made explicit so that it can be tuned in one place, and every rule is checked against
synthetic 60-day histories with planted deviations and against figures worked out by hand. The last section lists what only real
data can settle.

## Decision

### 1. Shape

New module `server/src/wellness/`, the same layering as the plan, profile and metrics modules:

| File | Role |
|---|---|
| `metrics.ts` | The table of metrics: label, unit, adverse direction, smallest believable spread, plausible range |
| `baseline.ts`, `series.ts`, `recovery.ts`, `trimp.ts` | Pure functions: baselines and streaks, the daily answer, the rule table, the heart-rate load |
| `records.ts`, `derive.ts` | The raw Health Connect records in their compact stored form, and the daily figures worked out from them |
| `sources.ts` | The `WellnessSource` interface, `PushWellnessSource` (the Health Connect upload), `PullWellnessSource` (`IntervalsWellnessSource`), `WELLNESS_SOURCE_ORDER` |
| `store.ts`, `activities.ts`, `heart-rate-index.ts` | PostgreSQL: records, daily figures, sync state, the watch's activities, the rides' heart-rate index (migration 0007) |
| `service.ts`, `routes.ts` | `services.wellness` and the HTTP endpoints; the hooks into the metrics service |
| `puller.ts`, `forward.ts` | The pull from intervals.icu every N hours and on demand; the forwarding of the watch's activities |
| `src/mcp/tools/wellness.ts`, `activities.ts` | `get_wellness`, `get_recovery`, `list_activities`, `get_activity` |

Shared files touched, additively: `services.ts` (the `wellness` member), `app.ts` (one route registration), `events.ts` (the
`activity-stored` event), `config.ts` (`config-wellness.ts`), `profile/store.ts` (the heart-rate fields and the estimates), the
intervals.icu client (`listWellness`, `createManualActivity`), the MCP server's list of tools, `mcp/tools/profile.ts`.

### 2. The upload (`POST /v1/wellness`)

A batch `{ source: "healthconnect", device?, records }` of typed records that mirror Health Connect: `restingHeartRate`,
`heartRateSamples`, `sleepSession` (with stages when the watch writes them), `respiratoryRate`, `oxygenSaturation`, `steps`,
`exerciseSession` (with the heart-rate samples inside it), `heartRateVariabilityRmssd` (optional) and `weight`. The discriminator is
`kind`, because `exerciseSession` has a `type` of its own (the exercise type as a lower-case token). Times are UTC instants; the
zone offset is accepted and kept in the schema but the service works in its own `TIMEZONE` (section 4).

- **Idempotent by (source, record id)**, with `lastModified`. A record already stored is a *duplicate* when its content is the same
  or its `lastModified` is not newer; a changed record with a newer `lastModified` (or without any) replaces the stored one; an id
  that is already another kind of record is *rejected*, and so is a record that ends more than a day after the service's clock.
  The answer is counts only: `{ accepted, duplicates, rejected, byType, generatedAt }`.
- **Limits**: at most 2000 records and 4 MiB per request (`413 payload_too_large`).
- **The whole batch is checked first.** One bad record refuses the request (`422 invalid_record`) and nothing is stored; the message
  names the record's index and the field (`/records/1/bpm must be <= 300`), never the value. The ranges are Health Connect's own, so
  a record Health Connect accepted is accepted here; implausible readings inside them are ignored when the daily figures are
  worked out (`metrics.ts`), not refused: one glitch must not stop a phone from ever syncing.
- **Compact storage.** `wellness_records.payload` is jsonb with instants as epoch milliseconds and heart-rate samples as
  `[offset ms, bpm]` pairs (about a quarter of the size). Raw records are kept, the daily rows are derived from them, so a record that
  arrives late or is edited just makes the affected dates be worked out again.

### 3. Sources and their preference

`WellnessSource` has two kinds: a source that **pushes** (the companion's Health Connect upload, `HealthConnectUpload`, section 2)
and one that is **pulled** (`IntervalsWellnessSource`: `GET /api/v1/athlete/{id}/wellness?oldest&newest` through the existing
client, fields `restingHR`, `hrv` (taken as rMSSD, as intervals.icu documents it), `hrvSDNN`, `sleepSecs`, `spO2`, `respiration`,
`steps`, `avgSleepingHR`, `weight`; reading the owner's own wellness is the purpose of the key). The daily figure of each metric is
kept **per source** in `wellness_daily (day, metric, source, value)` and the one that is used is the first source in the metric's
order that has a value, so the value and where it came from are both known (`source` in every reading).

`WELLNESS_SOURCE_ORDER` is `healthconnect,intervals` by default; `…;restingHr=intervals,healthconnect;sleepSeconds=healthconnect`
gives a metric an order of its own, and a source left out of a metric's list is not used for it. The pull covers the last 90 days,
runs at start and every `WELLNESS_PULL_HOURS` (6), and on demand (`?refresh=true` on the GETs, `refresh` in the MCP tools, waiting at
most ten seconds); it is off without an intervals.icu key, like the plan refresher it is modelled on. A failed pull is recorded
(`wellness_sync_state`) and the answers then say `stale: true`; the figures keep what they had.

### 4. Daily figures

A night belongs to the **local date (`TIMEZONE`) of the morning it ends**.

| Figure | Rule |
|---|---|
| Night | The sleep sessions that end on the date and start between 18:00 the evening before and noon that day (a later one is a nap and is left out). Sleep is the sum of their asleep time: stages `sleeping`, `light`, `deep`, `rem` and `unknown` count, `awake`, `awake_in_bed` and `out_of_bed` do not; without stages the whole session counts. Under ten minutes is not a night |
| Bedtime, waking | The earliest start and the latest end of the night, as **minutes from local midnight**, a bedtime before midnight negative (23:05 is -55): a range of bedtimes is then a range, not two clusters either side of midnight. `sleepStart` and `sleepEnd` are the instants |
| Resting heart rate | The latest `restingHeartRate` record of the local date |
| Respiratory rate, oxygen saturation, rMSSD | The mean of the plausible records inside the night when there is one, else of those of the local date |
| Heart rate in sleep | The mean of the plausible heart-rate samples inside the night, **only when they are dense enough**: at least 20 samples and at least one every 20 minutes on average over the night. Otherwise none: a mean of a handful of samples is a guess |
| Steps | The sum of the records that start on the local date |
| Weight (§12.5) | The mean of the plausible weighings (20 to 400 kg) of the local date; `weightKg7d` is the mean of the daily weights of the 7 days up to and including the day, days without a weighing left out. **It changes nothing**: the tablet's system mass and the profile's physics stay as they are (that is a later stage) |

Plausible ranges (`metrics.ts`): resting heart rate 25 to 200 bpm, sleep 10 minutes to 18 h, respiratory rate 4 to 40 /min, oxygen
saturation 70 to 100 %, heart rate in sleep 25 to 180 bpm, rMSSD 3 to 300 ms.

### 5. Baselines (spec §6.4)

For a metric, the baseline in force on a day is the **mean and the sample standard deviation (n - 1) of its daily values over the 42
days before that day** (the day's own value is not part of what it is compared with). 42 is inside the spec's 28 to 60 and equals
the fitness time constant; it is `WELLNESS_BASELINE_DAYS` (28 to 60). The usual range is the mean minus and plus **one spread**. A
baseline exists only with at least `nightsNeeded` (14, `WELLNESS_NIGHTS_NEEDED`) readings behind it. Mean and standard deviation
are the spec's "mean and spread" and the plainest choice; the robust alternative (median and MAD) is not used because the brief asked
for the simple one and a few outlying nights in 42 days barely move it.

- **The spread has a floor** per metric, the smallest believable one, in the metric's own unit: resting heart rate 1.5 bpm, heart rate
  in sleep 1.5 bpm, sleep 20 minutes, respiratory rate 0.3 /min, oxygen saturation 0.5 %, rMSSD 0.08 on the log scale (8%), wake-up
  time 30 minutes, **sleep timing 60 minutes**. Without it a rider whose resting heart rate never moves would be "outside the usual
  range" by one beat; with it a bedtime counts as shifted only beyond an hour, which is the spec's "sleep timing shift beyond N
  minutes" with N = 60, expressed in the same machinery as the others.
- **rMSSD is compared on the natural-log scale** (heart-rate variability is log-normal; Plews et al. 2013), its range is the
  exponential of the log range.
- `deviation` is `above` / `below` when the value is more than one spread outside the mean, `inside` otherwise; `streak` is the
  days in a row up to and including the day outside the range **on the same side** (a day without a reading, or a flip of side, ends
  it); it looks back 60 days before the first day asked for.
- `nightsLogged` is the days with at least one reading of a scored metric in the 42 days **before** the morning rated: the nights the
  baseline stands on. A verdict needs `nightsLogged >= nightsNeeded`, so the first verdict is on the 15th morning (14 nights of
  history and the morning rated).

### 6. The recovery state (spec §6.4)

Each scored metric gets an **adverse score**: how many spreads its reading is from its mean, counted positive in the direction that
matters:

| Metric | Adverse when | Phrase |
|---|---|---|
| Resting heart rate | above | "Resting heart rate is above your usual range" |
| Sleep | below (shorter) | "Sleep was shorter than usual" |
| Sleep timing (bedtime) | later | "Bedtime was later than usual" |
| Respiratory rate | above | "Respiratory rate is above your usual range" |
| Oxygen saturation | below | "Oxygen saturation is below your usual range" |
| Heart rate in sleep | above | "Heart rate in sleep is above your usual range" |
| Heart-rate variability (7-day mean of ln rMSSD) | below | "Heart rate variability is below your usual range" |

Waking time, steps, rMSSD's SDNN and weight are shown, not scored. A score above 1 means outside the usual range in the adverse
direction. **The rule table, first match wins** (`recovery.ts`; every row has a test):

| Rule | Condition | State |
|---|---|---|
| R1 | three or more metrics with a score above **1.5** | rest |
| R2 | a metric (not heart-rate variability) with a score above **3** this morning and the morning before | rest |
| C1 | two or more metrics with a score above **1** | caution |
| C2 | one metric with a score above **2** | caution |
| C3 | one metric with a score above 1 on this morning and the morning before (a run of two or more) | caution |
| OK | none of the above | ready |

Also `learning` (fewer than `nightsNeeded` nights logged, or no metric has a baseline of its own yet: no verdict, the readings are
shown without a range) and `noData` (no reading in the last two days). The state is for today, or for yesterday while this
morning's data has not arrived (`date` says which, the app says "yesterday"); nothing in the last two days is `noData`.

- **Why these numbers.** They are the brief's example, made explicit: caution on two signals beyond 1 spread or one beyond 2, rest on
  three beyond 1.5 or any beyond 3 for two mornings. C3 is the design's own example ("resting heart rate is above your usual range for
  the second morning"). R1 asks for three metrics at 1.5 because one metric alone misleads (a hot night, a late dinner); R2 lets a
  single metric decide rest only when it is extreme *and* persistent. A score above 1 is not rare on its own (about one morning in
  six per metric by chance on a normal distribution), which is why caution needs two metrics, or a metric far out, or a run. They are
  first guesses; the last section says how real data will tune them.
- **Heart-rate variability refines, never decides alone toward rest, and is never required.** Where a source writes rMSSD, the
  metric is the 7-day geometric mean (the exponential of the mean of ln rMSSD over at least four of the last seven days, on a day that
  has a reading), compared with the baseline of that same 7-day mean (Plews et al. 2013 use the rolling 7-day mean of ln rMSSD
  against the individual's own range; the thresholds here are ours, not theirs). It counts in C1, C2, C3 and R1 like any metric; it
  cannot fire R2 alone. Without a source the table has one metric fewer.
- **`reason`** is one plain sentence written from the rule by a template, never by a model ("Resting heart rate is above your usual
  range for the second morning."; "Three signals are well outside your usual range: sleep, resting heart rate and respiratory
  rate."). It names signals and directions only, never a cause. A ready morning with one metric a little outside says so ("Bedtime was
  later than usual, but nothing else is.") instead of claiming everything is in range. `guidance` is one short sentence of training
  advice ("Train as planned.", "Train, but keep the hard parts short.", "Keep today easy or take it off.").
- **`facts`** are three or four metrics in the order that decided: the ones that fired the rule first (strongest first), then those
  outside their range, then those inside, then those with a reading and no range yet, then those with a usual range and no reading
  this morning (shown as "no reading"). The design's example of rest with two signals ("Two signals are outside your usual range")
  is caution here by the brief's rule; the designer's reasons are examples, as the design says.

### 7. Other activities from the watch (spec §6.7)

Each `exerciseSession` becomes a row of `activities` (`watch-<record id>`): type, title, start and end, the heart-rate samples inside
the session (the record's own, or, when the phone attached none, those of the heart-rate records that overlap it, which also fills the
activity in when they arrive later), and **how dense the samples are**: how many, the median and the longest gap in seconds and the
share of the session within five minutes of a sample (`heartRate` in the answer): spec §6.7's "to check", visible per session so the
owner sees how dense OHealth's data really is.

**Load**: Banister's exponential TRIMP per sample interval (Banister 1991), normalised so that **one hour at threshold heart rate is
100**, the scale of the power-based score. With the reserve fraction `x = (HR - rest) / (max - rest)` clamped to 0..1 and
`y(x) = x * exp(b * x)`, an interval of `dt` hours adds `dt * y(x)` and the load is `100 * sum / y(x_threshold)`. The constant 0.64 of
Banister's formula cancels. `b` is Banister's coefficient for men, **1.92** (women 1.67): the profile has no sex, and dividing by the
threshold's own value absorbs most of the difference (under 10% in the weight of an easy hour against a threshold hour), so one
fixed value is used and the figure stays approximate. A sample holds until the next one, at most 5 minutes; the last holds the median
gap (or 5 minutes) and no further than the end of the session; a gap in the data is therefore not counted as heart rate.

- **Heart rates in force**: `maxHeartRate` and `thresholdHeartRate` are new profile fields (bpm, or null; PATCH, `get_profile`,
  `update_profile`); where the owner has set none the estimates from the rides stand in. `estimatedMaxHeartRate` is the highest heart
  rate in any stored ride (a strap glitch above 230 is ignored); `estimatedThresholdHeartRate` is **95% of the best 20-minute average
  heart rate** in any stored ride, over a window of 20 minutes in a row with at least 90% of the seconds measured and not paused (the
  20-minute test's rule of thumb, as FTP is 95% of the best 20-minute power); both are null when the rides do not say or the value is
  outside what the profile holds. They are computed once per ride (`ride_heart_rate`) and read-only.
- **Resting heart rate**: the mean of the readings of the 42 days before the activity when there are at least 7; else the mean of all
  readings when there are at least 7 (an estimate); else 60 bpm (an estimate).
- **No thresholds known**: the load is `null`. **`loadApproximate: true`** where an estimate stood in for a maximum or threshold, the
  resting heart rate was a fallback, or the samples cover under 80% of the session. `loadNote` says in words that the figure is
  approximate and that **heart rate lags in short intermittent efforts, so a HIIT-style session is under-counted**. In the rides list
  of the companion every activity is `loadApproximate: true`, as that contract says.
- **The same ride twice**: a session that overlaps a ride stored from the tablet by half of its length or more is the same ride seen by
  the watch (the owner wears the watch on the bike). It has `countsTowardLoad: false` and `overlapsRideId`, its load is `null`, it is
  not in the rides list, and it is not forwarded; otherwise the ride would count twice.
- **Where it counts**: the load is registered with the metrics service as its `other` load source and so feeds fatigue, form and
  recovery; the activities are a source of `GET /v1/rides` (`kind: "activity"`). They are **never** in the power curve, FTP estimation
  or aerobic drift, which read rides only (`GET /v1/metrics` has `loadOther` and `otherLoad` for them).
- **Forwarding**: each session is also posted to intervals.icu as an activity of the matching sport (`biking` Ride,
  `biking_stationary` VirtualRide, running Run, walking Walk, hiking Hike, swimming Swim, rowing Rowing, yoga Yoga, strength
  training WeightTraining, anything else Workout) with `POST activities/manual` (start in the athlete's wall clock, type, name,
  duration, average and maximum heart rate, our id as `external_id`), through the same client, queued in `activity_destinations`
  like rides, off without a key. It is idempotent here: before posting, the worker looks for an activity with that external id or
  one that started within a minute and counts it as done; a queue row is posted once; a POST is not repeated after a network error
  (it could make two). A session older than 7 days when it is received is skipped (the first sync of a phone must not post weeks of
  history); one that overlaps a ride is skipped. **Nothing goes to Strava.** The endpoint and its fields are from the public API
  documentation and could not be tried without an account; a refusal's message keeps the status and not the body, which may quote
  heart rates.

### 8. Endpoints and tools

`POST /v1/wellness`; `GET /v1/wellness?from&to` (at most 186 days: per day the figures and, per metric, `value`, `source`,
`baselineLow`, `baselineHigh`, `deviation`, `streak`; the usual bedtime and wake-up ranges; `nightsLogged`, `nightsNeeded`,
`generatedAt`, `stale`); `GET /v1/recovery`; `GET /v1/activities/{id}` (summary, density, the heart rate averaged into at most 300
points). MCP: `get_wellness` (compact by default, `detail: "full"` for every reading), `get_recovery` (adds what the rule means),
`list_activities` and `get_activity` (rides and the watch's workouts with a `type`: `cycling` for a ride, the watch's name for a
workout, and a `source`); `list_rides` and `get_ride` stay as deprecated aliases for one release. Contracts: `wellness-upload`,
`wellness-upload-response`, `wellness-response`, `recovery-response` and `activity` schemas, Kotlin `WellnessApiJson`, and the four
heart-rate keys of the profile (**optional on read** so that a profile cached by an older app stays valid; the service always
writes all four).

### 9. Privacy: health data is private

Never in a log line, never in an error message: only counts, ids, device names and dates. The upload route parses its own body (the
default JSON parser's message quotes a snippet of it); a validation error names an index and a field path, never a value or a key
that is not an identifier; a database error is replaced by its code (PostgreSQL's quotes the failing row); the intervals.icu client
keeps the status and not the body of a refusal of an activity; the pull logs a count of days. A privacy test sends distinctive figures
through every door (the upload, a refused batch, a body that is not JSON, a database that fails and quotes the row, an intervals.icu
that refuses and quotes what was sent) and searches the HTTP log, the service log and every answer for them. The data leaves the server
only to the endpoints the owner chose: the MCP clients they approved and intervals.icu with their own key; the heart rates sent to
intervals.icu are average and maximum only.

## What is not claimed

No diagnosis and no cause: the sentences name signals and directions. The state is a training signal from the rider's own
history, not a medical measurement; it is not validated against anything. The heart-rate load is approximate (no sex, no individual
coefficient, heart rate lags in intermittent efforts, thresholds may be estimates). Weight changes nothing.

## What only real data can settle

- **The thresholds** (1, 1.5, 2, 3 spreads, a run of two mornings, 14 nights, 42 days, the floors): how often caution and rest come
  on real data and whether they match how the owner feels. If caution is too frequent the first knobs are C3 (three mornings) and the
  floors.
- **OHealth's real records**: whether it writes `RestingHeartRateRecord`, `RespiratoryRateRecord`, `OxygenSaturationRecord` and sleep
  stages at all, how dense the heart-rate samples are in a session and during sleep (the density rule of section 4 and the 80% coverage
  rule), what exercise types it uses for a VR session, and whether it writes a session for a ride the tablet also recorded.
- **intervals.icu**: whether `hrv` is rMSSD, which wellness fields a natively integrated device really fills, whether `avgSleepingHR`
  and `weight` exist under those names, and the `POST activities/manual` endpoint, its fields and how it treats a repeat.
- **The heart-rate load**: the resting heart rate fallback and the estimates of maximum and threshold; the coefficient 1.92.

## Consequences

- The companion app (stage P3-4) uploads the Health Connect batches and draws the Today and Wellness screens from these endpoints.
- The next stage (insights and alerts) reads the same facts; deviation alerts use `streak`.
- The ride app is unchanged. The profile contract grew four optional keys, and core's `Profile` has them with defaults, so the next
  tablet release reads them. **A tablet release older than this change** (its decoder rejects keys it does not know, as the schemas
  do) cannot read the profile the service now serves and keeps its own settings until it is updated, as it already does for
  `wPrimeKj` (stage P3-1).
