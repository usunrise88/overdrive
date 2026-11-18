# Roadmap

Phase 1 (spec §4) was cut into four stages. **Every stage ends with a release** (see `CLAUDE.md`,
Process): a tagged, signed APK the owner can install and try. Order follows risk: the things that
can only be learned on the real tablet come first, the hardware-dependent things only once the
rest is solid.

**Where we are: M1 to M4 are done. The bench is next, then Phase 2.** `v0.1.0` is the MVP: everything automatic is green and
nothing has touched real hardware.

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
2. **Phase 2** (spec §5; handoff and a proposed split into stages in `docs/phase2-kickoff.md`): the web service with planned workouts, the MCP server, the calendar,
   the Telegram bot, ride upload (the outbox of Phase 1 is where it starts) and the ride app's changes. It begins, as every
   wire-format change does, in `contracts/`: the sync API's schemas and fixtures come first. Phase 3 (statistics, the companion
   app) follows.

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
(`v0.2.0-…` for Phase 2). Mechanics and checklist: `CLAUDE.md`, Process.
