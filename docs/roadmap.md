# Roadmap

Phase 1 (spec §4) is cut into four stages. **Every stage ends with a release** (see `CLAUDE.md`,
Process): a tagged, signed APK the owner can install and try. Order follows risk: the things that
can only be learned on the real tablet come first, the hardware-dependent things only once the
rest is solid.

| Stage | Release | Goal | What the owner tests |
|---|---|---|---|
| **M1 Design and overlay** | `v0.1.0-alpha.1` | Design system and mock-ups (§7.4); ride app with a **simulated** trainer, strap and Click: ride screen, overlay (compact and detailed, draggable, snaps, remembered), foreground service | Overlay over a real video app on the tablet: legibility, drag smoothness, ZUI background behaviour, battery (§9 "ZUI background limits") |
| **M2 Engine and workouts** | `v0.1.0-alpha.2` | Ride engine, physics, virtual gears, workout player (steady, ramp, repeat, free; ERG and simulation), auto-pause, crash recovery, FIT export. Still simulated devices | A full simulated workout; the exported FIT opens in intervals.icu with power, heart rate, cadence and laps (acceptance 2, 5, 6) |
| **M3 Real devices** | `v0.1.0-alpha.3` | BLE transport, FTMS trainer, heart-rate strap, pairing and reconnect, raw BLE session recorder | Ride on the D500 with the Coospo strap: control, dropouts, RR intervals, gear feel (§9) |
| **M4 Handlebar and MVP** | `v0.1.0` | Click v2 and the gesture layer, all ten actions, sync stub and outbox, settings, acceptance criteria 1–7 (§4.10) | The whole acceptance list, on the bike |

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
