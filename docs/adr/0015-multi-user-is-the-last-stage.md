# ADR 0015: Multiple users come last

Status: accepted (owner's decision, 2026-11-18).

## Context

Spec §1 lists multiple users and accounts as a non-goal. The owner asked when they would arrive, and decided they come
**as the very last stage of the project**, after Phase 3.

## Decision

- Phases 1 to 3 stay single-user: one profile, one plan, one set of keys for intervals.icu, Strava, Google and Telegram.
  `DEVICE_TOKENS` (ADR 0014) name devices, not people.
- Multi-user is a final stage of its own with its own ADR. It will bring accounts and sign-in, a user id on every table,
  per-user integration keys, profile and physics, and a check of the Strava terms for several people.
- Until then nothing is built "just in case": no user column, no account tables, no per-user abstractions.
- Cheap habits that keep the door open, without building anything: do not read a user's data from process-wide globals, keep
  the owner's settings in one place on the server, and keep ride and profile access behind the service layer, not in routes.

## Consequences

- Adding users later means a migration that gives every existing row to the owner's user, which is simple while one user exists.
- Spec §1 non-goals and `docs/roadmap.md` say so.
