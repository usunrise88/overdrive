# Phase 2 kickoff

A handoff for whoever starts Phase 2 (spec §5: the web service, planned workouts, the MCP server, the calendar, the
Telegram bot, ride upload) in a new session, for example on the server that will run the service. Written 2026-11-18, after
the first ride on real devices. **Read `CLAUDE.md` first**, then `docs/spec.md` §1, §3, §5, §8, §9 and `docs/roadmap.md`.

## Where the project stands

- **Phase 1 is released as `v0.1.0`** (signed APKs on GitHub Releases). The development branch `claude/sleepy-gates-zqbtwq` is
  ahead of it with fixes found on the first ride (Click defaults, the D500's Reset; ADR 0013). The owner has held back
  `v0.1.1`; cut it only when asked.
- **The first ride worked** on the tablet: the trainer, the overlay, the Click's gear and media buttons. What it showed is in
  `docs/bench-reports/2026-11-18-first-ride.md`. Still open from the bench: the FIT's laps do not show as intervals in
  intervals.icu (the activity itself imported fine as a Virtual Ride; "Use Laps" was not found in the owner's menus), HRV, the
  hour-long ride, the app in the background, and the Click's default button map against the real puck (ADR 0013).
- **Acceptance criteria are not confirmed** except as informal observations (`docs/phase1-acceptance.md`).

## What already exists for Phase 2

| Piece | Where | State |
|---|---|---|
| Web service skeleton | `server/` (Fastify, Zod, Node 24 type stripping, `Dockerfile`) | A health endpoint and configuration only. CI builds and smoke-tests the image. |
| Wire formats | `contracts/schema/` (`ride-record.schema.json`, `workout.schema.json`), fixtures, TypeScript tests, Kotlin `WorkoutContractTest` | The ride record and the workout exist; the sync API's request and response shapes do not. |
| Upload queue on the tablet | `core/.../sync/` (`Outbox`, `SyncCoordinator`, `SyncProvider`, `NoopSyncProvider`), `android/data/.../FileOutbox.kt`, ADR 0012 | Every stored ride is queued once, oldest first, durably; the provider acknowledges nothing, so nothing leaves the tablet. |
| Sync settings | Settings, Sync (address and token, stored, unused) | The token is a secret type that prints as dots. The ride app still has no `INTERNET` permission: **adding it is part of the first Phase 2 stage** and needs an ADR. |
| FIT | `core/.../fit/FitWriter` (ride record to FIT), `docs/protocols/fit.md` | The server can store the ride record and let the writer's output be regenerated, or store the FIT as uploaded: decide in the first ADR. |

## Rules that bind Phase 2

From `CLAUDE.md`: **contracts first** (a wire format starts in `contracts/`, then the Kotlin model and the TypeScript side in the
same change); the tablet talks only to our server, never to an external service; **server secrets live in the server
environment only**, never in the repository (it is public); **Strava is write-only** (never read Strava data); the LLM writes
insight text from facts the service computed and never calculates; record significant decisions as ADRs; every stage ends with a
release (`v0.2.0-alpha.N`, then `v0.2.0`), made as in `docs/releasing.md` (the owner makes the release in the GitHub web UI; the
sandbox cannot push tags).

## A proposed split into stages (not yet in the roadmap)

1. **Rides leave the tablet.** Sync API schemas and fixtures in `contracts/`; an intake endpoint with token authentication and
   storage on the server; a real `SyncProvider` over HTTPS in the app (`INTERNET` permission, retry and backoff, idempotent by ride
   id); the same ride arriving twice stored once; then the upload of the FIT to intervals.icu so a ride appears there with no manual
   step.
2. **Planned workouts reach the tablet.** A plan in the service, the workout contract in use, a list on the tablet's ready screen.
3. **The MCP server and planning** (spec §5.1, §5.2).
4. **Google Calendar and the Telegram bot** (§5.3, §5.4).
5. **Strava upload and its description** (§5.5, §5.6).

## Decisions only the owner can make

- Where the service runs, its address and HTTPS (the tablet needs a stable HTTPS URL).
- Storage: for one user a single SQLite file on a volume is the simplest; Postgres only if the owner wants it.
- How the tablet proves who it is: a long random bearer token, issued by the owner and typed into Settings, Sync (already there).
- Accounts and keys the stages need (intervals.icu API key, Telegram bot token, Google OAuth client, Strava access): each given
  to the **server's environment**, never to the repository, and never pasted into a chat.

## Habits that paid off in Phase 1

- Test timing-dependent code against the condition, not a sleep: two flaky tests (a DataStore file still held, a state read one
  step early) each cost a release run.
- A fake written from the same notes as the code proves consistency with the notes, not with the hardware: keep a list of what
  only the real thing can settle, and ask the owner for the device's own recordings (the app writes them).
- Briefs to subagents are self-contained; the main session reviews, merges and re-runs the whole check before pushing.
