# Architecture

Summary of spec §3. The spec ([spec.md](spec.md)) is authoritative; this page adds the diagram and
maps components to directories.

```mermaid
flowchart LR
  subgraph Devices
    trainer["Van Rysel D500<br/>(FTMS)"]
    strap["Coospo HR7<br/>(HR)"]
    click["Zwift Click v2"]
    watch["OnePlus Watch 3<br/>(OHealth)"]
  end

  subgraph Ours["Our software"]
    ride["Ride app<br/>tablet"]
    companion["Companion app<br/>phone"]
    service["Web service<br/>self-hosted"]
  end

  agent["AI agent"]
  intervals["intervals.icu"]
  gcal["Google Calendar"]
  strava["Strava"]
  telegram["Telegram"]
  llm["LLM endpoint"]

  trainer & strap & click -- BLE --> ride
  watch -- "Health Connect" --> companion
  ride -- "FIT files, sync" --> service
  companion -- "wellness, stats" --> service
  agent -- MCP --> service
  service <--> intervals
  service --> gcal
  service --> strava
  service <--> telegram
  service --> llm
```

Devices talk only to the apps, the apps talk only to the web service, and the service is the single
point that reaches the external services. The ride app works fully offline.

## Components and where they live

| Component | Directory | Stack |
|---|---|---|
| Ride app (tablet) | `android/app-ride` | Kotlin, Compose |
| Companion app (phone) | `android/app-companion` | Kotlin, Compose |
| Shared pure-Kotlin core | `core` | Kotlin Multiplatform (JVM target) |
| BLE transport | `android/ble` | Android library |
| Ride files, sync outbox (`FileOutbox`, Phase 1); the sync client is Phase 2 | `android/data` | Android library |
| Design system | `android/ui-kit` (design sources in `design/`) | Compose |
| Web service | `server` | TypeScript on Node, PostgreSQL |
| Wire formats and shared fixtures | `contracts` | JSON Schema, JSON fixtures |

## Dependency rules

```
app-ride, app-companion → ui-kit, data, ble → core
server → contracts            (core and the server never import each other)
```

- `core` imports no Android or other platform API. It is a separate Gradle build so the rule is
  enforced by the build, not by discipline.
- Kotlin and TypeScript share no code. They share **contracts**: JSON Schemas plus fixtures that
  both sides test against. A format change starts in `contracts/`.
