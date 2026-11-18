# Phase 1 acceptance

How each acceptance criterion of spec §4.10 gets verified, and in which release. "Auto" means CI
proves it without hardware; "Owner" means it needs the owner's tablet, bike or accounts and is listed
in that release's notes as something to try.

| # | Criterion (spec §4.10) | Auto | Owner on device | Stage |
|---|---|---|---|---|
| 1 | A 60-minute free ride completes with video playing full screen and the overlay visible throughout | Simulated 60-minute session runs to completion with no state loss. **Overlay (M1-B, auto):** the service shows exactly one window while the ride is live and the app is out of sight, hides it when the app returns, never flashes on a short leave, and stops after the summary (`OverlayServiceTest`) | 60 minutes over a real video app on the tablet; overlay stays visible, foreground service survives ZUI (bench 2.8 to 2.13) | M1 (simulated + window), M4 (real) |
| 2 | A workout file with steady, ramp and interval steps plays in ERG and holds target power | Workout player drives the simulated trainer through every step type, target power asserted per step (`SweetSpotEngineTest`, M2a) | Same workout on the D500; power holds the target | M2 (auto), M3 (owner) |
| 3 | All ten Click actions work from the right controller with the app in the background | Gesture layer: 5 buttons × press/hold produce the 10 actions, hold threshold 500 ms, volume repeat 150 ms | Each action from the real Click with the video app in front | M4 |
| 4 | Pulling the trainer's power mid-ride pauses the ride; it resumes after reconnect with no data lost | Engine: dropout pauses, reconnect resumes, samples recorded as gaps, nothing lost (`RideEngineStateTest`, `SweetSpotEngineTest`, M2a) | Unplug the trainer mid-ride | M2 (auto), M3 (owner) |
| 5 | Killing the app mid-ride and relaunching recovers the ride | **Core (M2b-1, auto):** per-second append-only log with a checksum per line (`RideLogRecorder`, ADR 0007); a log cut at every byte, zero-filled, with a garbage tail or one flipped byte recovers exactly the records before the damage (`RideLogFuzzTest`); a real process killed with SIGKILL mid-write recovers (`FileByteStorageTest`); the engine's ride is recovered identically (`RideEngineLogTest`). **App (M2b-2, auto):** the log is written on its own thread, so a stalled `fsync` never delays the engine (`RideHostDiskTest`); a simulated ride cut off mid-way (the process "dies", its files stay) is found by a new `AppContainer` over the same folder, offered on the Ready screen, and saved: the recovered samples are exactly the first ones the engine produced (`RideHostPersistenceTest`, `AppContainerRecoveryTest`); finalize is atomic, a stop after any step is completed on the next start (`RideStoreTest`); rides under 60 s are dropped, a finished log without its export is completed silently, a log that cannot be written is one notice and the ride goes on (`RideLibraryTest`, `StorageUiTest`) | Force-stop mid-ride (`adb shell am force-stop app.overdrive.ride`), relaunch, press **Save** in the notice: the ride's duration matches the time of the stop within a few seconds (bench 6.1 to 6.4) | M2 (auto), M3 (owner) |
| 6 | The exported FIT file opens correctly in intervals.icu with power, heart rate, cadence and laps | **Core (M2b-1, auto):** FIT written by our own writer and read back by an independent test reader written from the format facts: header and file CRC, sizes, message order, every field of records, laps, session, activity and hrv against the ride record, sport and sub-sport, gaps as invalid values (`FitWriterTest`), and byte for byte against the worked example of `docs/protocols/fit.md` section 6 (`FitGoldenTest`). Garmin's decoder is **not** part of CI: it is a local, opt-in oracle (`GarminFitOracleTest`, run with `-PfitOracleJar`, ADR 0007). **App (M2b-2, auto):** a finished simulated ride leaves `<id>.json` (equal to the engine's record) and `<id>.fit` (header, data size and CRC-16 checked by a local implementation, byte-equal to the writer's output for that record and the device's UTC offset) and no stray log; the finished screen's Share FIT file and Save to folder go through a `FileProvider` that serves only `files/rides/` and the system file picker (`RideHostPersistenceTest`, `StorageUiTest`, `RideFilesTest`) | **Share FIT file** or **Save to folder** on the finished screen, then import into intervals.icu (bench 7.1 to 7.5) | M2 (auto + owner) |
| 7 | Dragging the overlay drops no frames at the tablet's refresh rate | **Auto (M1-B):** a drag moves the window through `updateViewLayout` only, once per distinct position, with no recomposition of the content and no state write until it ends; bounds read once per gesture; 100 snapshots with the same numbers recompose nothing (`OverlayRecompositionTest`, `OverlayWindowControllerTest`, `OverlayUpdatesTest`). These prove the drag path is cheap, not that frames are not dropped | `dumpsys gfxinfo` and `am trace-ipc` while dragging on the tablet at each refresh rate (bench 2.5, 2.6) | M1 (auto: no work per move; owner: frame time) |

## What the overlay stage (M1-B) proves without hardware, and what it leaves for the bench

Automatic, in `make android-check`: the window's flags (no `FLAG_SECURE`, no `FLAG_NOT_TOUCHABLE`), the snap, clamp and
persistence maths, the tap, long press and drag classifier, the lifecycle and stop rules, the once-a-second filter with the
rider's actions shown at once, one window per live ride and none otherwise, tap and position written to the settings, the
permission rows. Owner on the tablet: whether Android 15 ZUI lets the sideloaded app hold "Display over other apps", whether
the long press brings the app to the front from a video app, frame time while dragging (criterion 7), DRM video and screen
recordings, blur, battery (spec §7.3) and the service surviving an hour. All of them are in `docs/bench-checklist.md`
(items 1.3 to 1.5 and 2.1 to 2.17) and in the "Unverified on hardware" table of `docs/adr/0006-overlay-service.md`.

## Release gate for `v0.1.0`

All "Auto" checks are green and the release notes list every "Owner" check as not yet run on hardware
(the owner's bench comes after M4, see `docs/roadmap.md`). A criterion counts as *confirmed* only after
its "Owner" check has been done on the device; record the date and the outcome in the table below.
The open checks of spec §9 that Phase 1 can settle (RR intervals from the strap, Click unlock, gear
feel, ZUI background limits) are written into the release notes and `docs/spec.md` as they get answered.

| # | Confirmed on device |
|---|---|
| 1–7 | not yet |
