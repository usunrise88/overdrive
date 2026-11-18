# Overdrive: guide for coding agents

Single-user indoor cycling system. The authoritative spec is `docs/spec.md`; cite sections as
"spec §4.7". Read §1, §3 and the section for the phase you are working in before changing code.

## Layout

| Path | Contents | Build |
|---|---|---|
| `core/` | Pure Kotlin (KMP, JVM target). **No Android or other platform imports.** Own Gradle build. | `cd core && ./gradlew check` |
| `android/` | Gradle build: `ble`, `data`, `ui-kit`, `app-ride`, `app-companion`. Uses `../core` via `includeBuild`. | `cd android && ./gradlew check assembleDebug` (Android SDK) |
| `server/` | TypeScript web service (Fastify, Zod). | `pnpm --filter @overdrive/server test` |
| `contracts/` | JSON Schemas + fixtures shared by Kotlin and TypeScript. | `pnpm --filter @overdrive/contracts test` |
| `design/`, `docs/` | Design deliverables; spec, architecture, ADRs. | |

Everything that needs no Android SDK: `make check`. Format everything: `make format`.
Layout rationale: `docs/adr/0001-repository-layout-and-toolchain.md`.

## Rules that cross components

- **Contracts first.** A wire format change (workout JSON, sync API payloads, ride record) starts in
  `contracts/` (schema + fixtures), then updates the Kotlin model in `core` and the TypeScript side
  in the same change. `core`'s `WorkoutContractTest` and `contracts`' tests must both pass.
- **Phases are gated.** Phase 1 = Android ride app MVP (spec §4). Do not build Phase 2 or 3 features
  (spec §5, §6) unless asked.
- **The ride app works fully offline** and has no dependency on Google Play Services (no Firebase,
  no Google sign-in). The tablet never talks to an external service directly, only to our server.
- **The overlay is performance-critical** (spec §4.6, §7.3): no continuous animation, recompose at
  most once a second at rest, no dropped frames while dragging. BLE and the ride engine run off the
  main thread.
- **Server secrets live in the server environment only**, never in the repo (`.env` is ignored).
- **Strava is write-only**: never read Strava data (its API terms forbid use in AI models). The LLM
  writes insight text from facts the service computed; it never calculates.
- Spec §8 is a first draft: names and fields are fixed during implementation, so update
  `docs/spec.md` and the schema when you settle them.

## Conventions

- Kotlin: ktlint via Spotless (`./gradlew spotlessApply`), explicit API mode in `core`.
- TypeScript: Biome (`pnpm format`), ESM, `.ts` import extensions, no enums or parameter properties
  (Node runs the sources directly via type stripping). Shared dependency versions are in the
  `catalog:` of `pnpm-workspace.yaml`.
- One version catalog for both Gradle builds: `gradle/libs.versions.toml`. Keep the two Gradle
  wrappers on the same version.
- Package/namespace root is `app.overdrive`.

## Working in a Claude Code cloud sandbox

- Node 24 is at `/opt/node24/bin`; pnpm comes through corepack (`corepack enable`).
- Maven Central rate-limits the sandbox's egress IP and answers parallel requests with random 404s.
  If Gradle reports `Could not find ...jar`, add a user-level init script (not committed):
  `~/.gradle/init.d/central-mirror.init.gradle.kts` with `beforeSettings { pluginManagement.repositories { maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") } }; dependencyResolutionManagement.repositories { maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") } } }`.
- The Android SDK is absent by default and needs `dl.google.com` to be allowed on the environment's
  network. There is no Docker daemon, so the server image is built only in CI.
