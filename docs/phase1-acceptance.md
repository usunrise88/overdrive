# Phase 1 acceptance

How each acceptance criterion of spec §4.10 gets verified, and in which release. "Auto" means CI
proves it without hardware; "Owner" means it needs the owner's tablet, bike or accounts and is a step of
`docs/bench-checklist.md` (step numbers below are that document's) and is listed in the release notes as
something to try. **At the end of Phase 1 every "Auto" cell is green and no "Owner" cell has been run.**

| # | Criterion (spec §4.10) | Auto | Owner on device | Stage |
|---|---|---|---|---|
| 1 | A 60-minute free ride completes with video playing full screen and the overlay visible throughout | Simulated 60-minute session runs to completion with no state loss. **Overlay (M1-B, auto):** the service shows exactly one window while the ride is live and the app is out of sight, hides it when the app returns, never flashes on a short leave, and stops after the summary (`OverlayServiceTest`) | 60 minutes over a real video app on the tablet; overlay stays visible, foreground service survives ZUI (bench 6.8 to 6.13) | M1 (simulated + window), M4 (real) |
| 2 | A workout file with steady, ramp and interval steps plays in ERG and holds target power | Workout player drives the simulated trainer through every step type, target power asserted per step (`SweetSpotEngineTest`, M2a). **Core (M3-1, auto):** the same real `RideEngine` drives a fake FTMS trainer through the real adapter and the real codecs (`BleRideTest`): the ERG target reaches the Control Point as `05 lo hi` (vectors CP3, CP4, `VectorsTest`), the fake trainer's power follows it, each step's target is held, a paused step sends 0 W, a rejected or unanswered command is retried by the engine, and a control loss reported by Status is taken back and the last target sent again (`FtmsTrainerDeviceTest`). A workout ride recorded through the session recorder replays into the identical ride record (`BleRecordReplayTest`, fixtures in `contracts/fixtures/ble/`). The fake is written from the same notes as the adapter, so this proves consistency with `docs/protocols/ftms-hr.md`, not with the D500 | Same workout on the D500; power holds the target (bench 3.3, 3.3b, 3.10) | M2 (auto), M3 (owner) |
| 3 | All ten Click actions work from the right controller with the app in the background | **Core (M4-1, auto):** the gesture layer turns 5 buttons × press and hold into the 10 actions (`GestureDetectorTest`: hold threshold 500 ms, volume repeat 150 ms, a tap fires on release and a hold once at the threshold, the volume's first step on press, two buttons held, a lost release, a stalled or stepping clock, rapid taps, bounce), the router carries them out (`ActionRouterTest`: in an ERG step the gear actions change intensity by 5 points, the pause toggle works only while riding or paused), and **one test fires every one of the ten actions from a fake Click, through the Click adapter, the mapping, the gestures and the router into the real `RideEngine` over a fake trainer, asserting each effect** (`ClickRideTest`: gear and intensity, next and previous interval, the pause and the resume, play and pause, volume up and down with their repeats). The Click dropping and coming back mid-ride, a locked Click that never delivers (the ride record is equal to the ride without a Click) and a learned mapping are in the same test. Codec vectors derived from `docs/protocols/click-v2.md`, fixtures in `contracts/fixtures/ble/click-*.click.session.txt`. The fake Click is written from the same notes as the adapter, so this proves consistency with `docs/protocols/click-v2.md`, not with the Click. **App (M4-2, auto):** the Click is a long-lived device owned by the container, connected at launch, never closed by a ride or by a change of the other devices, replaced cleanly, reconnected when Bluetooth returns, and a button held on a replaced Click is released (`PairedClickTest`, `AppContainerClickTest`); **the whole chain through the real container with no activity ever created**: a press of a fake Click reaches the real `RideEngine` over a fake trainer (gears, pause and resume) and the recording media keys (`AppContainerClickTest`); **all ten actions** fired from their gestures in virtual time (`HandlebarServiceTest`) and through the container's router; **one routing rule** for the ride screen's buttons and the Click, equal in every ride state and in ERG and simulation steps (`ActionRoutingTest`, `ActionRouterTest`); Learn buttons (happy path, combo, duplicate, cancel, reset, persistence, a corrupt stored value, gestures suspended while it runs, ended when a ride goes live: `ButtonLearningTest`, `ClickSettingsTest`, `AppContainerClickTest`); the Click's row and the two unlock notices in every state (`BleDevicesModelTest`, `ClickUiTest`); the developer settings sanitised and effective (`ClickSettingsTest`, `PairedClickTest`); the Android transport writes without response and the scanner reads manufacturer data (`ble` tests, `ClickOverAndroidTransportTest`). The fake Click and the fake radio are written from the same notes as the code, so this proves consistency with them, not with the Click or the tablet's Bluetooth stack | Each action from the real Click with the video app in front (bench 5.6, 5.7, 7.1); the plain handshake without an unlock (bench 5.2); the buttons' bits (bench 5.5); the process surviving behind a video app and with the screen off, and the media key reaching the video app (bench 5.6, 5.11) | M4-1 (core, auto), M4-2 (app, auto), owner |
| 4 | Pulling the trainer's power mid-ride pauses the ride; it resumes after reconnect with no data lost | Engine: dropout pauses, reconnect resumes, samples recorded as gaps, nothing lost (`RideEngineStateTest`, `SweetSpotEngineTest`, M2a). **Core (M3-1, auto):** over the fake Bluetooth a status 133 drop mid-ride pauses the ride, the supervisor closes the dead transport and opens a fresh one, backs off 1 s, 2 s, 4 s … up to 30 s, and the first notification after the reconnect resumes the ride with the gap recorded as paused samples without power (`LinkSupervisorTest`, `FtmsTrainerDeviceTest`, `BleRideTest`, `BleRecordReplayTest` with a dropout, a failed reconnect and a strap off the chest). Bluetooth switched off is a state, not a failure: nothing is tried and the link returns at once when it is back. The strap's dropout is only a gap (`HeartRateStrapDeviceTest`). Backoff numbers and the establish timeout are guesses until the bench | Unplug the trainer mid-ride (bench 3.11, 3.12) | M2 (auto), M3 (owner) |
| 5 | Killing the app mid-ride and relaunching recovers the ride | **Core (M2b-1, auto):** per-second append-only log with a checksum per line (`RideLogRecorder`, ADR 0007); a log cut at every byte, zero-filled, with a garbage tail or one flipped byte recovers exactly the records before the damage (`RideLogFuzzTest`); a real process killed with SIGKILL mid-write recovers (`FileByteStorageTest`); the engine's ride is recovered identically (`RideEngineLogTest`). **App (M2b-2, auto):** the log is written on its own thread, so a stalled `fsync` never delays the engine (`RideHostDiskTest`); a simulated ride cut off mid-way (the process "dies", its files stay) is found by a new `AppContainer` over the same folder, offered on the Ready screen, and saved: the recovered samples are exactly the first ones the engine produced (`RideHostPersistenceTest`, `AppContainerRecoveryTest`); finalize is atomic, a stop after any step is completed on the next start (`RideStoreTest`); rides under 60 s are dropped, a finished log without its export is completed silently, a log that cannot be written is one notice and the ride goes on (`RideLibraryTest`, `StorageUiTest`) | Force-stop mid-ride (`adb shell am force-stop app.overdrive.ride`), relaunch, press **Save** in the notice: the ride's duration matches the time of the stop within a few seconds (bench 8.1 to 8.5) | M2 (auto), M3 (owner) |
| 6 | The exported FIT file opens correctly in intervals.icu with power, heart rate, cadence and laps | **Core (M2b-1, auto):** FIT written by our own writer and read back by an independent test reader written from the format facts: header and file CRC, sizes, message order, every field of records, laps, session, activity and hrv against the ride record, sport and sub-sport, gaps as invalid values (`FitWriterTest`), and byte for byte against the worked example of `docs/protocols/fit.md` section 6 (`FitGoldenTest`). Garmin's decoder is **not** part of CI: it is a local, opt-in oracle (`GarminFitOracleTest`, run with `-PfitOracleJar`, ADR 0007). **App (M2b-2, auto):** a finished simulated ride leaves `<id>.json` (equal to the engine's record) and `<id>.fit` (header, data size and CRC-16 checked by a local implementation, byte-equal to the writer's output for that record and the device's UTC offset) and no stray log; the finished screen's Share FIT file and Save to folder go through a `FileProvider` that serves only `files/rides/` and the system file picker (`RideHostPersistenceTest`, `StorageUiTest`, `RideFilesTest`) | **Share FIT file** or **Save to folder** on the finished screen, then import into intervals.icu (bench 7.2 to 7.6) | M2 (auto + owner) |
| 7 | Dragging the overlay drops no frames at the tablet's refresh rate | **Auto (M1-B):** a drag moves the window through `updateViewLayout` only, once per distinct position, with no recomposition of the content and no state write until it ends; bounds read once per gesture; 100 snapshots with the same numbers recompose nothing (`OverlayRecompositionTest`, `OverlayWindowControllerTest`, `OverlayUpdatesTest`). These prove the drag path is cheap, not that frames are not dropped | `dumpsys gfxinfo` and `am trace-ipc` while dragging on the tablet at each refresh rate (bench 6.5, 6.6) | M1 (auto: no work per move; owner: frame time) |

## What the overlay stage (M1-B) proves without hardware, and what it leaves for the bench

Automatic, in `make android-check`: the window's flags (no `FLAG_SECURE`, no `FLAG_NOT_TOUCHABLE`), the snap, clamp and
persistence maths, the tap, long press and drag classifier, the lifecycle and stop rules, the once-a-second filter with the
rider's actions shown at once, one window per live ride and none otherwise, tap and position written to the settings, the
permission rows. Owner on the tablet: whether Android 15 ZUI lets the sideloaded app hold "Display over other apps", whether
the long press brings the app to the front from a video app, frame time while dragging (criterion 7), DRM video and screen
recordings, blur, battery (spec §7.3) and the service surviving an hour. All of them are in `docs/bench-checklist.md`
(items 1.3 to 1.5 and 6.1 to 6.17) and in the "Unverified on hardware" table of `docs/adr/0006-overlay-service.md`.

## What the Bluetooth stage (M3-2) proves without hardware, and what it leaves for the bench

Automatic, in `make android-check` (criteria 2 and 4 on the Android side; ADR 0010): the Android GATT transport against core's
strict fake radio and a hand-scripted platform (contract, ordering, late and missing callbacks, busy, release), the real device
adapters and supervisor over that transport reproducing core's scenarios (ERG workout, trainer dropout and status 133, strap
dropout, RR); the radio and permission mapping; the remembered devices (replace, forget, survive a restart, damaged file);
**devices owned by the container and outliving every ride session**; the ride host over real devices (an ERG workout, a trainer
dropout that pauses and resumes the ride, a strap dropout that is only a gap, **no trainer means no ride and nothing
simulated**, a ride that has started is never replaced, pairing refused while riding); the Developer switch; every Devices
state and the Ready notice as screenshots and taps; the foreground-service type decision for a missing Bluetooth permission;
raw session recording (redacted, capped, non-blocking) and the share intent. Owner on the tablet and the bike: everything in
the table of `docs/adr/0010-android-ble-and-pairing.md` section 10, in `docs/bench-checklist.md` items 2.1 and 3.1 to 3.17
and 4.1 to 4.6. The owner's first recorded ride (3.17) is the evidence for the open FTMS and heart-rate guesses (FT 1 to FT 12).

## What the Click and outbox stage (M4-2) proves without hardware, and what it leaves for the bench

Automatic, in `make android-check` (criterion 3 on the Android side, spec §4.7 and §4.9; ADR 0012): the Android transport's write
without response against the strict fake radio and the real `ClickDevice` over it; the container's Click (ownership, lifetime,
re-pairing, options, reconnect); **a press with no activity reaching the real engine**; the ten actions; the one routing rule in every
state; Learn buttons and the mapping in the settings; the developer settings; the Click's row and unlock notices as pictures and
taps; the outbox (`FileOutboxTest`: a process stopped after every step of a write loses nothing, a torn tail is ignored, duplicates
and order, compaction) and its wiring (a finished ride, a recovered ride and the rides on disk at a start are all queued, never
twice, oldest first, nothing uploaded); the sync settings (an address checked and cleaned, a token that never prints as itself and
never reaches a log, a ride file, the outbox or a backup) and a manifest with no `INTERNET` permission (that was Phase 1; stage
P2-1b adds it for the upload, ADR 0014). Owner on the tablet and the bike:
everything in the table of `docs/adr/0012-click-on-android-and-sync-outbox.md` section 11, in `docs/bench-checklist.md` items 5.1 to
5.12 and 7.8. The open checks it settles are CK 1 to CK 14 and spec §9 "Click v2 unlock bypass", "Click firmware" and "Click v2
reliability".

## How to run every automatic check

```
make check                                     # TypeScript, contracts and the Kotlin core
make android-check                             # the Android modules: tests, lint, ktlint, debug and release builds (Android SDK)
cd core && ./gradlew spotlessCheck check       # core on its own
node design/tools/build-tokens.mjs --check     # generated tokens are current
node design/tools/build-icons.mjs --check      # generated icons are current
pnpm lint                                      # Biome
```

CI runs the same on every commit; a release is cut only when it is green (`CLAUDE.md`, Process).

## Release gate for `v0.1.0`

All "Auto" checks are green and the release notes list every "Owner" check as not yet run on hardware
(the owner's bench comes after M4, see `docs/roadmap.md`, and is `docs/bench-checklist.md`). A criterion counts as *confirmed* only after
its "Owner" check has been done on the device; record the date and the outcome in the table below.
The open checks of spec §9 that Phase 1 can settle (RR intervals from the strap, Click unlock, gear
feel, ZUI background limits) are written into the release notes and `docs/spec.md` as they get answered.

| # | Criterion | Bench steps | Confirmed on device | Date and outcome |
|---|---|---|---|---|
| 1 | 60-minute ride, video full screen, overlay visible | 6.1, 6.8 to 6.13, 7.1 | not yet | |
| 2 | Workout plays in ERG and holds target | 3.3, 3.3b, 3.5, 3.10, 7.1 | not yet | |
| 3 | All ten Click actions from the right controller, app in the background | 5.5 to 5.7, 5.11, 7.1 | not yet | |
| 4 | Trainer power pulled mid-ride pauses; resumes with no data lost | 3.11 to 3.14 | not yet | |
| 5 | Kill mid-ride, relaunch recovers | 8.1 to 8.3, 8.5 | not yet | |
| 6 | FIT opens in intervals.icu with power, HR, cadence, laps | 7.2 to 7.6 | not yet | |
| 7 | Overlay drag drops no frames | 6.5, 6.6, 6.14 | not yet | |

## First bench (2026-11-18): observations only, no criterion confirmed

The owner's first ride on the real devices (`docs/bench-reports/2026-11-18-first-ride.md`: the signed `v0.1.0`, the D500, a strap and
the right puck of a Click) was **not run as the steps of `docs/bench-checklist.md`**, so no "Owner" cell above is run and no criterion
is confirmed. It bears on these, as signs and not as confirmations:

| # | What it showed |
|---|---|
| 1, 7 | Not reported: the overlay worked over a video by the owner's eye; no frame times, no 60-minute ride |
| 2 | An ERG ramp from 98 W to 138 W in 4 W steps every 12 seconds: the measured power four to nine seconds after each step was within about 3 W of the target (a last step ran 12 W high); a target of zero read zero power. Every Control Point command was answered with success. A good sign; the criterion also needs ramp and interval steps in a workout file |
| 3 | By the owner's eye the gear buttons change gears and the media buttons work. Not with a video app in front, not the ten actions, no timing |
| 4, 5 | Not tried |
| 6 | An exported FIT file of 224 one-second records, two laps and a start and a stop event was produced; **not yet imported into intervals.icu** (FIT 1 to FIT 8 stay open) |

It also changed code (ADR 0013): the Click's default reply check accepts the bare `RideOn` it answers, silence is no longer taken
for a lock, and the trainer adapter ignores the D500's Reset after its own Request Control. FT 1 (bit 2 is cadence) is settled.
