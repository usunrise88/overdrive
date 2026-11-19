# ADR 0035: The coach notebook, the Methodology section and replanning after a missed session

Status: accepted (v0.4.0, stage A2a, server side, 2026-11-19).

## Context

Every coach-agent session starts with no memory of the last one (spec §11.6). The notebook is its persistent working memory about the
rider, with a Methodology section that cannot drift from one session to the next (§12.4). When the plan needs rebuilding (a missed session,
a session the rider moved or deleted, illness, travel) the service only detects and queues, and the agent rebuilds the week (§12.2).
Built in `server/` and `contracts/` only; the Kotlin model, the companion's screen, the easier variant (stage A2b) and the live coach
are later stages. Written without a real MCP client, bot or account: the tests use the SDK's client, the fake intervals.icu and the fake
Telegram of the earlier stages.

## Decision

1. **Storage: versions, never updates.** Migration `0010`: `notebook_versions` (scope, section, version, text, reason, kind `edit`
   `append` `rollback`, `rolled_back_to`, author, time; unique per scope, section and version). The current text is the highest version.
   A rollback is a new version with the old text, so nothing is rewritten and a rollback can be rolled back. One writer at a time per
   notebook (an advisory lock) decides and writes in one transaction, so two sessions appending both keep their line; `expectedVersion`
   lets an agent refuse to overwrite a change it has not seen. `author` is the MCP client, never a token.
2. **Fixed sections, two notebooks.** Coach: `profile`, `what_works`, `what_does_not`, `injuries`, `methodology`, `open_questions`, `log`
   (in the contract). Game master: `game_plans`, `notes`, `log`, a guess the game's own stage may extend. The log is edited by
   `append_notebook_log` (a dated line, at most 300 characters) and condensed with `update_notebook_section`.
3. **Caps answer, they do not fail.** `NOTEBOOK_MAX_CHARS` 12000 for the document, `NOTEBOOK_LOG_MAX_CHARS` 3000 for the log (UTF-16
   units). An edit that would pass the document cap is refused with `saved: false` and how to make room; an edit that does not lengthen
   the document is always accepted, so condensing is always possible. The log entry that passes its cap is **saved** (a decision is never
   lost) with `condense` and a note; further entries wait (`log-full`) until the log is condensed. A rule the agent can fix (unknown
   section, methodology without a reason) is a tool error instead.
4. **The Methodology.** `update_notebook_section` and `rollback_notebook_section` on it refuse a missing or blank reason. A saved change
   is a version (with its reason), is logged (scope, section, version, kind: never text) and is announced in **one Telegram message
   written by code**: `Coach notebook: Methodology changed (version 3).` / `Why: …`. It goes through the existing notifier as a
   `NotificationSource` derived from the version rows (exactly-once in `bot_notifications`, valid for a day), kicked by a new
   `notebook-changed` event. The tool answer says whether the rider was told (the bot needs its token and the owner's chat).
5. **The game master's scope reuses the OAuth scopes.** The access token already carries a scope string the MCP route turns into
   `AuthInfo.scopes`. `game-master` is a second accepted scope, granted **in addition to** `mcp` (alone it is `invalid_scope`) when a
   client asks for `scope=mcp game-master` and the owner types the passphrase; the approval page says so. It is **not advertised**
   (`scopes_supported` and the challenge stay `mcp`), so a client that follows the metadata never asks for it. The tools are **built per
   request for the token's scopes** (`createMcpServer(ctx, caller)`): a coach session's notebook tools have no `scope` argument and
   nothing in their descriptions or the instructions mentions the game master (an unknown argument is dropped, so naming it anyway reads
   the coach notebook). A session with the scope gets the argument and an instruction to keep the contents out of the coach notebook. No
   REST route can name it; nothing announces it. This separates the agents and the rider's apps, **not the owner**, who approves every
   client with the passphrase; static tokens carry `mcp` only, so a game master needs OAuth.
6. **REST for the companion.** `GET /v1/notebook` (contract `notebook-response.schema.json`): the seven sections always, with version,
   time and reason, the caps and the total; coach only, read-only, not cached.
7. **Plan events.** `plan_events` (kind `missed` `moved` `deleted` `variant` `illness` `travel`, day, workout id, detail, status, times,
   resolution note, `dropped`), idempotent by a **natural key** (`missed:<plan id>:<day>`, …) so a day is one event however often it is
   looked at.
   - *Missed:* a check at the end of each local day, **plus a grace of 180 minutes** (`PLAN_EVENT_DAY_END_GRACE_MINUTES`) so a late upload is
     not a miss, over the plan history and the tablet's rides, with the rule of the missed-session alert (`findMissed`, extracted from
     `evaluateDrift`, so the two cannot disagree). A cursor (`plan_event_cursor`) records how far it got: a restart does not look again, a
     downtime is caught up for at most 7 days, and the very first check looks only at the last day. Every ten minutes and at start, only
     with a plan source.
   - *Moved or deleted:* found by the plan refresh. `PlanStore.mirror` now reports, inside its transaction, a session whose date
     changed and one inside the window that vanished; what the service changed itself through its tools or `/skip` and `/move` is already
     in the cache and never reported. One honest limit: a refresh already reading when a tool wrote can restore the old entry, and the next
     refresh then reads the tool's change as the rider's (one event the agent resolves with a note).
   - *Variant:* the type and `planEvents.emit` exist; nothing calls it until stage A2b.
   - *Declared:* `POST /v1/plan-events` (`illness` or `travel`, optional `until`; 201 new, 200 repeated; a bad body is `400 invalid_json`,
     no new error code) and `/ill`, `/travel` in the bot (`/ill 3` three days, today included, or a date). They were small: the command
     framework is a switch.
   - *Resolved:* `resolve_plan_event` (`id`, `resolutionNote`, explicit `dropped`). One Telegram note **written by code from the
     fields** (what happened, what became of it, `Why:` the agent's note as written; a dropped session is said to be dropped), once,
     through the same notifier. The tool says whether the rider was told.
8. **No feedback queue exists yet.** The brief assumed one; the service has none (the implicit-feedback facts of §11.1 and §11.5 come
   with their stages). `list_plan_events` is the queue and the place a later fact of that kind will join. Nothing was invented for it.
9. **The agent's instructions.** The server instructions now tell every session to read the notebook and check the open events first, to
   record the reason for any plan change, to check it against the Methodology and to resolve each event; they are the text a model reads
   at the start of each session.

## Not built, on purpose

The Kotlin models and the companion's notebook screen; the easier variant itself; routes; the live coach; the scheduled end-of-season
methodology review (the agent keeps "the date of the next review" in the section's text); `GET /v1/plan-events` for the companion;
matching a planned day to an outdoor or watch activity (as in the plan-drift alert); static tokens with the game master's scope; a
tombstone to close the refresh race of item 7.

## What only real use settles

Whether 12000 and 3000 characters are the right caps for the agent's context; whether three hours of grace is long enough for the tablet
to upload after a ride (it is a setting); whether the agent condenses the log as the answer asks, and writes a methodology reason worth
sending to the rider; whether a client that is asked for `scope=mcp game-master` sends it as configured (the MCP clients' scope handling
is untested against real ones, as ADR 0018 says); the wording of the two Telegram messages.

## Consequences

- An agent session can start from what the last one knew and why the plan is as it is; the rider can read the same notebook and is told
  when the approach changes.
- A day with no ride, a session the rider moved or deleted and a declared illness become a queue the agent works through, and the rider
  is told what became of each; no code in the service moves a workout.
- `PlanStore.mirror` returns `MirrorChange` (the old three lists plus `moved` and `deleted`); `plan-changed` still carries only the three.
- `createMcpServer(ctx, caller?)`: tools can now depend on the token's scopes.
- The companion's read-only view of the notebook exists (`GET /v1/notebook`, Settings, Coach notebook; `design/companion.md` §4.13); the phone never writes it.
