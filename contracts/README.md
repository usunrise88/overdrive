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

The ride record, sync API payloads and MCP tool inputs (spec §8.2–§8.4) get schemas here as they are
fixed during implementation.

`fixtures/zwo/` holds golden files for the Zwift ZWO import (spec §4.5, §8.1): each `valid/*.zwo` has a
`*.expected.json` beside it, the native workout it must convert to, and every `invalid/*.zwo` must be
rejected. Kotlin's `ZwoFixturesTest` checks them; any other ZWO importer (the web service, later) should too.

## Changing a format

1. Edit the schema and add fixtures: a valid one for anything newly allowed, an invalid one for
   anything newly forbidden.
2. `pnpm --filter @overdrive/contracts test` — the TypeScript side validates every fixture.
3. `cd core && ./gradlew jvmTest` — the Kotlin model must agree on every fixture
   (`WorkoutContractTest`). Update the model until it does.
4. Bump the `schema` version if existing files would stop validating.

Fixtures are excluded from Biome formatting so they can stay hand-laid-out.
