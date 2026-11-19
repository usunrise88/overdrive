# ADR 0019: Google Calendar push and the Telegram bot

Status: accepted (Phase 2, stage P2-4, server side, 2026-11-18).

## Context

Spec §5.3 wants the planned workouts mirrored one way into a dedicated Google Calendar, and §5.4 wants a Telegram bot for the
owner: reminders, a summary after each ride, a weekly summary, and a few commands. Both read the plan the way the rest of the
service does (ADR 0016: `PlanStore`, the `plan-changed` and `ride-stored` events, the intervals.icu client) and neither may
change a wire format, so no contract changes. This ADR records how they were built **without a Google account and without a
Telegram bot**: the Calendar API v3, Google's OAuth 2.0 for installed apps and the Telegram Bot API come from their public
references, read once, nothing from them committed. Every test runs against a fake Google and a fake Telegram that we wrote from
the same notes (`server/test/helpers/fake-google.ts`, `fake-telegram.ts`), so the tests prove the code agrees with the notes and
not with the real services. The last section lists what only real accounts settle.

## Decision

### 1. Shape of the code

Two modules that import nothing from each other, both built by `createServices` (`services.calendar`, `services.telegram`, both
`null` when off) and started and stopped with the other loops:

| Module | Role |
|---|---|
| `src/config-integrations.ts` | The settings, as a zod shape spread into `config.ts`; every one optional |
| `src/zoned.ts` | Wall-clock arithmetic in `TIMEZONE`: "07:00 on that date", weekday, Monday of a week, English day names |
| `src/plan/workout-summary.ts` | A native workout in plain words (duration, steps), computed by code; shared by the calendar event, the reminders and `/today` |
| `src/gcal/` | `client.ts` (OAuth refresh-token grant and Calendar v3, plain `fetch`), `event.ts` (what an event says), `mirror.ts` (the worker), `auth-cli.ts` (the one-time helper), `index.ts` |
| `src/telegram/` | `client.ts` (Bot API), `bot.ts` (long polling and the owner filter), `commands.ts`, `notifier.ts` (what is due now), `messages.ts` (every sentence), `history.ts` and `rides.ts` (what it reads), `context.ts`, `index.ts` |

No SDK: `googleapis` and the Telegram frameworks are far larger than the handful of calls used here. Migration **0004**
(`0003` belongs to the MCP and OAuth stage, built at the same time) adds four tables: `calendar_events`, `plan_history`,
`bot_notifications`, `bot_state`.

### 2. Configuration

| Variable | Default | |
|---|---|---|
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REFRESH_TOKEN`, `GOOGLE_CALENDAR_ID` | unset | The calendar is on only when **all four** are set. With some of them set and some not it stays off and the log names the missing ones (never a value): half a setup is usually a typo, and a server that refuses to start because the owner is halfway through `google-auth` would be worse |
| `GCAL_WORKOUT_START` | `07:00` | The time of day a workout starts, in `TIMEZONE`: the event's start, and the reference of the lead reminder |
| `TELEGRAM_BOT_TOKEN` | unset | The bot is on when this is set |
| `TELEGRAM_OWNER_CHAT_ID` | unset | The one chat the bot answers. With the token but without this the bot is in **setup mode** (section 8) |
| `REMINDER_EVENING_TIME` | `20:00` | Evening-before reminder, `HH:MM` or `off` |
| `REMINDER_LEAD_MINUTES` | `60` | Reminder this long before the start; `0` is off, at most 1440 |
| `WEEKLY_SUMMARY_DAY`, `WEEKLY_SUMMARY_TIME` | `sun`, `18:00` | Day (`mon`, `monday`, …) and time, or time `off` |
| `GOOGLE_API_BASE_URL`, `GOOGLE_TOKEN_URL`, `TELEGRAM_BASE_URL` | the real ones | Overridable, as `INTERVALS_BASE_URL` is: the tests point them at fakes |

Validation is in `config.ts`'s schema; an error names the variable and never prints the value.

### 3. The calendar: what the mirror does

- **Input.** Only the plan cache. A pass compares the workouts the cache holds from today on (`PlanStore.list`, so only the ones
  the tablet can play; an unsupported workout has no steps to describe and no length) with the `calendar_events` table, which says
  what Google shows, and creates, updates or deletes what differs. `plan-changed` just nudges a pass; a pass also runs every minute
  and at start. A pass is idempotent, so a missed event costs nothing.
- **The event.** Title: the workout's name. Start: the date at `GCAL_WORKOUT_START` in `TIMEZONE`, sent as an instant with the
  `timeZone` field. End: start plus the workout's duration (at least five minutes). Description: the steps in plain words and the
  total, by code: `10 min warm-up (45% to 70% FTP), 3 x (10 min sweet spot at 88% FTP, 5 min recover at 50% FTP), 5 min free ride.
  Total 1 h 15 min.` No reminders are set: the calendar's own notification settings apply (deploy.md, section 14). Two private
  extended properties mark the event as ours (`overdrive=1`, `plannedId=icu-123`).
- **Idempotent creates.** Google lets the caller choose an event id (5 to 1024 characters from `a`-`v` and `0`-`9`). Ours is
  `ovd` plus 40 hex digits of the SHA-256 of the planned id. A create whose answer was lost, repeated, gets `409`, and the mirror
  then patches that event instead: no duplicate. The same covers a database restored from a backup. A deleted event keeps its id in
  Google, so creating it again also answers `409`; patching with `status: confirmed` brings it back. Updates are `PATCH`, so a
  field the owner added by hand (a colour) survives; the fields we write are overwritten the next time the workout changes.
- **Never read back.** A change made in Google Calendar is not noticed and not undone until the workout next changes. The only
  read is the start-up sweep below, and it reads ids and markers, not content.
- **State and retries** (`calendar_events`, like the upload worker's `ride_destinations`): per planned workout the Google id, the
  SHA-256 of what was last pushed, and `state` `pending` (`attempts`, `last_error`, `next_attempt_at`), `synced` or `failed`. A try
  takes a ten-minute lease first, so a crash mid-try resumes after it. Waits 1, 2, 4 … minutes up to 6 hours, persisted, so a restart
  does not forget them; **at start every wait is cancelled** (the owner may have fixed something). `failed` after 20 tries, or at
  once when Google refuses the content (4xx other than the ones below); a failed event is retried only when the workout changes.
  Wrong credentials (`invalid_grant`, 401, 403, 404 for the calendar) are not the event's fault: the whole mirror stops for six
  hours, once, with one error line that says to run `google-auth` again, and a restart tries at once.
- **Reconciliation.** At start (once per start, retried until it succeeds) the mirror lists our events from today on (by the
  `overdrive=1` marker) and deletes those whose workout is not in the plan, and any second event for one workout. It does **nothing
  until the plan has been read once**: an empty cache on a fresh database means "not known yet", and deleting on that would empty
  the calendar.
- **Days that have passed.** The plan cache drops a day once it is past; the calendar keeps it. A row of a past day is never
  deleted from Google (only tidied out of the table after a week), and the sweep only looks forward from today. "Removed from the
  plan" means a day that has not passed.
- **Off.** Without the four `GOOGLE_*` variables there is no object, no request and one log line. With them but no plan source
  (`INTERVALS_API_KEY` unset) the mirror says once that there is nothing to push and does nothing.

### 4. `google-auth`

`pnpm --filter @overdrive/server run google-auth` (`src/gcal/auth-cli.ts`) is OAuth 2.0 for installed apps, the loopback flow with
PKCE: it listens on `127.0.0.1` (any free port, or `--port` for an SSH tunnel), prints the address to open, receives the code
there, exchanges it and **prints `GOOGLE_REFRESH_TOKEN=…` to the terminal only**. It writes no file (a test checks that the source
imports no file API) and sends nothing anywhere but Google. If the browser is on another computer than the terminal, the
redirect fails to load, and the owner pastes the whole address from the browser's address bar into the terminal instead; the helper
then needs no inbound connection at all, so it also works inside `docker compose run`. The client id and secret come from the
environment or are asked for (the secret is not echoed). The **device flow is not usable**: Google's device flow does not allow
the Calendar scope. Scope: `calendar.events` (create, change and delete events; nothing else). `access_type=offline` and
`prompt=consent` make Google send a refresh token every time.

### 5. Telegram: ears

Long polling with `getUpdates` (25 s waits, `allowed_updates` message and callback_query), so no inbound webhook; `deleteWebhook`
is called at start because the two exclude each other, and the command menu is set (`setMyCommands`). The next `offset` is kept in
`bot_state`, so a restart neither replays a command nor skips one. Errors back off 1, 2, 4 … 60 s and are logged on the first and
every tenth; a refused token waits five minutes, a 409 (another poller) thirty seconds. Everything is **plain text**: workout names
contain `_` and `*`, which Markdown would swallow, and nothing needs escaping.

**Only the owner.** An update is acted on only if its chat id equals `TELEGRAM_OWNER_CHAT_ID`. Any other update, a message or a
button, is ignored without a reply and logged with the chat id and the update id, **never its content**. The bot token is part of
every API URL, so the client keeps URLs out of errors and logs and scrubs the token from anything Telegram says back (a test makes
the fake echo it).

### 6. Telegram: what it says by itself

A tick (every 30 s, and when a ride is stored or the plan changes) works out what is due *now* from the plan, the rides and the
clock, and sends each thing once. A row per (kind, key) in `bot_notifications` is **claimed before the send** (an atomic
`INSERT … ON CONFLICT … WHERE`, with a ten-minute lease in `next_attempt_at`) and marked `sent` after it, so a restart neither
repeats nor loses one. The text is built at the moment of sending, from the plan as it is then, so a reminder for a workout that
has since been moved, removed or ridden is `dropped` and never sent. A failed send is retried from the table after 30 s, 1, 2,
4, then 5 minutes, and given up after ten tries. The one window that is not closed is a crash between the send and the mark:
that message is sent once more after the lease (at-least-once, never silently lost). The clock is injectable, so the tests move
time and wait on conditions, not sleeps.

- **Reminders**, per workout. *Evening before*: at `REMINDER_EVENING_TIME` on the day before, sent late if the service was down
  or the workout was planned after that time, but not once the lead reminder is due or the workout has started (it would say
  "tomorrow" about today). *Lead*: `REMINDER_LEAD_MINUTES` before `GCAL_WORKOUT_START`, not sent if the workout is already ridden,
  never after the start. Both carry **Skip** and **Move** buttons that go into the flows below. The start time is the calendar's, one
  setting for both.
- **After a ride**, on `ride-stored`: duration, average and normalised power, average and maximum heart rate, and the load, all
  from the stored record's summary (the tablet computed them; nothing is recomputed, samples are never loaded), and when the ride
  is linked to a plan the planned and ridden durations. Linked means the ride names the workout, or it is the only one planned that
  day (the intervals.icu upload's rule, ADR 0016). Rides under a minute are an accident, not a session, and get no message. A
  ride whose event was missed (the service was down) is summarised on the next start if it began in the last 36 hours; an event
  while running is summarised however old the ride is (a tablet that was offline).
- **Weekly**, on `WEEKLY_SUMMARY_DAY` at `WEEKLY_SUMMARY_TIME`, still sent for a day if the service was down: the week
  (Monday to that day) against the week before **up to the same weekday**, so a Friday summary does not compare four days with
  seven. Sessions planned against done (and skipped from the bot), the ones missed by name, rides and moving time, and the load
  (the sum of the rides' training stress score) with the difference. A week with nothing planned and no rides sends nothing.
  Numbers only: any "insight" in words is Phase 3 and is not built. **No model is used anywhere in this stage.**
- **`plan_history`.** The plan cache drops a day once it is past, but "planned against completed" needs last week's plan, so the
  bot keeps its own row per planned workout it has seen (date, name, planned length, `planned` or `skipped`), synchronised from the
  cache on every `plan-changed` and at start. A workout that vanishes from the cache while its day has not passed was deleted and
  is forgotten; one that vanishes because its day passed stays; one skipped from the bot stays as `skipped`. The first week after
  the bot is switched on counts only what it saw.
- **A refused bot token is asked about once per poll** (found by the Phase 3 long checks, 2026-11-19). When Telegram answers 401 the
  notifier stops its tick, and it also stops asking when an event (a plan change, a stored note or ride) kicks it: only the next
  30-second poll asks again, in case the owner has mended the token. Before this, a second event right after a refusal made a second
  tick, which sent the next message and was refused too (a flaky `telegram-notifications` test on CI, made deterministic since).

### 7. Telegram: commands

`/today`, `/week` (the next seven days), `/skip`, `/move`, `/help` (also `/start`). They talk to the plan only through `PlanStore`
and the intervals.icu client (`deleteEvent`, `updateEvent`, then `services.refresher.refresh()`): the calls the MCP tools make, so
nothing here is a second way of writing the plan. With no plan source they say so in plain words and change nothing.

- `/skip` and `/move` act on the next day, from today on, that has a workout not yet ridden. One workout goes straight to the
  question; several are offered as buttons first. **Skipping asks to confirm** ("Skip Sweet spot on Wed 18 Nov? It is deleted from
  the calendar in intervals.icu."), then deletes the event in intervals.icu, removes it from the cache (which makes the Google
  event follow) and records it as skipped. **Moving** offers the next seven days as buttons (not the day it is on), updates the
  event's date in intervals.icu and in the cache. A failure at intervals.icu is answered "Could not … Nothing was changed."
- Buttons carry their action in at most 64 bytes of callback data (`sk:`, `sk!:`, `mv:`, `md:<id>:<date>`, `no`); every handler
  checks the plan again, so a button on an old message says the workout is gone instead of acting. A message that cannot be edited
  is replaced by a new one.

### 8. Setup mode

The owner cannot know their chat id before the bot has seen a message from them. With `TELEGRAM_BOT_TOKEN` set and no
`TELEGRAM_OWNER_CHAT_ID`, the bot polls and replies to nobody; each message it receives is logged with its chat id and the
instruction to set `TELEGRAM_OWNER_CHAT_ID` to it. That is the whole procedure in `docs/deploy.md`, section 13, and the token and
chat id stay in `.env`.

## Deviations from the brief

- **`plan_history`** and the weekly semantics (same weekday of the week before; skipped counted separately) are additions: the
  cache alone cannot answer "planned against completed" for days that have passed.
- **Setup mode**, the Skip and Move buttons on reminders, and "next day with a workout" instead of "today" for `/skip` and `/move`.
- `REMINDER_EVENING_TIME` and `WEEKLY_SUMMARY_TIME` accept `off`, `REMINDER_LEAD_MINUTES=0` is off, and a partly set `GOOGLE_*`
  leaves the calendar off with a warning instead of failing the start.
- Workouts the tablet cannot play are not pushed to the calendar.
- `GOOGLE_API_BASE_URL`, `GOOGLE_TOKEN_URL` and `TELEGRAM_BASE_URL` exist for the tests and for proxies.
- `server/test/migrate.test.ts` and `config.test.ts` no longer hard-code how many migrations and settings there are, so the next
  stage does not break them.
- The migration is numbered 0004 as briefed. The parallel stage's 0003 was not in this branch, so a local placeholder (not
  committed) stood in for it; the orchestrator's merge supplies the real one.

## What only real accounts can settle

Nothing below was verified against Google or Telegram. Each is a place where the code, the fake and the tests share an assumption.

**Google**

1. **The OAuth flow.** That a Desktop-app client accepts `http://127.0.0.1:<any port>` as redirect, that PKCE with the client
   secret is accepted together, that `prompt=consent` returns a refresh token every time, and what the consent screens look like.
2. **The 7-day expiry.** An OAuth app whose consent screen is in *Testing* gets refresh tokens that expire after seven days; deploy.md
   says to publish it ("In production", unverified is fine for one's own use). If the token does expire the mirror logs the
   refusal and says to run `google-auth` again. Whether a personal account can publish an unverified app with this scope without
   friction is for the owner to find out.
3. **Event ids.** That `calendar.events` allows `events.insert` with our own `id`, that the id format is accepted, that a
   duplicate is `409` (also for an event that was deleted), that `PATCH` with `status: confirmed` brings a deleted event back, and
   what `PATCH` and `DELETE` answer for a deleted event (we treat 404 and 410 as "gone").
4. **Listing.** `privateExtendedProperty=overdrive=1` as a filter, `timeMin` and `timeMax` as overlap bounds, paging.
5. **Times.** That `dateTime` as a UTC instant plus `timeZone` shows at the right local time, including across a clock change.
6. **Reminders.** That an event with no `reminders` follows the calendar's notification settings (deploy.md tells the owner where
   to set them).
7. **Limits.** Rate-limit answers (`403` with `rateLimitExceeded`, `429`), which we retry, and the token endpoint's error shapes.

**Telegram**

8. That `getUpdates` answers `409` while a webhook is set and `deleteWebhook` clears it; that `allowed_updates` is honoured; that
   unconfirmed updates stay available after a restart (they are kept for 24 hours), which the saved offset relies on.
9. The edit rules: that editing a bot message with identical text answers 400 "message is not modified" (we ignore it), that an old
   message can still be edited (otherwise we send a new one), and `429` with `retry_after`, `403` when the owner blocked the bot.
10. That a private chat's id equals the owner's user id (the owner finds it in the log), and that plain text with
    `disable_web_page_preview` renders as written.
11. That 25 s long polls survive whatever sits between the server and Telegram.

**intervals.icu** (ADR 0016 section 3 and the list there): that `DELETE` of an event removes it and that a **partial** `PUT` with
only `start_date_local` moves it without disturbing its steps. Both are what `/skip` and `/move` rely on and neither has been seen
against the service.

**Judgement, not facts.** The wording of the messages, the 07:00 default, the 36-hour catch-up, the weekly layout and the five-minute
minimum event: all reasonable on paper, for the owner to adjust after a week of use.

## Consequences

- With the settings in place the owner's calendar shows the plan, the phone gets a nudge the evening before and before the session,
  and a rider can skip or move a workout from the couch; all of it survives an intervals.icu, Google or Telegram outage and a restart.
- Moving off intervals.icu, or adding another calendar or chat channel, touches `src/gcal/` or `src/telegram/` only: both read the
  plan through `PlanStore` and the events.
- The MCP stage's tools and the bot are two writers of the same plan through the same calls; if they diverge, `commands.ts`
  (`doSkip`, `doMove`) is the place to move onto a shared function.
- Strava (P2-5) is unaffected; Phase 3's notifications (§6.6) will reuse `notifier.ts`'s claim-then-send and the history table.
