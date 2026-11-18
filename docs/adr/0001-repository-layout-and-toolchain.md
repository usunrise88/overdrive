# ADR 0001: Repository layout and toolchain

Status: accepted (bootstrap, 2026-11-17)

## Context

Overdrive is one product with several deliverables: two Android apps, a shared pure-Kotlin core, a
self-hosted web service, and wire formats that both sides must agree on (spec §3, §7, §8). One
person builds and operates all of it, partly with coding agents, so a change that crosses
components must be one commit and one CI run.

## Decision

**One repository, one directory per deployable or build unit.**

```
core/        Kotlin Multiplatform, JVM target — its own Gradle build
android/     Gradle build: ble, data, ui-kit, app-ride, app-companion; includes ../core
server/      TypeScript web service (pnpm workspace member)
contracts/   JSON Schemas and fixtures (pnpm workspace member)
design/      Design-system and mock-up deliverables
docs/        Spec snapshot, architecture, ADRs
gradle/      libs.versions.toml, the one version catalog both Gradle builds read
```

**`core` is a separate Gradle build, not a module of `android`.** The spec requires `core` to have
no Android imports (§7.1). A separate build makes that structural: it cannot see the Android Gradle
plugin or SDK, builds with only a JDK, and its tests run anywhere, including environments without
the Android SDK. `android` consumes it through `includeBuild("../core")`.

**Contracts are data, not shared code.** Kotlin and TypeScript each implement the formats. The
schema in `contracts/schema` is the source of truth and `contracts/fixtures` are golden files:
TypeScript validates them with the schema, Kotlin decodes them with its model, and both must
agree on every valid and invalid fixture.

**Toolchain**

| Area | Choice | Why |
|---|---|---|
| Server language | TypeScript on Node 24 | Proposed in spec §7.2; this makes it the working default. Revisit before Phase 2 if needed. |
| Node packages | pnpm 12 workspace, versions pinned with `packageManager` | Strict, fast, `catalog:` keeps shared versions in one place. |
| TS execution | Node's native type stripping in dev, `tsc` for builds | No extra runtime transpiler. Requires `erasableSyntaxOnly` (no enums or parameter properties). |
| TS lint/format | Biome | One tool for both. |
| TS tests | Vitest | |
| Server framework | Fastify, Zod for config and input | Small, typed, good test story (`inject`). |
| Kotlin / Android | Kotlin 2.4, Gradle 9.9 wrapper, AGP 9.5 (built-in Kotlin), Compose BOM 2026.09, version catalog | `minSdk` 31, `targetSdk` 36, `compileSdk` 37 (Compose requires it). |
| Kotlin lint/format | Spotless with ktlint | `./gradlew spotlessApply` formats. |
| CI | GitHub Actions: one job per component plus a server image build | |

## Consequences

- Two Gradle wrappers (`core/`, `android/`) must stay on the same Gradle version.
- `gradle/libs.versions.toml` sits outside both builds, so Dependabot cannot update it. It is bumped
  by hand.
- Changing a wire format touches `contracts/`, both implementations and their tests in one change.
- Anything that needs the Android SDK is verified only by the `android` CI job or on a machine with
  the SDK.
