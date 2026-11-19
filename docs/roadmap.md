# Roadmap

Phase 1 (spec §4) was cut into four stages. **Every stage ends with a release** (see `CLAUDE.md`,
Process): a tagged, signed APK the owner can install and try. Order follows risk: the things that
can only be learned on the real tablet come first, the hardware-dependent things only once the
rest is solid.

**Where we are: M1 to M4 are done, and Phase 2 is complete.** `v0.1.0` is the MVP: everything automatic is green and
nothing has touched real hardware. Phase 2 (P2-1 to P2-5) is built and merged, and `v0.2.0` is ready to be cut by the
owner once CI is green. Phase 3 is built (see below). Multiple users (ADR 0015) comes last. The bench (below) is still open.

| Stage | Release | Goal | What the owner tests | State |
|---|---|---|---|---|
| **M1 Design and overlay** | `v0.1.0-alpha.1` | Design system and mock-ups (§7.4); ride app with a **simulated** trainer, strap and Click: ride screen, overlay (compact and detailed, draggable, snaps, remembered), foreground service | Overlay over a real video app on the tablet: legibility, drag smoothness, ZUI background behaviour, battery (§9 "ZUI background limits") | done |
| **M2 Engine and workouts** | `v0.1.0-alpha.2` | Ride engine, physics, virtual gears, workout player (steady, ramp, repeat, free; ERG and simulation), auto-pause, crash recovery, FIT export. Still simulated devices | A full simulated workout; the exported FIT opens in intervals.icu with power, heart rate, cadence and laps (acceptance 2, 5, 6) | done |
| **M3 Real devices** | `v0.1.0-alpha.3` | BLE transport, FTMS trainer, heart-rate strap, pairing and reconnect, raw BLE session recorder. Built against the specs, open-source references and replayed sessions | *Deferred to the bench:* ride on the D500 with the Coospo strap: control, dropouts, RR intervals, gear feel (§9) | done (built against the specs, not run on hardware) |
| **M4 Handlebar and MVP** | `v0.1.0` | Click v2 and the gesture layer, all ten actions, sync stub and outbox, settings, acceptance criteria 1–7 (§4.10), bench checklist | *Deferred to the bench:* the whole acceptance list, on the bike | done (M4-1 core in ADR 0011, M4-2 app in ADR 0012; `v0.1.0` is published, 2026-11-18) |

## The bench, then Phase 2

1. **The bench** (owner): install the signed `v0.1.0` APK and run `docs/bench-checklist.md` top to bottom, nine sessions. It
   confirms or breaks each acceptance criterion on the device (`docs/phase1-acceptance.md`), answers the open checks of spec §9 and
   the numbered questions of the protocol notes, and produces the Bluetooth session logs that fix the codecs. Defects go to
   `v0.1.x`; a switch in Settings, Developer · Click, or one default in `FtmsOptions`, `HeartRateOptions`, `ClickOptions` or
   `FitOptions` is most of the likely fixes.
2. **Phase 2** (spec §5; the stages are in the table below, ADRs 0014 to 0020): built and merged. Phase 3 (statistics, the
   companion app) follows.

## Phase 2 stages

Phase 2 (spec §5) is cut into five stages, done in this order. The stages do not each end in an alpha: the owner decided
on 2026-11-18 that Phase 2 has one release, `v0.2.0`, after the last stage.

| Stage | Goal | State |
|---|---|---|
| **P2-1 Rides leave the tablet** (ADR 0014, 0016) | The intake API on the server, and the tablet's upload of each ride's record and FIT, both idempotent. Then the FIT goes on to intervals.icu with no manual step | done (merged) |
| **P2-2 Planned workouts reach the tablet** (ADR 0016, 0017) | A plan cache from intervals.icu; `GET /v1/workouts`; the list on the ready screen; starting a planned workout links the ride to it | done (merged) |
| **P2-3 MCP server and planning** (ADR 0018) | The MCP server (spec §5.2) with OAuth, the eight Phase 2 tools of §8.4, and `GET`/`PATCH /v1/profile` | done (merged) |
| **P2-4 Google Calendar and Telegram** (ADR 0019) | Planned workouts pushed to Google Calendar (§5.3); the Telegram bot (§5.4) | done (merged) |
| **P2-5 Strava upload and description** (ADR 0020) | Rides uploaded to Strava, with their description written (§5.5, §5.6). Strava is write-only | done (merged) |
| **Release `v0.2.0`** | Notes from `docs/releases/TEMPLATE.md`. One release for all of Phase 2, no alphas (owner, 2026-11-18). The owner publishes it in the GitHub web UI once CI on the commit is green (`docs/releasing.md`, section 11) | ready to be cut by the owner |

## Last: multiple users

Decision (owner, 2026-11-18, ADR 0015): multi-user support is the **final stage of the project**, after Phase 3. Until then the
system is single-user (spec §1): one profile, one plan, one set of integration keys, device tokens that name devices, not people.
It will need accounts and sign-in, a user id on every table, per-user integration keys and profile, and a review of the Strava
terms for more than one person. Nothing of it is built earlier, and no earlier stage adds a user column "just in case".

## Hardware verification is deferred

Decision (owner): M3 and M4 are built without access to the trainer, strap or Click. They are tested on
the owner's bench once it exists: an environment with `adb` to the tablet and the devices in reach.
Until then correctness rests on the Bluetooth specifications, open-source reference implementations
(for the Click, BikeControl, §9 risk 1) and a fake GATT transport that replays recorded or synthetic
sessions (§7.5). The honest consequences:

- `v0.1.0-alpha.3` and `v0.1.0` are cut when every automated criterion is green. Their release notes
  say plainly which parts have not touched real hardware.
- Defects found on the bench are fixed in `v0.1.x`; `docs/phase1-acceptance.md` marks which criteria
  have been confirmed on device.
- M4 delivered `docs/bench-checklist.md`: what to check, in which order, which logs to capture (nine sessions, one document).

## Why this order

- The overlay and ZUI behaviour are the biggest unknown that is independent of hardware, and the
  design has to exist before UI code (§7.4). M1 tests both on the tablet with no bike.
- Everything in M2 is pure logic in `core`, fully testable in CI with simulated devices (§7.5).
- Real BLE (M3) and the Click protocol (M4) are the riskiest and need the owner's hardware, so they
  come after the app around them is solid. M3 ships a raw-session recorder so the owner's first
  rides produce the data to fix the codecs without a bench machine.

## Release mechanics

Pre-releases are `v0.1.0-alpha.N`; the MVP is `v0.1.0`. Later phases continue from there
(`v0.2.0` for Phase 2, with no alphas). Mechanics and checklist: `CLAUDE.md`, Process.

## Phase 3 stages (spec §6)

**Where we are: Phase 3 is released as `v0.3.1` (2026-11-19); `v0.3.2` adds the launch animation; the long checks run after the releases; their fixes go to `v0.3.3`.**
There was no `v0.3.0`: `v0.3.0-rc.1` was the candidate, and the owner asked for the final release to carry the graphics (ADR 0030) and
to run the long checks after it, so the final version is `0.3.1`.

Order of work, 2026-11-18. Stages merge into the development branch without a release; the phase ends with a release candidate
`v0.3.0-rc.1`, which the owner tests while the orchestrator runs the long checks, then the final `v0.3.0` (`CLAUDE.md`, Process).
Tasks live in Lific, project OVD.

| Stage | Goal | Task | State |
|---|---|---|---|
| Interfaces | The phone companion's design: navigation, screens, charts, copy, mock-ups (`design/companion.md`) | | done |
| P3-1 Metrics engine | Load, fitness/fatigue/form, power curve, eFTP suggestion, aerobic drift, W′ balance; `GET /v1/metrics`, ride detail, MCP `get_fitness` (§6.3) | OVD-12 | done (merged, ADR 0022) |
| P3-2 Wellness | `POST /v1/wellness`, `WellnessSource`, intervals.icu wellness, baselines, ready/caution/rest, other activities from the watch (§6.2, §6.4, §6.7) | OVD-13 | done (merged, ADR 0023) |
| P3-3 Insights and alerts | Written notes from computed facts (configurable LLM endpoint), morning/deviation/drift alerts in Telegram, MCP tools, coach topics (§6.5, §6.6, §12.3) | OVD-14 | done (merged, ADR 0025) |
| P3-4a Companion app | Phone app: five screens, service connection, charts, the FTP answers (§6.1) | OVD-15 | done (merged, ADR 0024) |
| P3-4b Health Connect reader | Health Connect records from the phone, the outbox, background reading, the Wellness screens (§6.1, §6.2, §12.5) | OVD-15 | done (merged, ADR 0027) |
| P3-4c Repeats and FIT share on the phone | Repeat page, FIT share target with its outbox (§11.7, §11.8) | OVD-15 | done (merged, ADR 0028) |
| P3-5 FTP confirmation | The eFTP suggestion is confirmed or dismissed by the owner (companion, Telegram, MCP); only then do zones move (§6.3) | OVD-16 | not started |
| P3-7 Repeats and outdoor rides | The same-session comparison (hash of the workout structure, heart rate at the same power, trends, `GET /v1/repeats`, `previous` for the overlay, MCP); outdoor rides from a FIT file (own decoder, `POST /v1/activities/fit`, heart-rate load as `other`, duplicate guard, forwarded to intervals.icu, Telegram document) (§11.7, §11.8) | | done (merged): server ADR 0026, companion ADR 0028; the overlay line is `v0.4.0` |
| P3-6 Release candidate | `v0.3.0-rc.1`, then the long checks while the owner tests, then `v0.3.0` | OVD-17 | `v0.3.0-rc.1` cut; the final release is `v0.3.1`, with the long checks after it (owner's decision, 2026-11-19) |

P3-5 is not marked done here: the companion has the Keep and Use answers to a suggested FTP (ADR 0024, section 3), but the stage
was not re-checked against its task (OVD-16) when this table was written.

Owner's open design questions, answered with the designer's defaults until the owner says otherwise: 14 nights before a recovery
verdict; charts open on 6 months (fitness, power) and 4 weeks (wellness); form words Fresh/Neutral/Training hard/Very fatigued at
+5/−10/−30; a dismissed FTP suggestion stays hidden until the estimate moves by 3 W; the phone sends no notifications of its own
(Telegram only).

## Spec additions of 2026-11-18 (spec §11 and §12)

The owner added thirteen features to the spec; each names the phase it belongs to. Decision (owner, 2026-11-18): they are built
**before** any macOS application (which is not in the spec yet; nothing about it is planned until these are done). Those that belong
to Phase 3 join the Phase 3 stages above and ship in `v0.3.0`; those that belong to Phases 1 and 2 follow as their own phase,
`v0.4.0`, with the same rule: a release candidate first, then the release.

| Addition (spec) | Spec phase | Where it is built |
|---|---|---|
| Repeat-session comparison (§11.7) | 3 | P3-7 (detection and comparison on the server: **done**, ADR 0026) and P3-4 (page in the companion; the overlay line is in `v0.4.0`) |
| Outdoor rides from the bike computer (§11.8) | 3, needed from March 2027 | P3-7 (FIT import, heart-rate load, duplicate guard, the Telegram bot receiving a FIT: **done**, ADR 0026) and P3-4 (share target in the companion) |
| Post-ride analysis that hands a topic to the coach (§12.3) | 3 | P3-3 |
| Body weight from Health Connect (§12.5) | 3 | P3-2 (records, 7-day average, `get_wellness`) and P3-4 (trend on the Wellness screen); system mass in the tablet's physics in `v0.4.0` |
| ERG stall guard (§11.1) | 1 | `v0.4.0`, stage A1 (tablet) |
| Heart-rate hold, step type `hr` (§11.2) | 1 | `v0.4.0`, stage A1 (workout contract, player, MCP) |
| Overlay expands around step changes (§11.3) | 1 | `v0.4.0`, stage A1 (tablet) |
| Routes with elevation (§11.4) | 2 | `v0.4.0`, stage A2 (service routing engine, MCP tools, trainer simulation, FIT positions) |
| Easier variant of a planned workout (§11.5) | 2 | `v0.4.0`, stage A2 |
| Coach notebook (§11.6) and methodology section (§12.4) | 2 | `v0.4.0`, stage A2 (MCP tools, versioning, companion read-only view) |
| Agent replans after a missed session (§12.2) | 2 | `v0.4.0`, stage A2 (plan events, MCP tools, Telegram note) |
| Live coach: spoken cues in real time (§12.1) | 2 | `v0.4.0`, stage A3 (streaming from the tablet, trigger rules, LLM, TTS) |

## Phase 4 (`v0.4.0`) stages

Started 2026-11-19 on the owner's word. Decisions (owner, 2026-11-19): the road-routing engine is **BRouter**; the TTS service is the
owner's own (`TTS_BASE_URL`, `TTS_MODEL`, `TTS_VOICE`, `TTS_API_KEY` are in the server's `.env`; an OpenAI-compatible
`/v1/audio/speech` answering `audio/mpeg`, about 0.5 s to the first byte). Still the owner's: the region whose BRouter tiles are downloaded,
and whether the live coach comes before the macOS application (in this plan it is the last stage). The order inside a stage is the order in
the table; stages merge without a release, the phase ends with `v0.4.0-rc.1` and then `v0.4.0`.

| Stage | Goal | Task | State |
|---|---|---|---|
| **A1a Resume after a pause** (spec §4.2, §4.5) | After a pause the resistance and the cadence come back at once (the owner's report, 2026-11-19: 10 to 15 s of pedalling against nothing) | OVD-24 | done (merged): ADR 0032; not verified on the bike, the cause is still a hypothesis until the owner's Bluetooth log |
| **A1b Stall guard and heart-rate hold** (§11.1, §11.2) | The core: the guard, the `hr` step in the contract, the player and the controller, MCP and the repeats hash | OVD-19 | done (merged): ADR 0033; nothing ridden yet, every number is a setting to tune on the bike |
| **A1c The overlay and the settings** (§11.3, §11.7, §12.5) | Expands around step changes, shows the guard and the heart-rate step, the previous-attempt line, system mass with the body weight | OVD-19 | done (merged): ADR 0034; nothing seen on a tablet yet |
| **A2a Notebook and replanning** (§11.6, §12.2, §12.4) | The coach notebook (versions, rollback, Methodology), plan events and their tools, the Telegram note, the companion's read-only view | OVD-20 | server side done (merged): ADR 0035, migration 0010; the companion's view is not built |
| **A2b-1 Routes and the variant: the service** (§11.4, §11.5) | BRouter beside the service (container and tiles done: deploy guide section 20), `create_route` in three modes, the variant of a workout and its lifecycle, route sessions in repeats | OVD-20 | done (merged): ADR 0036, migration 0011 |
| **A2b-2 Routes and the variant: the tablet** (§11.4, §11.5) | The Kotlin models, route choice, grade in the trainer's simulation, positions in the FIT, the variant choice at the start, the companion's notebook view | OVD-20 | |
| **A3 Live coach** (§12.1) | Snapshots from the tablet, trigger rules, the LLM line, the owner's TTS, audio and text on the tablet, the cue log | OVD-21 | |
| **Release candidate `v0.4.0-rc.1`**, then **`v0.4.0`** | Long checks while the owner rides it | | |

