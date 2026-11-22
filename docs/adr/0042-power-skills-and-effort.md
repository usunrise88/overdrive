# ADR 0042: Power skills and weekly effort

Status: accepted (release 0.4.1, the data for two companion charts, 2026-11-22).

## Context

The owner wants two charts in the phone companion (`design/companion.md` §4.14, §4.15, §6.11, §6.12, §7.8): on the Power tab an analogue
of Strava's Power Skills, a radar of the best power at 12 durations (15 s to 60 min) over the last 8 weeks, all time or a custom range, with
a level for each of five groups of durations; on the Fitness tab a weekly **Effort** chart (our own name and numbers) with the rider's usual
range. The screens are a later stage. This one builds what they read: two contracts, the service, the MCP tools and the Kotlin models and
cached resources. The rules of `CLAUDE.md` hold: the service computes and the phone draws; tablet rides are the only source of power;
Strava is never read; the repository is public, so every figure in a test is invented.

## Decision

### 1. Layers

| Where | What |
|---|---|
| `contracts/` | `power-skills-response.schema.json`, `effort-response.schema.json` (fixtures under `fixtures/power-skills/response` and `fixtures/effort/response`), `src/power-skills.ts`, `src/effort.ts` (types, validators and the rules a schema cannot state); the profile gains `powerProfileTable` (schema, patch, fixtures) |
| `server/src/power-skills/` | `levels.ts` (the level scale: one documented constants file), `compute.ts` (pure), `service.ts`, `routes.ts` |
| `server/src/effort/` | `compute.ts` (pure: weeks, usual range, verdict), `service.ts`, `routes.ts` |
| `server/src/mcp/tools/` | `power-skills.ts` (`get_power_skills`), `effort.ts` (`get_effort`); `update_profile` and `get_profile` carry `powerProfileTable` |
| `core/powerskills`, `core/effort` | strict models and `PowerSkillsApiJson`, `EffortApiJson` |
| `core/remote` | `PowerSkillsResources`, `PowerSkillsWindow`, `effortResource` (`ChartsRepositories.kt`) |
| `android/app-companion` | `CompanionContainer`: `powerSkillsFor`, `powerSkillsWindow`, `effort`, refresh targets and Sync now |

No migration. Everything is derived from what is stored, with one addition to a stored value (below).

### 2. Power skills: the data rules

* **Tablet rides only** (spec §11.8): the curve is the stored `ride_metrics.power_curve` of the rides; the watch's workouts and the outdoor rides
  imported from FIT files never feed it. A ride's best at a duration is its mean-maximal power (ADR 0022: a pause or a missing reading counts as 0 W).
* **Twelve durations**: 15 s, 30 s, 1, 2, 3, 5, 10, 15, 20, 30, 45 and 60 min. Eleven are among the 25 of the metrics contract; **45 min is not**
  (the contract has 40 and 60). It is kept beside the 25 in the same stored curve under its own key (`EXTRA_CURVE_DURATIONS`), which
  `curveFromJson` leaves out, so no answer that lists the 25 changes. `RIDE_METRICS_VERSION` is 2: rides stored with version 1 are recomputed on
  the next read (the existing mechanism).
* **Windows**: `from` and `to` are the rider's local days, both included; the default is the last 56 days up to today; at most 1096 days (three
  years); `from` not after `to`. A bad parameter is `400 invalid_range` naming the parameter (the MCP tool says it in words). **Window** is the best
  among the rides of the window, **allTime** the best among all rides up to the end of the window (so a custom range in the past compares with what
  the rider could do then). A tie goes to the later ride. `window.rideCount` is the rides in the window that have a power curve. "All time" in the
  phone's picker is the longest window, three years up to today, so both outlines are the same set of rides.
* **Each effort** carries `watts`, the ride (`rideId`, `rideName`: the name of the workout it was played from, or "Free ride", or "Planned workout"
  when the snapshot is gone) and its local day, `wattsPerKg` and `score`.
* **Weight**: the 7-day average of Health Connect (§12.5, `riderWeight` of the profile, `{ kg, date, days }`), today's, not the weight of the day of the
  ride. Without it there are no `wattsPerKg`, no `score` and no levels (`null`), and the answer says so in `levelsNote`.

### 3. The level scale and its limits

Follows the design (`design/companion.md` §6.11), which changed the brief in these points: a **score** per duration, a skill's level as the **mean of
its scores rounded down**, the design's nine words, a **women's table** chosen in the profile.

* At each duration the table has **B**, the floor of level 1, and **T**, the floor of level 9; levels 2 to 8 are equal steps. The rider's W/kg are
  placed on it as `score = 1 + 8 (W/kg - B) / (T - B)`, two decimals. **The service holds the score between 0 and 9** (the design lets it run beyond
  both and clamps only the radius): a skill's level is the mean of its scores, and one huge 15 s effort (a score of 20) would otherwise lift a whole
  skill; the radius the phone draws is the same. A skill's level is `floor(mean)` held between 1 and 9; its words are Starting, Casual, Regular, Fit,
  Strong, Very strong, Racer, Elite, World class. The level is `null` without weight or without an effort at any duration of the skill.
* **Men**: the design's B and T per duration, as given. They are the log-log interpolation of four anchors (5 s, 1 min, 5 min, 60 min) of the published
  Allen and Coggan power profile, rounded; the interpolation reproduces the design's 10 and 30 minute columns to 0.04 W/kg.
* **Women**: built the same way from the women's anchors. **How they were taken and checked**: the book (*Training and Racing with a Power Meter*) was
  not at hand, so the anchors come from two independent web transcriptions of its women's chart, the TrainingPeaks chart read into a table by
  triathlonregimen.com (floors of "Novice 2" 8.63, 4.75, 1.97 and 1.58 W/kg at 5 s, 1, 5 and 60 min; "World class" from 17.88, 8.64, 6.06 and 5.10) and
  the "simplified" table of the 2nd edition on cyclistshub.com ("World class" above 17.7, 8.6, 6 and 5), cross-checked cell by cell (they agree to 0.3
  W/kg or better) and against the top figures Coggan is quoted giving for women (about 19, 9, 6.5 and 5.5). B is the lowest category boundary and T the
  top of the chart, one category step above the floor of "World class": 19.2, 9.19, 6.64 and 5.61. **The same procedure applied to the men's chart lands
  within a few percent of the design's men's anchors**, which is the check that it is the design's method. What this does not establish: both
  sources read an image. The anchors and the interpolation are in `levels.ts` and a test recomputes the table from the anchors.
* **Limits, said in the code and the answers**: the tables are for young adults and are applied as they are whatever the age; they survey trained
  riders; indoor power differs from outdoor; the weight is a 7-day average; the eight in-between durations are interpolated, not published. "Level 4
  of 9" is a rough guide to how the rider's own numbers move, not a verdict. Tests check that B and T fall with the duration (B does not rise: the design's
  men's floor is flat at 2.1 W/kg from 15 to 20 min) and that the women's table is its anchors.
* **`powerProfileTable`** (`men` default, `women`) is a new optional, writable field of the profile (schema, patch, fixtures, TypeScript, Kotlin,
  `PATCH /v1/profile`, `get_profile`, `update_profile`), written to the wire only when the owner has set it so that a tablet built before it still
  decodes the profile. `null` goes back to the default. The answer's `table` says which was used.

### 4. Effort: the data rules

* **Effort is the training load the service already computes**, not a second formula (ADR 0022, ADR 0023): a ride's stored power TSS, a watch
  workout's or an outdoor ride's Banister TRIMP from heart rate, on one scale (100 is an hour at threshold). Every kind feeds it, a session that
  overlaps a tablet ride is not counted twice (`countsTowardLoad`). `GET /v1/effort` reads the loads, it works out none.
* **Weeks** are Monday to Sunday in `TIMEZONE`, a day is the local day of the start (a ride at 23:30 UTC may be the next day's), the week is built
  with calendar arithmetic, never by adding 24-hour days: tests run the clock changes of 28 March 2027 and 25 October 2026 and a new year in
  `Europe/Belgrade`. Every sum is of the **rounded** figures below it (activity to day to week), so what the phone adds up is what the service says.
* **Shape** (the design's names, `design/companion.md` §11): `weeks` oldest first, the current one last; each `weekStart`, `load`, `approximate`
  (any of it from heart rate), `current`, `usualLow`, `usualHigh` (null while learning), `weeksLogged`, `verdict`, `days` (always 7, the days after
  today 0) and `activities` (`id`, `kind` `ride` or `activity`, `sport` (null for a ride; `cycling-outdoor` for an imported ride), `name`, `date`,
  `load` (null when it could not be worked out), `approximate`, `share` of the week); top-level `weeksNeeded` (8) and `weeksLogged` (the current week's).
  `weeks` is 1 to 52, default 12; a bad value is `400 invalid_query` naming `weeks`. The companion asks for 52 once: the card draws the last 12, Values all.
* **`approximate`** is true for a load from heart rate (the design: "any part from heart rate"; the rides list already says the same of every
  activity). An activity with no heart rate, or no limits to scale it by, has `load` null: it is listed, adds nothing and has no share.
* **Usual range** (the design's rule, replacing the brief's quartiles): the mean minus and plus one **sample standard deviation** of the loads of
  the **8 complete weeks before that week**, the low edge held at 0, to one decimal, the way the wellness usual range is made. The weeks count from the
  rider's **first week with any activity**, so the weeks before the rider began are not zeros that drag the range down while a week off after it counts
  as the 0 it was. A week with fewer than 8 such weeks before it is **learning**: no range, `weeksLogged` (0 to 8) says how many there are.
* **Verdict** per week: `below` under `usualLow`, `above` over `usualHigh`, `within` between them (edges included), `learning` without a range,
  `none` for the current week when nothing at all is recorded in it. **For the week in progress the load so far is compared with the whole range**, not
  scaled to the weekday (the brief proposed a scaled comparison and "unknown before Wednesday"; the design is simpler and honest): `above` and `within`
  can only stay or rise, `below` means "below so far", and the phone words it. A past week with nothing in it is `below`, not `none`.
* **Differences from the brief, all from the design**: `usual { low, high, median, basedOnWeeks }`, `thisWeek { verdict, verdictText }` and `note`
  are gone (the phone owns the words; each week has its own range); `kind` is `ride | activity` with `sport`, not `ride | watch | outdoor`; the power skills
  has `rideName` and `score` per duration, a `table` and a `level { index, name }` without `bestWattsPerKg`, `nextLevelWattsPerKg` or `atSeconds`.
  `levelsNote` and `outdoorNote` stay for readers of the answer with no words of their own (the MCP agent); the companion writes its own. `stale` is
  not sent: no input source of either answer can fail separately.
* An error for a bad parameter is `invalid_range` (dates) or `invalid_query` (`weeks`), the codes the metrics endpoints already use; the brief said
  `invalid_json`, which the service keeps for bodies.

### 5. What the phone does not compute

Nothing: not a watts-per-kilogram figure, not a score or a level, not a week's sum, a usual range, a share or a verdict. It reads them and words them
(the level words are in the contract only so that a model rejects a wrong one). Kotlin's strict models check the rules the schemas cannot state, so an
answer that contradicts itself is refused as an answer that is not the contract's.

### 6. What the UI stage calls

* `container.powerSkillsFor(window: PowerSkillsWindow): RemoteResource<PowerSkillsResponse>`; `PowerSkillsWindow.EightWeeks`, `.AllTime` and
  `.Custom(from, to)` (real local days, at most three years; its cache is read when first asked for). `container.powerSkillsWindow` and
  `setPowerSkillsWindow` keep the choice while the app lives. `container.screenOpened(RefreshTarget.PowerSkills(window))` and `refresh(...)`; the
  Power tab's `RefreshTarget.Power` also refreshes the chosen window.
* `container.effort: RemoteResource<EffortResponse>`; `RefreshTarget.Effort`, and `RefreshTarget.Fitness` refreshes it too.
* Sync now asks the effort, the eight weeks, all time and a custom window when one is chosen.
* The models: `PowerSkillsResponse.duration(seconds)`, `.skill(PowerSkillId)`; `EffortResponse.currentWeek`, `EffortWeek.usual`.

## Tests

`contracts`: 10 + 79 power-skills and 15 + 67 effort fixtures (valid and invalid) and the profile's. `server`: the scale (every boundary, the clamps, both
tables and the interpolation of the women's), the skills by hand, the weeks and the usual range by hand, the clock changes, the 45-minute figure of an old
row recomputed, the parameters, auth and the MCP tools. `core`: the contract tests, the repositories, the profile's key. `android`: the container over the
loopback service.

## What only real data can settle

The women's anchors (read from images by third parties); whether 8 complete weeks is the right length of a rider's usual (a rider with a long season
has a wide range); whether the level words feel right; how the 45-minute figure behaves on rides that include a pause.
