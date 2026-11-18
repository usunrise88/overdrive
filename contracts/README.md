# contracts

Wire formats shared by every Overdrive component. Kotlin and TypeScript do not share code; they share
these files and both test against them.

```
schema/      JSON Schemas (draft 2020-12). The source of truth.
fixtures/    Golden files. valid/ must be accepted, invalid/ must be rejected, by every implementation.
src/         @overdrive/contracts: the schema plus a validator for the TypeScript side.
```

## Formats

| Format | Schema | Spec |
|---|---|---|
| Native workout (schema 1) | `schema/workout.schema.json` | §8.1 |
| Ride record (schema 1) | `schema/ride-record.schema.json` | §8.2 |
| Upload response (`{rideId, status}`) | `schema/sync-upload-response.schema.json` | §8.3, ADR 0014 |
| API error (`{error: {code, message}}`) | `schema/api-error.schema.json` | §8.3, ADR 0014 |
| Planned workouts (`GET /v1/workouts`) | `schema/workouts-response.schema.json` | §5.1, §8.3, ADR 0016 |
| Rider's profile (`GET /v1/profile`) | `schema/profile.schema.json` | §8.3, ADR 0018 |
| Profile patch (`PATCH /v1/profile`) | `schema/profile-patch.schema.json` | §8.3, ADR 0018 |

The remaining sync API payloads and the MCP tool inputs (spec §8.3–§8.4) get schemas here as they are fixed during
implementation. The sync outbox of the ride app (ADR 0012) is a local journal, not a wire format, and has no schema.
What an upload sends is two requests (ADR 0014): `PUT /v1/rides/{rideId}` with the ride record as the body (its schema is
above) and `PUT /v1/rides/{rideId}/fit` with the FIT file's bytes (`application/vnd.ant.fit`, not JSON, so no schema; its
fixtures are below). The service answers with the upload response, or on any failure with the API error, whose `code` is a
closed list of stable names (each with its HTTP status in `API_ERROR_STATUS`).

`fixtures/sync/` holds the golden files of those two answers, `upload-response/{valid,invalid}` and
`api-error/{valid,invalid}`, run by `test/sync.test.ts` and, in Kotlin, by `SyncApiContractTest` (`SyncApiJson`, strict like
the schemas). The ride id syntax the service accepts in a path (`RIDE_ID_PATTERN`) is stated in the response schema; the ride
record's own schema only asks for a non-empty id.

`fixtures/plan/workouts-response/{valid,invalid}` hold the golden files of `GET /v1/workouts?from=YYYY-MM-DD&to=YYYY-MM-DD`
(ADR 0016): the range (at most 62 days, both ends included), `generatedAt` (when the plan was last read from its source, or
null), `stale` (the last refresh failed: the list is the last known one), `configured` (false: no plan source, the list is
empty) and `workouts`, each `{ id, date, name, source, workout }` with the native workout inside. The schema refers to
`workout.schema.json` by file name; what a schema cannot say (real calendar dates, the range's length, unique ids, `workout.id`
equal to the entry's `id`, an unconfigured service being empty and not stale) is checked by `validateWorkoutsResponse` here and
by `PlanApiJson` in Kotlin (`PlanApiContractTest`), against the same fixtures. The request also takes `refresh=true` (read the
source first, wait at most ten seconds); that is a query parameter and has no schema. A malformed range is `400 invalid_range`.

`fixtures/profile/` holds the golden files of the rider's profile (ADR 0018): `profile/{valid,invalid}` for the profile itself (FTP 30 to
2000 or null, six strictly increasing zone bounds, minutes per weekday, at most ten goals, the physics constants under the names of
the ride engine's `PhysicsSettings`, every key always present); `patch/{valid,invalid}` each hold `{ before, patch, after }` (valid: applying
the merge-patch-like `patch` to `before` must give exactly `after`; invalid: it must be refused, either as a patch or as the profile it
would make, and `before` is untouched); and `zone-ranges.json`, the seven zones in watts for an FTP and its six bounds, with the zone of
every whole-watt power derived from them (Kotlin also checks Coggan's against `PowerZone.of`). `contracts/test/profile.test.ts`,
Kotlin's `ProfileContractTest` (`ProfileApiJson`, strict like the schemas) and the service's `profile.test.ts` (through HTTP) run the
same files. The new API error code `invalid_profile` (422) has its fixture under `sync/api-error/valid/`.

`fixtures/fit/` holds two FIT files for the upload's tests: `fit-golden-3s.fit`, the 376 bytes `FitWriter` writes for
`rides/valid/fit-golden-3s.json` (the worked example of `docs/protocols/fit.md`; Kotlin's `FitFixturesTest` regenerates it
and compares), and `minimal.fit`, the smallest file the service's framing check accepts (the 12-byte header, four bytes of
records, the file CRC), which holds no real messages. Both are synthetic.

`fixtures/rides/` holds the ride record's golden files: `valid/*.json` must be accepted and `invalid/*.json`
rejected by every implementation, including the structural rules a schema cannot state (samples run 1, 2, 3, …; laps
tile the ride; the summary's duration is the number of samples). Kotlin's `RideRecordContractTest` and
`test/ride-record.test.ts` check them. Four valid ones are generated by Kotlin (`minimal-free-ride`, `empty-ride`,
`fit-golden-3s`, the five-minute `synthetic-5min`); the test explains how to regenerate them. All are synthetic.

`fixtures/zwo/` holds golden files for the Zwift ZWO import (spec §4.5, §8.1): each `valid/*.zwo` has a
`*.expected.json` beside it, the native workout it must convert to, and every `invalid/*.zwo` must be
rejected. Kotlin's `ZwoFixturesTest` checks them; any other ZWO importer (the web service, later) should too.

`fixtures/ble/` holds raw Bluetooth session recordings in the text format of `docs/protocols/ftms-hr.md` §7.1 (spec §7.5,
ADR 0009 §6): a trainer and a strap file for each of two synthetic rides (`erg-workout`, `dropouts`). They are not a wire
format of ours and have no schema; they are **generated** by running a ride over the fake Bluetooth with the recorder on
(`BleSessionFixturesTest`, regenerate with `cd core && ./gradlew jvmTest -PregenerateBleFixtures=true`, then review the
diff), and the same test replays each pair through the real adapters and engine into the ride that produced it. They hold
synthetic bytes, no addresses and no real person's heart rate. Six more, `click-*.click.session.txt` (buttons, real-puck, dropout, locked,
unlock, silent), are sessions of a fake Zwift Click (`docs/protocols/click-v2.md`, ADR 0011) in the same format, generated and
replayed the same way (`ClickSessionFixturesTest`); the firmware revision is `*` and the handshake key is zeroed, as for any
recording. **Never add a real recording here:** the repository is public.

## Changing a format

1. Edit the schema and add fixtures: a valid one for anything newly allowed, an invalid one for
   anything newly forbidden.
2. `pnpm --filter @overdrive/contracts test` — the TypeScript side validates every fixture.
3. `cd core && ./gradlew jvmTest` — the Kotlin model must agree on every fixture
   (`WorkoutContractTest`). Update the model until it does.
4. Bump the `schema` version if existing files would stop validating.

Fixtures are excluded from Biome formatting so they can stay hand-laid-out.
