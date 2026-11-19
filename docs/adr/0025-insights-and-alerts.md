# ADR 0025: Written insights, alerts and discussion topics

Status: accepted (Phase 3, stage P3-3, server side, 2026-11-18).

## Context

Spec §6.5 asks for notes written in plain language from facts the service computes: a morning note, a note after a ride, a weekly
review, with a conclusion and a recommendation, only supplied numbers, no diagnosis and no medical condition, the same facts exposed
through MCP, and an endpoint the owner chooses. §6.6 asks for notifications: the morning note, alerts for a metric outside its own
baseline for several days, and plan drift, through the Telegram bot and the companion's Today screen. §12.3 asks the post-ride note to
hand a topic to the coach agent for a real reason, at most two a week, closing when discussed or after 14 days. The companion's design
(`design/companion.md` §4.1, §4.2, §11) reads the notes through `GET /v1/insights`. The rules of `CLAUDE.md` hold: **the model writes
text from facts the service computed and never calculates**; Strava is never read; health data leaves the server only to the endpoint
the owner configured; the repository is public, so no secret and no real data in it.

Written **without a language-model endpoint, a Telegram bot, an intervals.icu account or a recording of the owner's body**: the endpoint
is a fake HTTP server written from the public description of the OpenAI chat-completions API, Telegram is the fake of ADR 0019, and
every number in a test is invented. The last section lists what only the real thing settles.

## Decision

### 1. Shape

New module `server/src/insights/`, the layering of the plan, metrics and wellness modules:

| File | Role |
|---|---|
| `facts.ts`, `quotes.ts`, `planned.ts` | The facts builders: pure and deterministic. Every number goes through `Quotes`, which lists it as quotable |
| `gather.ts` | Reads what the builders need from the services (metrics, wellness, plan, plan history, ride store); calculates nothing a note quotes |
| `validator.ts`, `deny.ts` | The check on a text: numbers, dates, times, deny list |
| `llm.ts`, `writer.ts`, `prompts/` | The OpenAI-compatible client; the writer (model, check, one retry, template); the prompts, one file per kind, versioned |
| `template.ts`, `readings.ts` | The deterministic text of each kind; the fixed sentences of the deviation alerts |
| `alerts.ts`, `topics.ts` | The alert thresholds and the topic rule table: pure |
| `store.ts`, `service.ts`, `routes.ts` | PostgreSQL (migration 0008), the triggers and everything above put together, `GET /v1/insights` |
| `src/mcp/tools/insights.ts` | `list_insights`, `get_facts`, `list_discussion_topics`, `mark_discussion_topic` |
| `src/notifications.ts` | What a module hands the Telegram bot: candidates with a (kind, key), without importing it |

Shared files touched, additively: `services.ts` (`insights`, a shared `PlanHistory`), `app.ts` (one route), `events.ts` (`wellness-updated`,
`insight-stored`), `config.ts` (`config-insights.ts`), `telegram/` (a source of messages and monospace spans, see 7),
`wellness/service.ts` (`recoveryDays`, the `wellness-updated` event), `wellness/puller.ts` (an `onStored` hook), the MCP server.

### 2. The facts

A note is written from one structured JSON document per kind, built by a pure function from inputs the services computed (golden-tested
against `server/test/fixtures/insights-facts-*.json`; the same inputs give the same bytes):

- **morning**: the recovery state with its rule, reason, guidance and signals (sleep in hours, bedtime as a time of day, the usual range
  of each), whether the state is this morning's, `adjustment` (`none`, `keep-hard-parts-short` for caution, `easy-or-off` for rest), today's
  planned workouts (length, planned load, intensity factor, hardest step in watts at the FTP in force, the steps in one line, done or not),
  fitness, fatigue, form with its word and the change in fitness over 7 days, the last ride.
- **ride**: the ride (length, power, normalised power, IF, load, heart rate, work), the plan (planned length, planned load, minutes of the
  workout ridden and the share, load against planned in percent, steps compared and how many were within 5% of target, above, below,
  the planned and ridden average power over the steps with a target, each step), the best power at 5 s, 1, 5, 20 and 60 minutes against
  the best of every earlier ride (a **new best** only against an earlier ride, with the number of earlier rides), aerobic drift where
  there is some, W′ (its lowest point as a share of W′: **low** below 30%), form at the end of the day.
- **week**: sessions planned, done, skipped, missed (the day passed, no ride); rides and time; load against the week before up to the same
  weekday; fitness change over the week and form; recovery mornings by state; wellness averages against the week before and the days
  outside the usual range; new bests; what is planned for the next 7 days.
- **deviation**: the metrics that crossed the streak, with their reading, usual range, days in a row and the fixed sentences of
  `readings.ts`. **drift**: the reasons, the missed sessions, the load against plan, the thresholds.

Every number the text may quote is **in the facts** and in `facts.quotable`, a list of `{label, value, unit}`. Numbers are rounded
once, to the precision they are shown with (watts, load, fitness and form in whole points, IF to two decimals, sleep to a tenth of an
hour), so the text and the companion say the same, and a **change** is the difference of the rounded figures, so a reader can check the
subtraction. A test checks that every numeric leaf of every golden facts document is in `quotable`.

### 3. The check (the rule enforced, not trusted)

After a model has written a text, `validator.ts` extracts every number and rejects the text if one is not supplied:

- **Supplied** means: a `quotable` value, compared at the precision the text gives it (the text "73" matches 73.4; "73.4" does not match
  73; "58.4" does not match 58); every digit and every English number word in the facts' own sentences and names (a workout called "Sweet
  spot 3x10" may be called that; the recovery reason's "second morning" is not a number); the dates the facts mention, as an ISO date,
  "18 November", "Nov 18" (a week's note may name any day of the week); the times of day the facts mention, and a duration written
  `h:mm` that is a length in the facts.
- **Allowed without being supplied**: list numbering (`1.`, `2)`), "7 days" and "24 hours", power zones 1 to 7, `VO2`, and the pronoun "one".
- **Everything else is a violation**, including a number the model derived (76% from an intensity factor of 0.76; hours from minutes:
  decimal hours are listed as quotable where a duration is an hour or more, so "1.3 hours" is fine and "1.25" is not).
- Digits are checked in any language; number words only in English, so a note in another language is held to its digits.
- A **deny list** (`deny.ts`, about 60 words and stems, whole words, case-insensitive: diagnos*, infect*, illness, ill, sick*, virus,
  flu, doctor*, medical*, symptom*, cardiac, apnea, overtrain*, ...) is a second rejection. `INSIGHT_DENY_WORDS` adds to it.
- The text must not be empty, not longer than the contract's 1500 characters, and carry no link.

A rejected text is asked for **once more**, with the violations named (the offending numbers, dates and words, and nothing else of
the text), and a second rejection, or any failure of the endpoint, stores the **template's** text. A reasoning model's `<think>` block is
taken off before the check; markdown is taken off rather than rejected. The retry sends the model its own first text back: it goes to the
same endpoint as the facts.

What the check **cannot** see is whether a sentence is *true* or *wise*: "you are well recovered" over a state of rest has no number
in it. That is the prompt's job (the facts carry `adjustment`, the state and the reason, and the prompt makes the state the first thing
said), and the template's, which is code. The owner reads the first notes; `source` and `promptVersion` say who wrote each.

### 4. The language-model client

`llm.ts`: `POST {LLM_BASE_URL}/chat/completions` with `Authorization: Bearer {LLM_API_KEY}` (when set), `model`, `messages`,
`temperature` 0.3, `max_tokens` 800, `stream: false`; plain `fetch`, no SDK. It **retries** a network error, a timeout, 408/429
(honouring `Retry-After` up to 20 s) and 5xx, three requests in all, waiting 1 s then 2 s, and does **not** retry 401/403 or other 4xx.
`LLM_TIMEOUT_SECONDS` (60) bounds each request. It follows **no redirect** (the key and the health data would go with it). A failure is
a kind and a status and nothing else: **no URL, header, prompt or reply is ever logged or put in an error** (a server's error body may
echo either). The model is on only when `LLM_BASE_URL` and `LLM_MODEL` are both set (one without the other: off, the log names the missing
variable); the key is optional. `LLM_BASE_URL` must be https (http for localhost only) with no credentials or query. Health data leaves
the server **only** to this address, and without it nothing leaves at all.

Prompts are files under `insights/prompts/` (TypeScript modules, so the build carries them): `system.ts` (the rules every note is
written under: only supplied numbers, one conclusion and one recommendation, no diagnosis, no cause, short, plain text, second person,
the language, never mention the rules) and one file per kind with its instructions and length. **Each carries a version**, stored with
every note it wrote as `promptVersion` (`morning.v1+rules.v1`); a template note says `template.v1`. `INSIGHTS_LANGUAGE` is put in the
prompt as given (letters, spaces and hyphens only, so it cannot carry an instruction); the template stays English.

### 5. Notes, idempotence and triggers

Table `insights` (migration 0008): `id`, `kind` (`morning`, `ride`, `week`, `deviation`, `drift`), `key`, `created_at`, `text`, `ride_id`,
`source` (`llm` or `template`), `prompt_version`, `facts_hash` (SHA-256 of the canonical facts), and `facts` (what the writer saw, which
`get_facts` returns for an existing note). **`UNIQUE (kind, key)`** makes a note idempotent: the key is the local day for a morning
note, the ride id for a ride note, the Monday for a weekly review and a plan-drift alert, the day and a short hash of the metrics for a
deviation alert (so the key, which the bot logs, says nothing about which metric deviated). A second attempt is a no-op, in the
database, so a restart, a repeated event or two passes at once write nothing twice.

The service runs a pass every minute and when a ride is stored, the plan changes or wellness figures arrive (the clock and timers are
injectable, as in the other loops):

| Note | When | Waits for |
|---|---|---|
| **morning** | from `INSIGHT_MORNING_TIME` (07:00 in `TIMEZONE`) for 6 hours | this morning's recovery verdict (ready, caution, rest); or, **2 hours after the time**, a plan alone (the note then says there is no watch data yet); with neither, no note |
| **ride** | when the ride is stored (record and FIT), or at the next start for a ride from the last 36 hours | the ride's metrics; not for a ride under a minute |
| **week** | the weekly summary's day and time (`WEEKLY_SUMMARY_*`), up to a day late | not for a week with nothing planned and no ride; looked at again when something changes |
| **alerts** | with the morning note, and again when the data they read changes | a reading from this morning |

`INSIGHTS_ENABLED=false` writes and sends nothing (what is stored is still served).

### 6. Alerts and their thresholds (spec §6.6)

All in `alerts.ts`; the values below are the defaults, first guesses to be tuned on real data.

- **Deviation**: a scored metric (resting heart rate, sleep, bedtime, respiratory rate, oxygen saturation, heart rate in sleep, HRV) outside
  its personal baseline **on its adverse side** (resting heart rate above, sleep below, ...; the **streak** of ADR 0023) for
  **`ALERT_STREAK_DAYS` = 3** mornings in a row (2 to 14). Only the adverse side: a resting heart rate below the usual range is not a
  warning. **At most one alert per metric per streak**: `alert_streaks` holds (metric, the day the run began); a streak that goes on for
  ten days alerts once, a new run alerts again. Metrics crossing together are one alert (at most three, the longest runs first). The text is built from fixed
  sentences (`readings.ts`) saying what the pattern *usually* means for training and what to do, with no cause and no condition; the model
  may rephrase them under the same check.
- **Plan drift**, over the **7 days before today**, from the plan history (the sessions the plan had, kept after the cache drops a day;
  migration 0008 adds the planned load to it): **`ALERT_MISSED_SESSIONS` = 2** planned sessions with no ride (a ride pairs with a session
  as the bot's summaries do: by workout id, else the only session of that day; a session the rider *skipped* is neither missed nor
  planned), **or** the rides' load more than **`ALERT_LOAD_ABOVE_PERCENT` = 30** above or more than **`ALERT_LOAD_BELOW_PERCENT` = 40**
  below the load planned for the sessions planned in those days, compared only when the planned load is at least 50 and known for every
  session. **At most once a week** (the key is the Monday). One missed session of one planned counts as a load 100% below the plan when
  its planned load is 50 or more: that is what the rule says.

### 7. Telegram

The notes and alerts are `NotificationSource` candidates: kinds `insight-morning`, `insight-ride`, `insight-week`, `insight-deviation`,
`insight-drift`, key the note's key, through the bot's **exactly-once `bot_notifications`** (claimed before the send, retried from the table,
never twice across a restart) and to the **owner's chat only**. The notifier reads them every tick and when the service announces
`insight-stored`, so a note goes out within moments. A note is worth sending for 12 hours (morning), 36 (ride, week) or 24 (alerts) after
it was written. The morning message *is* the morning note (the wording is adjusted by the state, in the text); a ride note is sent after
the bot's own numeric summary of the ride, and the weekly review after its numeric weekly summary: the numbers stay, the words are added.
A ride note with an **open topic** ends with it: "Worth a chat with your coach: …", then the **opening question as a monospace block**
(`pre` entity of `sendMessage`, no parse mode, nothing to escape; Telegram lets the reader tap it to copy). `INSIGHTS_ENABLED=false`
sends none of it. The phone sends no notification of its own.

### 8. Discussion topics (spec §12.3)

The post-ride analysis may attach **one** topic, for a real reason only, from the **rule table** in `topics.ts` (first match wins):

| Rule | Reason | Defaults |
|---|---|---|
| `cut-short` | the ride stopped before the plan did | under 75% of the planned length (the share shown, in whole percent); not for a plan under 10 minutes |
| `power-record` | a new best at a standard duration | 5, 20 or 60 minutes (the longest first); at least 3 earlier rides at that duration; at least 1% above the best |
| `load-off-plan` | the ride's load far from the planned load | +30% or −40%; a planned load of at least 30 |
| `repeated-drift` | the plan has drifted again | the second plan-drift alert within 28 days |

Four more rows are **named hooks, not available** (`stall-guard`, `effort-rating`, `tag-streak`, `repeat-trend`: the signals come in
later stages): the day a stage brings its signal, its row is switched on in the table and nowhere else. *(Stage A1b of v0.4.0, ADR 0033: `stall-guard` is now an available row, at least 3 activations in one ride.)* The topic's text (one sentence) and
opening question (in the rider's voice) are **templates filled from the ride's facts**, so every number in them was computed and no model
is involved. **At most two topics in any rolling 7 days** and one per ride, enforced in the database transaction that writes the note
(the note is written whatever the limit says); an open topic **closes when the coach marks it discussed** (`mark_discussion_topic`) or
**after 14 days** (`expired`, set when the service looks). Table `discussion_topics`.

### 9. API, contracts and MCP

`GET /v1/insights?from&to` (bearer, local days of the note's creation, at most 366, newest first, `no-store`) answers
`{ from, to, generatedAt, stale, insights: [{ id, kind, createdAt, text, rideId, topic }] }` (`contracts/schema/insights-response.schema.json`,
`discussion-topic.schema.json`, Kotlin `InsightsApiJson`, one set of fixtures for both languages). MCP: `list_insights` (compact by
default; kind filter; `detail: full` adds the prompt version and the facts hash), `get_facts` (the very facts of an existing note, else
worked out now, `stored` says which), `list_discussion_topics` (open by default; the ride, the rule, the opening question),
`mark_discussion_topic` (the only status that can be set is `discussed`; nothing else changes). Nothing here can change the plan.

### 10. Privacy

The facts and the text are health data: stored in the database like the wellness records (the backups hold them), shown to the
endpoint the owner chose, returned by the API to the device tokens and by MCP to the clients the owner approved, and **nowhere else**.
**No log line holds a value, a sentence, a fact, the key or the address of the endpoint**: only kinds, ids, counts and the way a note
was written. A database error that reaches the insights is logged as its code only (its message may quote the row). A test runs a
whole day of notes, alerts, rides and a week with a model that works, fails and misbehaves, and a database that fails, and searches the
log for every note's text, the facts, the key, the address and the words of the data.

## Deviations from the brief

- **Five kinds, not three.** `deviation` and `drift` are written to the same list as `morning`, `ride` and `week`, so the companion's Today
  can show an alert (spec §6.6: "the companion's Today screen") with no second endpoint. A client that has only the three labels needs a
  fourth ("Heads-up"). The companion's ride detail has **no `insightId`**: the notes carry their `rideId`, and the note of a ride is found
  by it.
- **`INSIGHTS_ENABLED`**, a master switch, and `INSIGHT_DENY_WORDS`, `ALERT_MISSED_SESSIONS`, `ALERT_LOAD_ABOVE_PERCENT`, `ALERT_LOAD_BELOW_PERCENT`,
  `LLM_TIMEOUT_SECONDS`, `INSIGHTS_LANGUAGE` are additions to the variables of the brief.
- **A shared plan history.** The bot's `plan_history` is built by the service now (one instance for the bot and the insights), has a new
  `planned_load` column, and is brought up to date by the insights even with the bot off.
- **`WellnessService.recoveryDays`** and two events (`wellness-updated`, `insight-stored`) were added to wellness and the bus.
- The post-ride note does not replace the bot's numeric ride summary, and the weekly review does not replace the weekly summary.
- **Topic texts are templates, not model text**, so a topic cannot carry an invented number.

## What is not claimed

- That a note is *right*: the check enforces the numbers, the dates, the times and the words, not the sense. The first notes are for the
  owner to read; a template note is plain on purpose.
- That the thresholds are right. 3 mornings, 2 missed sessions, +30%/−40%, 75% of the planned length, a 1% record are first guesses that the
  first weeks of real data will move; each is one setting or one constant.
- That the deny list is complete or free of false rejections: it is broad on purpose, and a false rejection costs a retry or a plainer note.
- That the template or the readings are good prose, or that the facts are the best facts. Both are first versions, prompts included
  (`v1`).
- Number words in a language other than English are not checked, only digits.

## What only a real endpoint, bot and data settle

1. **The endpoint.** That MiniMax M3.1 (or the owner's choice) accepts `max_tokens`, `temperature` and `stream: false` as sent, answers in
   `choices[0].message.content`, how it marks its reasoning (`<think>` is handled; a separate `reasoning_content` field is ignored), how long it
   takes against the 60 s, whether it honours `Retry-After`, and what a 4xx looks like.
2. **The model's behaviour.** How often it quotes a number it was not given, how often it names a cause or a condition despite the rules,
   how much it likes a percentage it works out itself: the rate of `source: template` rows after a week says so. The wording of the prompts is
   a first version.
3. **Telegram.** That `entities` of type `pre` render as a block and copy on a tap, that offsets in UTF-16 units are right for a question
   with an accent or an emoji, and the 4096-character limit (a note and a topic stay far below).
4. **Real data.** Which metrics of the owner's watch cross their usual range, how often (an alert every day would say the streak is too
   short), how the planned load of the intervals.icu workouts compares with the rides' load (the drift's load rule), the 07:00 default
   across a clock change, and the first week of the weekly review.
5. **The readings' wording** ("usually goes with accumulated fatigue or stress"): a coach's eye on them.

## Consequences

- With nothing configured the owner already gets a morning message, a note after each ride, a weekly review, alerts and a topic for the
  coach, all in Telegram and on the phone's Today, and nothing leaves the server.
- A model is a setting, not a rebuild: `LLM_BASE_URL`, `LLM_MODEL`, a key. Moving to another endpoint changes that and the prompts' versions.
- The planning agent reads what the rider was told (`list_insights`), the facts behind it (`get_facts`) and what to start a chat from
  (`list_discussion_topics`), and marks a topic discussed; the coach notebook and the missed-session replanning (spec §11.6, §12.2) build
  on this and are not here.
- The stages after this one add signals to the topic table (rating, tags, repeats, the stall guard) without touching the rest.
