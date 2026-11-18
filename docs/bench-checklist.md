# Bench checklist

For the owner, at the bench, with the signed `v0.1.0` APK, the tablet and the three devices. **One document, top to
bottom**: later sessions assume the earlier ones passed. Each step is a checkbox with the result to expect and the
acceptance criterion or spec §9 check it settles. Record every result in the table at the end, including passes, so the
release notes can say what was actually tested.

This checklist is a consolidation of the research notes. It links to them rather than repeating them.
Tags used in the steps:

- **AC n**: acceptance criterion n of `docs/phase1-acceptance.md` (and spec §4.10).
- **SP9**: a row of the open-checks table in `docs/spec.md` §9.
- **CK n**: item n of `docs/protocols/click-v2.md` §9.
- **FT n**: item n of `docs/protocols/ftms-hr.md` §9.
- **FIT n**: item n of `docs/protocols/fit.md` §8.
- **OV n**: item n of `docs/android/overlay-notes.md` §9.
- **ADR 6**: the "Unverified on hardware" table of `docs/adr/0006-overlay-service.md`.
- **ADR4**: the "to check on the bench" notes in `docs/adr/0004-ride-engine.md`.
- **ADR 12**: the "Unverified on hardware" table of `docs/adr/0012-click-on-android-and-sync-outbox.md`.

Anything marked **(unverified)** is a command or outcome the sources do not confirm. Run it, observe,
and write down what you see. Do not treat it as known.

## The sessions in order

| # | Session | Needs | About |
|---|---|---|---|
| 1 | Install and app permissions | tablet, PC with `adb`, the APK | 15 min |
| 2 | Bluetooth permissions | tablet | 10 min |
| 3 | Trainer over Bluetooth (AC 2, 4) | D500 | 1.5 h |
| 4 | Heart-rate strap | HR7 | 20 min |
| 5 | Click v2 (AC 3) | Click, ideally a Zwift account to unlock it | 1.5 h |
| 6 | Overlay over a real video app (AC 1, 7) | video apps, trainer, strap, Click | 2.5 h, with the 60-minute ride |
| 7 | A whole ride, FIT export and import (AC 2, 3, 6) | trainer, strap, Click, intervals.icu | 1 h |
| 8 | Crash recovery (AC 5) | trainer | 20 min |
| 9 | Wrap-up and what to send back | | 15 min |

Why this order: permissions come first because everything else needs them; the devices come one by one, the trainer first
because it is the only one a ride cannot do without and every later session rides on it; the overlay session comes after
them, so the hour-long ride is a real one with a live trainer, strap and Click; the whole-ride session then ties the pieces
together and produces the FIT file; the crash is last because it ends a ride on purpose. Numbers `S.n` are stable names:
the code comments and ADRs refer to them, and the crosswalk at the end maps the numbers of the M3 version of this list
(which the released notes of `alpha.2` and `alpha.3` still use) to these.

---

## Before you start

**What you need**

- The Lenovo Xiaoxin Pad Pro tablet, with USB debugging on, or wireless debugging paired.
- A PC with `adb` (Android platform-tools) and `apksigner` (Android build-tools), to check the APK.
- The signed APK from the GitHub Release, `overdrive-ride-<version>.apk` (the name the release workflow
  produces, see `.github/workflows/release.yml`). The package is `app.overdrive.ride`.
- The Van Rysel D500 trainer, powered on. Only one app may talk to it: close Zwift and any other app
  that connects to it (spec §2).
- The Coospo HR7 strap with a fresh battery.
- The Zwift Click v2, both pucks if you have them. Note whether it has been unlocked through Zwift today (spec §4.7),
  and the firmware version Zwift Companion shows. **Never update the Click's firmware** (spec §9).
- A video app for the overlay tests, and a stopwatch.
- Somewhere to keep logs that is **not** the repository (raw recordings must not be committed,
  `CLAUDE.md`).

**Check the build and the device first**

```
adb devices
adb shell getprop ro.build.display.id
adb shell getprop ro.build.version.release
apksigner verify --print-certs overdrive-ride-<version>.apk
```

`apksigner` prints the signer certificate digest. Compare it with the digest the release workflow printed
(`.github/workflows/release.yml`). Write down the device model, Android version and the
ROM build string. (The `getprop` names are generic adb, not from the research notes. If one prints
nothing, write down what Settings → About shows.)

**Capture logs**

- Log to a file during every session, and name the file with the date and the session number:
  ```
  adb logcat -v threadtime > ~/bench/2026-MM-DD-s3-logcat.txt
  ```
  Filter the live view by the app with `adb logcat | grep -i overdrive` if it is too noisy. The
  research notes do not define log tags, so the raw log is the safe default.
- Bluetooth traffic: turn on **Developer options → Enable Bluetooth HCI snoop log** before sessions 3 to 5
  (click-v2 §9 equipment). Pull it with `adb bugreport <dir>` (a zip with the snoop file inside), or read
  `btsnoop_hci.log` directly. Its location varies by ROM (unverified). Open it in Wireshark.
- Screen: `adb shell screenrecord /sdcard/overdrive-s2-01.mp4`, stop with Ctrl+C, then
  `adb pull /sdcard/overdrive-s2-01.mp4 ~/bench/`. Android's default time limit is 3 minutes (generic,
  not from the research notes), so restart for longer clips. Whether protected video and the overlay show in a
  recording is an open question (OV 9.2), so note what the recording shows.
- Screenshots: `adb exec-out screencap -p > ~/bench/shot-name.png`.
- After any failure, run `adb bugreport ~/bench/` straight away, while the state is still on the tablet.
- **Raw Bluetooth session logs.** The app writes one text file per connection session of each device, on by default
  (Settings, Developer, **Record Bluetooth sessions**): `ble-<date>T<time>Z-trainer.txt`, `...-strap.txt` and
  `...-click.txt`, in the app's private `files/ble-logs/` (capped at 2 MB a file, 20 MB and 60 files together). Format:
  `docs/protocols/ftms-hr.md` §7.1 and ADR 0009 §6. A file holds no address and no identity values, **but it does hold your
  heart rate and RR**: keep it out of the repository and send it privately. How to get the files off the tablet: 9.2. Where
  a step below says "the recording", it means these files; the HCI snoop log shows the same traffic.
- Where a step says **flip**, the change is one default in `FtmsOptions` (`core/src/commonMain/.../ble/adapter/FtmsOptions.kt`),
  in `HeartRateOptions` or in the named constant, followed by a new build. ADR 0009 §8 lists every one with its default
  and the evidence behind it. **The Click's options are the exception: most are switches in Settings, Developer · Click,
  with no rebuild** (session 5). Write the observed value in the results table even when the default turned out right.

**Conventions for results.** Each step ends with **If different**: write the screen you saw, the time,
the session number and the log file name. Do not change the expected result to match what happened.

---

## Session 1: install and app permissions

- [ ] **1.1 Check the signature.** `apksigner verify --print-certs overdrive-ride-<version>.apk`.
  Expect: a valid signature from the release key (the digest matches the workflow's). If it does not, stop: a different key
  forces an uninstall and loses the app's data (`CLAUDE.md`). Settles: the release gate for `v0.1.0`.
- [ ] **1.2 Install.** `adb install -r overdrive-ride-<version>.apk`.
  Expect: `Success`. `-r` keeps existing data. If the install is blocked by the ROM, allow installs from
  the source you use on the tablet, then retry (the exact menu path is unverified on this ROM).
  If you see a signature mismatch, do **not** uninstall. Record the message and stop.
- [ ] **1.3 Restricted settings.** On Android 13 and later, sideloaded apps may need "Allow restricted
  settings" before some permissions can be granted. Open the app's info page and look for that option.
  Expect: unknown (unverified whether it blocks the overlay). Write down whether it appears, and where.
  Settles: OV 9.1, OV 1 (restricted settings), ADR 6 (unverified table).
- [ ] **1.4 Overlay permission.** Open the app. The Ready screen shows a row "Show numbers over other apps" with
  **Allow**. Tap it: on Android 13 and later the system first asks about notifications (answer as you like, see
  1.5), then the "Display over other apps" screen opens. Switch the app on and come back.
  Expect: the toggle is not greyed out (see 1.3), and the row is gone when you are back, without restarting the
  app. Write down where the toggle sits. Also check **Settings → Overlay**: "Display over other apps" shows
  "Allowed" and the button says **Manage**. Optional check: `adb shell appops get app.overdrive.ride
  SYSTEM_ALERT_WINDOW` (unverified: `appops get` is not in the research notes; `appops set` is). Settles: OV 9.1.
- [ ] **1.5 Notification permission.** Allow notifications when the system asks (step 1.4). Expect: the ride's
  foreground-service notification ("Ride in progress") appears when a ride starts. If you declined, Settings →
  Overlay → "Ride notification" has an **Allow** button that opens the app's notification settings. Record what you
  chose: the service and the overlay should work either way (the notification is hidden from the shade but the
  service shows in the Task Manager, which is documented but not yet seen on this ROM).
- [ ] **1.6 Battery optimisation.** Open the app's battery setting and set it to unrestricted (the
  option names differ by ROM). Expect: the setting is saved. Optional check:
  `adb shell dumpsys deviceidle whitelist` (unverified; the research notes list it as unverified).
  Settles: OV 8.1 and the battery-exemption fallback in spec §9 (ZUI background limits).

---

## Session 2: Bluetooth permissions

The Devices screen carries one notice for whatever stops Bluetooth from working, whichever device is on the row.

- [ ] **2.1 Nearby devices permission.** Open **Devices** on a fresh install. Expect: one notice, "Allow nearby
  devices", with one button, **Allow**, above the rows, and no scan button. Tap **Allow**: Android asks once ("Nearby
  devices"); answer **Allow**. Expect: the notice is gone and **Scan for devices** is there, without restarting the app.
  Repeat on another fresh install, answering **Don't allow** (twice if Android asks twice): the notice must then say
  "It is switched off for Overdrive" and its button must say **Open settings**, which opens the app's settings page; grant
  the permission there, come back, and the notice goes by itself (the app re-reads the permission on every resume).
  The Ready screen shows the same notice while the trainer cannot be reached for this reason. Write down what the system
  question looked like on this ROM, how many times it asked, and whether "Open settings" really opened the page. Nothing
  asks for location. Settles: ADR 0010 table, rows P1 to P3. This is the first step in which the Click's row can show its own
  state: with nothing paired it says "Not paired" with **Pair**, like the other two.
- [ ] **2.2 Bluetooth switched off before anything is paired.** Switch the tablet's Bluetooth off from the quick settings,
  then open **Devices**. Expect: the notice "Bluetooth is off" with **Turn on**, and **Pair** on any row does the notice's
  one action (it opens the system's Bluetooth settings) instead of scanning; no scan runs and nothing is tried. Switch
  Bluetooth on: the notice goes by itself and **Scan for devices** appears, without restarting the app. Write down the
  wording of the notice and whether **Turn on** opened the right system page. Settles: ADR 0010 rows R1 and R2 (the
  paired-and-connected case is 3.13).

---

## Session 3: trainer over Bluetooth (AC 2, 4)

Close Zwift and any other app that connects to the D500 first (spec §2). The D500 accepts one client.
Turn the HCI snoop log on for this whole session.

- [ ] **3.1 Pairing.** Open **Devices** (the bike icon in the top bar, or **Pair a trainer** on the Ready screen), tap
  **Scan for devices** (or **Pair** on the Trainer row). Expect: under "Trainers" the D500 appears, with its name and a
  signal like "Strong · −58 dBm"; tap it. Expect: the Trainer row goes to "Connecting to ..." with a spinner, then
  "Connected", the "Paired" mark shows on the list entry, and the Ready screen enables **Start**. Write down the time from
  the tap to "Connected", the name as shown, and the signal words you saw at 1 m and at 3 m. If the D500 does not appear
  although it is on and no other app holds it, write that down: the scan filters on the Fitness Machine service in the
  advertisement (ADR 0010 row S1), and a D500 that does not advertise it needs the filter widened. Close the app (swipe it
  away) and relaunch: the trainer must connect **without any tap** (spec §4.1) and the Ready screen must reach Start
  by itself. Write the trainer's name and the time to connect. Do not write its MAC address into any file that might be
  shared (ftms-hr §7.1). Settles: spec §4.1 pairing, ADR 0010 rows S1, S2, C1.
- [ ] **3.2 Feature and status.** The app reads the Fitness Machine Feature and Status characteristics
  (spec §4.1). Check the logs for the feature bits. Expect: the bits needed for ERG and simulation are
  set (ftms-hr §10 asks for these bit checks before ERG and gears). Write down the eight bytes of the Feature
  read (the `R 2ACC` line of the recording, or the snoop log; the codec's reading is two little-endian words,
  ftms-hr §3), and whether the app reported anything missing (`TrainerEvent.FeaturesMissing`;
  `FitnessMachineFeatures.missingForRide`). By default a missing bit is only reported and the trainer's own answer
  decides (`FtmsOptions.enforceFeatureBits = false`): if the D500 rejects ERG or simulation although its Feature word
  claims them, or accepts them although it does not, write that down and decide whether to turn
  `enforceFeatureBits` on. Settles: the precondition in ftms-hr §10.
- [ ] **3.3 ERG holds target (AC 2, part).** Start a steady workout step at a set target, for example
  100% of FTP. Read the D500 display and the app's power. Expect: the trainer holds the target for the whole
  step. The tolerance is not defined in the sources. Judge it on the display and write down the range you
  saw. In the recording, look for the Status notification (`N 2ADA`) after a target write: opcode `08` and the
  watts (`FtmsOptions.controlRecoveryDelay` and `TrainerEvent.TargetPowerAltered` exist because of this question; if the
  D500 never sends `08`, nothing breaks). Settles: AC 2 (on the D500), FT 4 (write status `0x08` after a target write).
- [ ] **3.3b Ramp and interval steps (AC 2).** Load a workout file with a steady step, a ramp and an interval
  set (the native JSON or a Zwift ZWO file, spec §4.5). Run it from the start. Expect: each step's target shows on
  the overlay, and the trainer's power follows each target through the ramp and every interval. Write down any
  step where power does not follow. Settles: AC 2 (every step type).
- [ ] **3.4 Control point replies.** In the snoop log, find the replies to the target and simulation
  writes. Expect: the `0x80` indication arrives. Note the time from write to indication (median and the
  worst of several), since the research notes say the timeout depends on this: the recording shows it as the gap
  between the `W 2AD9` line and the `I 2AD9` line that follows. The guesses to set from it are
  `FtmsOptions.controlPointTimeout` (3 s; make it comfortably longer than the worst you saw),
  `operationFailedRetries` (2) and `operationFailedBackoff` (500 ms). Also note any result code other than `01` and
  which command got it (`80 <opcode> <result>`; the adapter retries `04`, takes control again on `05`, and remembers `02`).
  Settles: FT 6.
- [ ] **3.5 Start or Resume.** Check in the snoop log whether the app sends `0x07` (Start or Resume) and
  whether the trainer needs it before a target (`0x05`). Expect: unknown. The M3 adapter sends it by default, right
  after Request Control (the recording shows `W 2AD9 00`, then `W 2AD9 07`, then the first `05`;
  `FtmsOptions.startAfterRequestControl = true`). Write down the result code the D500 gives `07` (`80 07 <result>`; a
  refusal is only logged) and whether a target works without it. Flip `startAfterRequestControl` to false only if
  sending it does harm. Write down the sequence that worked. Settles: FT 7.
- [ ] **3.6 Gear feel.** Free ride, flat. Step through the gears one at a time, up and down. Write down, for
  each gear you try, whether the change feels right. The defaults are 24 gears at 1% each, neutral at 12
  (ADR4, spec §4.4). Expect: each gear is clearly different and no step feels like a jump. Also read the
  grade on the D500 display after a gear change: is it honoured at 0.01% steps? Settles: spec §9 "Gear
  feel", FT 5, ADR4.
- [ ] **3.7 Simulation limits.** The app should log the Supported Inclination Range characteristic (`0x2AD5`).
  Find it in the log and write down the min and max. Check whether the 15% maximum in spec §2 is there.
  Then set a negative grade in a free ride and check the value on the D500 display. Expect: a negative grade
  reads as a downhill. The M3 adapter does not read `0x2AD5` itself yet (ftms-hr §10), so take it from the snoop log
  or nRF Connect. Also find the Status echo of the simulation (`N 2ADA 12 ...`): the two grade bytes are two's
  complement for a downhill (`38 FF` is -2.00 %, ftms-hr §8 ST3). The codec reads them signed
  (`FitnessMachineStatusCodec.DEFAULT_SIMULATION_SIGNED = true`, per device `FtmsOptions.simulationStatusSigned`); write
  down what you saw and whether the display agrees. Settles: FT 5, FT 8.
- [ ] **3.8 Cadence, speed and distance.** Ride at a steady cadence. Compare the cadence on the D500 with the
  app's cadence. Ride a known distance (for example, a lap of the trainer's own display) and compare with the
  app's distance. Expect: cadence matches. Distance matches within the trainer's own tolerance (unverified).
  Note which flag bit the cadence is in (FT 1, the codec's assumption is bit 2): in the recording, take an
  `N 2AD2` line with speed, cadence and power and read the low byte of the flags. `44` (bits 2 and 6) is what
  `IndoorBikeFlagMap.DEFAULT` (`DECODERS`) expects, with the cadence in bit 2; `42` means the D500 puts the cadence in
  bit 1 as the SIG XML labels say, and then `IndoorBikeFlagMap.DEFAULT` must become `SIG_XML_LABELS` (one line; per
  device `FtmsOptions.flagMap`). That reading of the two byte patterns is derived from ftms-hr §2.2 and §2.4, not
  observed. For distance, `IndoorBikeData.totalDistanceMetres` assumes metres (FT 2); if the app's distance is ten
  times off, the unit is decimetres. Settles: FT 1, FT 2, FT 3 (More Data: check for speed-absent or split
  notifications in the log; the app counts them as `TrainerEvent.MoreDataSeen`).
- [ ] **3.9 Auto-pause (cadence 0).** Ride, then stop pedalling. Expect: the ride pauses after about 3 seconds
  (spec §4.2). The overlay says why (auto). Pedal again: the ride resumes. Watch the log for the cadence the
  trainer reports when you stop: does it send 0, or hold the last value? Expect: 0. If it holds the last value,
  auto-pause never fires. Settles: ADR4 ("reports cadence 0 when the rider stops").
- [ ] **3.10 Zero watts in a paused ERG step.** Start a workout ERG step, then pause it (on screen). Expect:
  the trainer's target drops to 0 W and the trainer accepts it. If it rejects 0 W, the response code appears in
  the `0x80` indication (`80 05 <result>`). Record the code. On resume, the target ramps in over about 3 seconds
  (spec §4.5). A rejection is retried every second by the engine, so the ride does not stop, but the trainer then
  holds its last target. Settles: ADR4 ("is 0 W accepted"), spec §4.5.
- [ ] **3.11 Trainer dropout (AC 4).** Ride for a few minutes. Switch the trainer off at the wall. Expect:
  the ride pauses at once, and the overlay says the trainer disconnected. Wait 30 seconds, then switch it on.
  Expect: the trainer reconnects by itself and the ride resumes. When the ride is over, the record should show
  the seconds in between as paused samples with no power. Write down the reconnect time. After the reconnect,
  the recording should show a new `S connecting` and the whole set-up again (discovery, the three CCCD writes, `W 2AD9 00`),
  and the trainer's power should follow the workout target again without you touching anything: the adapter takes
  control back and the engine re-sends the target. Write down any step that did not repeat. Settles: AC 4, spec
  §4.2 (trainer link), FT 9 (indications still arrive while notifications are on).
- [ ] **3.12 Reconnect without the app restarting.** Repeat 3.11 with a longer gap (two minutes). Expect: the
  reconnect backoff works and the app reconnects without a restart. Note the time of each attempt (each
  `S connecting` line of the recording, or the log). The default `BackoffPolicy` waits 1 s, 2 s, 4 s, 8 s, 16 s and then
  30 s between attempts (each +/-20 %), starts again from 1 s after a link that held 30 s, and gives an attempt 45 s to
  finish (`LinkSupervisor.DEFAULT_ESTABLISH_TIMEOUT`). Also write down how long one attempt against the switched-off
  trainer takes to fail (`S connecting` to `S disconnected` and its status; 133 is expected) and whether any attempt
  ended only because of the 45 s backstop. On the Devices screen the Trainer row says "Not found · retrying in N s" and
  counts down once a second: check that it does, and that nothing else on the screen ticks. Press **Retry now** during a
  wait: the next attempt should start at once. Settles: spec §4.1 (reconnect with backoff); the guesses to tune are
  `BackoffPolicy` (initial, factor, cap, jitter, stable time) and the establish timeout.
- [ ] **3.13 Bluetooth switched off, and the permission.** During a ride, switch the tablet's Bluetooth off from the
  quick settings. Expect: the ride pauses (the trainer link is gone), the Devices screen shows the notice "Bluetooth is
  off" with **Turn on**, both device rows say "<name> · Bluetooth is off", and nothing is retried
  (`LinkStatus.BluetoothUnavailable`). Switch it on, from the quick settings or with **Turn on** (which opens the system's
  Bluetooth settings): the trainer and the strap reconnect at once, without waiting for a backoff step, and the notice
  goes by itself. Repeat by revoking "Nearby devices" in the app's permissions (spec §4.1, Android 12 and later): expect
  the notice "Allow nearby devices" and the rows "<name> · needs Nearby devices". Write down how long the reconnect took
  after Bluetooth came back, and whether the foreground service and the overlay kept running while the permission was
  revoked (they must: the service falls back to `specialUse` alone, ADR 0010 row F1). Settles: spec §4.1 (reconnect),
  ADR 0009 §5, ADR 0010 rows R1, F1.
- [ ] **3.14 Power-cycle the trainer while the app is idle.** With the trainer connected and the Ready screen
  showing, switch the D500 off. Expect: the Trainer row goes to "Connection lost · retrying in N s", the Ready screen's
  **Start** greys out with "Waiting for the trainer to connect." and the top bar says "Waiting for trainer". Switch the D500
  on: within one backoff step (at most 30 s, or press **Retry now**) the row says "Connected" and **Start** is back, with no
  tap on **Scan** and without restarting the app. Do this three times in a row, and once with the app in the background (open
  another app for a minute, switch the trainer off and on, come back). Write down the reconnect times. Settles: ADR 0010
  rows C2, C3 and the "ride after a reconnect" row of ADR 0009.
- [ ] **3.15 No trainer, no ride.** Tap **Forget** on the Trainer row. Expect: the row says "Not paired" with
  **Pair**; the Ready screen says "No trainer is paired." with **Pair a trainer**, **Start** is off and the top bar says
  "No trainer"; no simulated numbers appear anywhere. Then Settings, Developer, **Devices**: **Simulated**. Expect: Start is
  on, the Devices screen says "These are simulated devices", and the ride is the old simulation. Switch back to
  **Bluetooth** and pair the D500 again: the ride must be rebuilt by itself (Start comes back when the trainer connects).
  Also check that **Forget** and **Scan** are not offered during a ride ("Devices cannot be changed during a ride."). Settles:
  ADR 0010 section 7.
- [ ] **3.16 Foreground service types.** Start a ride with "Nearby devices" allowed. In a PC shell:
  `adb shell dumpsys activity services app.overdrive.ride` and read `foregroundServiceType` of the overlay service
  (unverified output format; expect both `connectedDevice` and `specialUse`, `0x10 | 0x40000000` as hex). Finish the
  ride, revoke "Nearby devices", start a ride in **Simulated** mode and look again: the service must start and show only
  `specialUse` (a missing permission must never stop the overlay on Android 14 and later). Write down both outputs.
  Settles: ADR 0010 row F1, ADR 0006 "Unverified on hardware".
- [ ] **3.17 Record a first ride and share the logs.** Settings, Developer: **Record Bluetooth sessions** is on
  (the default of the early builds). Ride 10 minutes with the trainer and the strap, ERG then simulation, and
  put the trainer in a dropout once (3.11). Then Settings, Developer, **Share Bluetooth logs**: the row says how many
  files and how big ("4 files · 120 KB"); tap **Share** and send them to yourself through any app. Expect: files named
  `ble-<date>T<time>Z-trainer.txt` and `...-strap.txt`, a new file per connection session, readable text starting with
  `# overdrive-ble-session 1`, with no device address and no device name other than a cleaned model line. **They do
  contain your heart rate**: keep them out of the repository and share them only with whoever is fixing the codecs.
  Replay one with `BleSessionFormat` to confirm the notes' guesses (FT 1 to FT 12 above). Write down the file sizes after
  the ride (the cap is 2 MB a file, 20 MB and 60 files together) and whether **Share** offered every file to the app
  you chose. Settles: ADR 0010 section 8, row L1, and gives the evidence for FT 1 to FT 12.

---

## Session 4: heart-rate strap (HR7)

- [ ] **4.1 Pairing and first value.** Put the strap on with the electrodes damp. On **Devices**, tap **Scan for devices**
  (or **Pair** on the Heart rate row). Expect: under "Heart-rate straps" the HR7 appears with a signal in words; tap it. The
  Heart rate row goes to "Connecting to ..." and then "Connected". Expect: a heart-rate value shows on the Ready screen's
  top bar and, in a ride, on the overlay. Write down the time to the first value. Close the app, relaunch: the strap must
  connect by itself, as the trainer does (3.1). Only one strap and one trainer can be paired: pairing another strap
  replaces the first (check that the old one disappears from the "Paired" mark and that no second link is opened).
- [ ] **4.2 RR intervals.** Check the snoop log or the strap's session file (how to get it: 9.2) for the
  Heart Rate Measurement flags. Expect: unknown: spec §9 lists RR intervals from the Coospo strap as an
  open check. Write down whether RR bit is set, how many RR values come per notification, and whether they
  look like 1/1024 s units (FT 10). In the recording these are the `N 2A37` lines: the first byte is the flags
  (`16` is 8-bit rate, contact detected, RR present; bit 4, `0x10`, is the RR flag), and each RR is two little-endian
  bytes after the rate. The app converts them with `RrIntervals.rawToMillis` (x1000/1024, halves rounded up); if the
  strap's RR looks like milliseconds already, that is the one function to change. If the app has no RR, record that.
  Optional: compare the RR average with the strap's own report, if you have nRF Connect (spec §9 names it). The file holds
  heart rate and RR: do not commit it. Settles: SP9 "RR intervals", FT 10.
- [ ] **4.3 Contact.** Take the strap off the chest. Expect: no heart rate is shown, not a stuck value (ADR4:
  0 bpm without contact). Put it back on. In the log, write down the contact status values you see (2 or 3).
  Expect: 3 when on the chest (the SIG XML's reading, ftms-hr §6.5). In the recording the contact status is bits 1 and 2
  of the first byte of each `N 2A37` line (`06` is 3, `04` is 2, `02` is 1, `00` is 0). The app reads only 3 as contact
  (`HeartRateContactRule.DEFAULT = SIG_XML`; the other rule is `ANY_NONZERO`, pycycling's reading) and shows no heart
  rate, with no RR, while contact is reported as supported but not detected (`HeartRateOptions.noSignalWithoutContact`).
  If the HR7 reports 1 or 0 all the time it does not report contact, and a strap off the chest is then visible only
  as notifications that stop: write that down. Settles: FT 11.
- [ ] **4.4 Dropout is only a gap.** During a ride, move the strap away for 20 seconds. Expect: the ride keeps
  going and the heart-rate samples have a gap (spec §4.2). Settles: spec §4.2 (heart-rate dropout never
  pauses).
- [ ] **4.5 MTU and long notifications.** In the log, find the negotiated MTU and whether the largest trainer
  notification (30 bytes) arrives whole. Expect: MTU at least 64, requested at connect (ftms-hr §7;
  `FtmsOptions.requestedMtu = 64`). The negotiated value is the `S - 0 mtu=N` line of the recording. Write down the
  negotiated value, and whether the app carried on when the request was refused (the MTU step is optional in the
  adapter). Settles: FT 12.
- [ ] **4.6 Battery service (optional).** The UUID `0x180F` is unverified (ftms-hr §1, FT 13). With nRF Connect,
  check whether the strap exposes it. Write down what you see. Not needed for acceptance.

---

## Session 5: Click v2 (AC 3)

The Click is paired on **Devices** like the other two, remembered, and connected when the app starts. It is owned by the
app's container, not by a ride, so it keeps its link between rides and a ride that ends or starts never closes it
(ADR 0012). The ride never depends on it: every state but "Connected" means "carry on with the on-screen controls". The
app is written against `docs/protocols/click-v2.md` and a fake Click built from the same notes; **no real Click has been
connected yet**. Never update the Click's firmware: if Zwift Companion offers an update, decline it (spec §9). Write down
the firmware version from the Companion app and the Zwift app version before each run (click-v2 §9); the app also reads
the firmware revision at set-up (`ClickDevice.firmware`) and keeps it out of every recording.

**The Click's row on Devices**, in the words you will see:

| Row says | Means |
|---|---|
| Not paired, **Pair** | nothing remembered |
| Connecting to Zwift Click (right)… | the app is connecting or setting up |
| Zwift Click (right) · press any button | connected, handshake done, no message yet. First bench (2026-11-18): a real Click sends nothing until a button is pressed, so the row says to press any button (up to the silence limit) |
| Zwift Click (right) · battery 87% | delivering messages; the battery once the Click has said |
| Not found · retrying in 8 s / Connection lost · … / Could not connect · … | it is off, asleep, out of range or held by another app / the link dropped / a set-up step failed; **Retry now** tries at once |
| Zwift Click (right) · locked, with a notice below | the Zwift service is hidden, or it answered and says nothing (5.3) |
| Zwift Click (right) · Bluetooth is off / needs Nearby devices | the same notice and fix as the others |

**The developer settings** (Settings, right column, **Developer · Click**) are the bench's switches. Each is one option
of `ClickOptions` or `GestureTiming` (table in ADR 0011 §9). A change replaces the Click's device (a reconnect of a few
seconds) and never touches a ride; **Reset** puts them all back and keeps the learned buttons.

| Setting | Option | Default | Question it answers |
|---|---|---|---|
| Setup message | `sendPostHandshake` (writes `FF 04 00`) | on | CK 6 |
| Handshake with response | `writeWithResponse` | off | CK 2 |
| Reply check | `handshakeReply`: Exact (`RideOn 02 03`) or Any RideOn | Exact | CK 2 to 4 |
| Keep-alive | `keepAliveInterval`: Off, 10 s, 30 s, 60 s | Off | CK 7 |
| Silence limit | `noEventTimeout`: 15 s, 30 s, 60 s, 2 min | 30 s | CK 7, CK 14 |
| Message size | `requestedMtu`: System, 128, 247 | 128 | CK 3 |
| Needs its service listed | `requireAdvertisedService` | on | CK 12 |
| Pucks found by Scan | `acceptedSides`: Right or Either | Right | CK 1 |
| Hold threshold | `GestureTiming.holdThresholdMillis`, 250 to 1000 ms | 500 | AC 3 |
| Volume repeat | `GestureTiming.repeatIntervalMillis`, 50 to 500 ms | 150 | AC 3 |

Not a switch (change the default in `ClickOptions` and build again): the two device-type bytes `rightSideByte` and
`leftSideByte`, `serviceHiddenMeansLocked`, `silentMeansLocked`, `acceptLegacyService`, `ignoredBits`,
`handshakeTimeout`, `operationTimeout`, `readStandardCharacteristics`, and `GestureTiming.bounceMillis`.

- [ ] **5.1 Discovery and pairing.** On **Devices**, **Scan for devices**. Expect: under "Handlebar controllers" the right
  puck as **Zwift Click (right)** with a signal in words (its name comes from its side, never from what it advertises);
  the left puck is not listed unless Pucks found by Scan is **Either**, and then as **Zwift Click (left)**. Tap the right
  one: the Click row goes to "Connecting to Zwift Click (right)…". Also scan with nRF Connect and write down each puck's
  advertised services and the full manufacturer data (the app recognises a Click from the service `FC82` or the older
  service **and** company `0x094A` with first byte `0x0A` right or `0x0B` left, never from the name:
  `ClickAdvertisements.recognise`). The byte that says which side the puck is (`0x0A` or `0x0B`) is unconfirmed: if the
  two are swapped, set `rightSideByte` and `leftSideByte`. If no puck is listed although it is awake, switch **Needs its
  service listed** off (the scan then runs unfiltered and tells a Click by its manufacturer data alone) and scan again.
  Close the app and relaunch: the Click connects by itself, as the trainer does (3.1). Settles: CK 1, CK 12.
- [ ] **5.2 Plain handshake, locked right puck.** Use a right puck with no Zwift connection in the last day. Pair it. The
  app subscribes to ASYNC, then to SYNC_TX, writes `RideOn` (`52 69 64 65 4F 6E`) without response, waits for the reply
  and by default writes `FF 04 00`. Expect: unknown. Write down the state the row reaches, the reply to the handshake and
  its length (the Click's session file shows both: the reply is `RideOn`, two version bytes and a tail, **the tail is
  zeroed in the file but its length is kept**), and whether buttons arrive without any unlock. Also write down whether
  the custom service is listed in GATT discovery (the row says **locked** with the "Click is locked" notice if not). If the
  row says **Could not connect** after a reply, the Click's session file has the first eight bytes of the reply: a reply
  other than `RideOn 02 03` is what **Reply check: Any RideOn** is for, and `01 03` would be the encrypted variant of the
  notes' §3.3 (**not implemented**; nothing here encrypts or unlocks). If the write fails, switch **Handshake with
  response** on. If the reply never comes, `handshakeTimeout` (5 s) is the number. Settles: CK 2, CK 3, CK 4, CK 12, CK 14,
  spec §9 "Click v2 unlock bypass" and spec §2 ("right controller working without unlock").
- [ ] **5.3 Unlock notice.** When the controller is locked, the app tells you to unlock it through Zwift once and goes on
  without it (spec §4.7). Two signs, two texts, one promise. The service is hidden from discovery: the row says "locked"
  and the notice **Click is locked**, "Zwift has it locked. Unlock it through Zwift once; the ride continues without it."
  (the app backs off and tries again by itself, at most every 30 s). The Click answers the handshake and then sends
  nothing for the silence limit: the notice **Click sends no presses**, "Press any button on it. If that does nothing, unlock it through
  Zwift once; the ride continues without it." (the link stays up and a late message clears the notice by itself).
  **Check again** retries now; for a silent Click it drops the link and starts over. Expect: the notice shows with the
  right text for the sign, and the on-screen ride controls keep working. Write down the exact notice and which sign fired.
  Then unlock through Zwift as the notice says, close Zwift, and write down the time: the Click should be picked up at the
  next attempt without a restart. Settles: spec §4.7 (unlock handling), CK 8, CK 12.
- [ ] **5.4 Unlock duration.** Check the controller after 1 hour, 12 hours, 25 hours, and after a phone reboot (CK 8).
  Expect: unknown. Write down when the service hides again, if it does. Settles: CK 8, spec §9 "Click firmware" context.
- [ ] **5.5 Button bitmap and Learn buttons.** With the Click connected and no ride live, tap **Learn buttons** (it says why
  when it cannot: "Not during a ride.", "Connect the Click to learn its buttons."). It asks "Press the button you want as
  Plus", then Button 2 to 5, one press and release each, with the Click's own names on the right (Y, Z, A, B, Plus). Press
  two buttons together: expect "One button at a time" and the same question again. Press a button you already gave:
  "That button is taken", naming the role that has it. **Cancel** leaves with nothing saved. While the flow runs the Click
  does nothing else: press Plus and see that no gear changes. When all five are set the mapping is saved and the one
  button left is **Done**. Kill the app and relaunch: the map on Devices still shows your buttons. **Reset to default**
  puts Plus, B, Y, A and Z back. Then, in the Click's session file or the snoop capture, press each of the five buttons on
  each puck in turn, one at a time. Expect: a pressed button clears its bit (0 = pressed, click-v2 §4: A `0x0010`, B
  `0x0020`, Y `0x0040`, Z `0x0080`, Plus `0x1000`). Write down the bitmap for each press, **including the second Plus bit
  `0x2000`** (if one press sets two bits, Learn buttons refuses it as a combo: say so, `allowMultiBitButtons` accepts it)
  and any bit that reads pressed all the time (that is `ignoredBits`). The default mapping is a guess: Plus gear up, B gear
  down, Y play and pause, A volume up, Z volume down (ADR 0011 §5); write down the mapping you chose and whether the
  default was comfortable. Settles: CK 5, spec §4.7 (assign buttons once observed).
- [ ] **5.6 The ten actions (AC 3).** Start a ride, then open the video app so Overdrive is **in the background**; do each
  action from the right Click and write pass or fail. The press reaches the ride because the app's container owns the
  Click, not an activity (ADR 0012): that this still holds on this tablet is what the step settles. Expect each row below.

  | Button | Press | Hold |
  |---|---|---|
  | Plus | Gear up | Next interval |
  | Button 2 | Gear down | Previous interval |
  | Button 3 | Play or pause the media | Pause or resume the ride |
  | Button 4 | Volume up | Volume up, repeating |
  | Button 5 | Volume down | Volume down, repeating |

  In ERG steps, the gear buttons change intensity by 5% instead (spec §4.7). A press of Plus, Button 2 or Button 3 acts when
  you **let go**, not when you touch it; a hold acts once, at 500 ms, while you are still pressing, and the release does
  nothing. Button 4 and 5 act at once on touch. Two buttons down at the same time at the 500 ms mark fire no hold. Expect: the
  media key reaches the video app (`AudioManager.dispatchMediaKeyEvent` from a background process: its behaviour is
  unverified, overlay-notes §7; write down whether the video app paused). Volume: write down whether the system volume bar
  appears (unverified, OV 9.9). The overlay shows the gear or intensity change within a second. Also pull the Click's link
  (switch it off, or walk away) while holding a volume button: the repeating must stop and no button may stay held after
  it comes back. Settles: AC 3, ADR 12 (background press, media key, volume).
- [ ] **5.7 Hold timing.** Time a hold of about 400 ms and one of about 600 ms on a button. Expect: the short hold is a
  press and the long one is a hold (500 ms, spec §4.7). For the volume repeat, hold a volume button for two seconds and
  count the steps: expect **12** (one at the touch, then at 500 ms and every 150 ms: eleven repeats). Then change **Hold
  threshold** to 750 ms in Settings and repeat: 600 ms is now a press; put it back. If a press ever fires twice, or a hold
  is lost in the middle, the controller bounces: turn on `GestureTiming.bounceMillis` (30 is a start; it delays every
  release by that time; not a switch). Settles: AC 3 (hold threshold, repeat).
- [ ] **5.8 Keep-alive and reconnect.** Stay connected and idle for 5 minutes, then 60 seconds, then 5 minutes again. Note
  any disconnect and how long it stays down (CK 7). Expect: unknown. Expect the app reconnects without a restart: the row
  says "Connection lost · retrying in N s" and counts down, then "Connected". If the puck falls asleep or disconnects, set
  **Keep-alive** to 30 s (the neutral frame `23 08 FF FF FF FF 0F`) and note whether it changes anything. Note whether the
  controller sends heartbeats (`15`) and how often: if it does not, **Silence limit** is the only thing that can call a
  working puck locked, so lengthen it. Settles: CK 7, SP9 "Click v2 reliability".
- [ ] **5.9 Message types and the battery.** In the Click's session file or the snoop log, note when type `0x3C` appears and
  what follows it (CK 10; the app reports it as an unrecognised frame with its length, the bytes are in the file). The
  Devices row shows "battery N%" once the Click has said: compare it with Zwift Companion and note whether it changes as the
  puck drains (CK 11, `ClickDevice.battery`). The `FF 04 00` write is on by default (**Setup message**): repeat 5.2 with it
  off and with it on and record any difference and any reply (CK 6). Expect: unknown.
- [ ] **5.10 Reliability in a ride.** Count the disconnects during the 60-minute ride in 6.8 (or any ride). Expect:
  reconnect without the ride stopping; the on-screen controls still work. Settles: SP9 "Click v2 reliability".
- [ ] **5.11 Screen off and the background (what only the tablet settles).** (a) Start a ride, switch the screen off for
  two minutes, press Plus a few times, wake the screen: expect the gear changed (the foreground service of a live ride keeps
  the process, and with it the Click's link, alive). (b) With **no ride live**, leave Overdrive for the video app for ten
  minutes, then press a button inside a ride you start straight afterwards: expect the Click still connected (there is no
  foreground service without a ride, so Android may stop the process, and the Click then reconnects at the next start; write
  down which happened). (c) Swipe the app away from recents during a ride (as 8.4) and press a button: write down whether it
  still reaches the ride. Settles: ADR 12 (what keeps the process alive), OV 9.6, OV 9.7.
- [ ] **5.12 Forget, pair again, and a changed option.** **Forget** the Click: the row says "Not paired" and no button does
  anything. Pair it again from the scan. Then change a developer setting (for example Keep-alive): the row goes to
  "Connecting to Zwift Click (right)…" and back to connected within a few seconds; during a ride the same change must not
  pause or alter the ride, and **Learn buttons** and **Forget** are refused ("Not during a ride.", "Devices cannot be
  changed during a ride."). Settles: ADR 12 (ownership and re-pairing).

---

## Session 6: overlay over a real video app

Use the build you are testing, **with the real trainer, strap and Click from sessions 3 to 5**, so the overlay is
judged over a real ride. Where a step needs no device (the drag, the snap, the lifecycle), **Settings, Developer, Devices,
Simulated** is a fine stand-in. The service starts from the ride screen when a ride is live (ADR 0006), so start the ride
from the ride screen, then switch to the video app. The overlay is **not** drawn while Overdrive itself is on screen, and appears
about 0.3 s after you leave it. The overlay button in the top bar is only an in-app preview and has nothing to
do with the window. Demo speed (Settings, developer group) runs a ride 10 or 60 times faster if you want to see
a workout's steps without waiting.

- [ ] **6.1 Overlay over video.** Start a ride, then open a video app full screen. Expect: the overlay
  stays visible on top. Settles: AC 1 (part), OV 9.2 (for protected video, see the next step).
- [ ] **6.2 Protected video.** Repeat with a DRM-protected streaming app. Expect: the overlay is drawn
  over it. Take a screen recording. Write down whether the overlay appears in the recording. Settles: OV 9.2.
- [ ] **6.3 Legibility.** Look at compact and detailed mode over dark and bright scenes at arm's length
  and at the bike's viewing distance. Expect: power, heart rate, cadence and time are readable at a
  glance. Write down any value you cannot read. Settles: spec §4.6 (readable over video), spec §7.4.
- [ ] **6.4 Tap and long press.** Tap the overlay: it toggles compact and detailed once, without animation,
  and the mode is still the same after you reopen the app (Settings → Overlay → Mode). Long-press it for half a
  second without moving: the full app comes to the front (no haptic, the app appearing is the feedback). Expect:
  both work from the overlay while a video app is in front. If the long press does nothing, record it and try the
  notification tap (6.12). Settles: spec §4.6; OV 9.5 (background start from the overlay's touch handler).
- [ ] **6.5 Drag smoothness (AC 7).** Run these three commands around a 10-second drag of the overlay.
  The app must be a release or non-debuggable build, or the numbers are not valid (overlay-notes §5.4).
  ```
  adb shell dumpsys gfxinfo app.overdrive.ride reset
  # drag the overlay continuously for 10 s
  adb shell dumpsys gfxinfo app.overdrive.ride framestats
  ```
  Expect: no "Janky frames" during the drag. The output format is **unverified** in the research notes,
  so write down the janky-frame line as printed, and the frame count. Run the drag again at each refresh
  rate the tablet offers (60, 90, 120 Hz, OV 9.4). Write down which rate was active.
- [ ] **6.6 Binder calls during drag (AC 7).** Same drag, with IPC tracing (documented):
  ```
  adb shell am trace-ipc start
  # drag the overlay for 10 s
  adb shell am trace-ipc stop --dump-file /data/local/tmp/ipc-trace.txt
  adb pull /data/local/tmp/ipc-trace.txt ~/bench/
  ```
  Expect: no binder call per move from the drag handler (OV 9.3). Settles: AC 7, OV 9.3.
- [ ] **6.7 Snapping and position memory.** Drag the overlay by its middle: it follows the finger 1:1 and
  never leaves the screen. Let go in the left half: it slides (about 0.2 s) to the left edge with a 16 dp
  margin; in the right half, to the right edge. Drag it to the bottom, tap it to go detailed: it must grow
  upward and stay on screen, and tap again to come back down. Press Home, reopen the app and leave again: the
  overlay comes back at the same place in the same mode. **Settings → Overlay → Position → Reset** puts it back
  top right. Write down any jump at the moment of a bottom-edge resize (ADR 0006 expects at most one frame).
  Settles: spec §4.6; OV 9.11.
- [ ] **6.8 Hour-long survival, screen on (AC 1).** Start a 60-minute free ride with the video app full
  screen and the screen on. Keep the ride log running. Expect: the overlay stays visible for the whole
  hour, and the ride finishes. Note any gap, and the time of the gap. This is the owner check for AC 1
  (with the trainer, strap and Click connected; use the Click for the gears now and then, 5.6).
- [ ] **6.9 Screen off and unplugged.** Start a 30-minute ride, switch the screen off with the power
  button, and unplug the tablet from charge. Expect: when you wake the screen, the overlay is there, the
  trainer is still connected, and the ride has kept counting. Settles: OV 9.7, spec §9 (ZUI background
  limits). To simulate this without waiting (device must be unplugged):
  ```
  adb shell dumpsys battery unplug
  adb shell dumpsys deviceidle force-idle
  # later
  adb shell dumpsys deviceidle unforce
  adb shell dumpsys battery reset
  ```
  These are documented in overlay-notes §8.3. Record which one reproduces any problem.
- [ ] **6.10 Restricted bucket.** During a ride, force the app into the restricted standby bucket:
  ```
  adb shell am get-standby-bucket app.overdrive.ride
  adb shell am set-standby-bucket app.overdrive.ride restricted
  ```
  Expect: the overlay, the trainer connection and the notification keep working. Write down any change.
  Put the bucket back to the value you read first (the restore command is not in the research notes;
  check the output of `am get-standby-bucket` after the change). Also, optionally,
  `adb shell cmd appops set app.overdrive.ride RUN_ANY_IN_BACKGROUND ignore` (documented), and restore it
  afterwards with the matching command (unverified). Settles: OV 8.1, OV 9.7.
- [ ] **6.11 Battery use.** Record the app's battery use before and after a 60-minute ride with the
  overlay visible:
  ```
  adb shell dumpsys batterystats app.overdrive.ride
  ```
  (unverified: the research notes list this command as unverified, and it may print a lot of text.
  Keep the output and compare the two snapshots.) Write down the percentage of battery used. Settles:
  spec §7.3 (battery is a requirement), OV 8.3.
- [ ] **6.12 Open the app from another app (long press and notification).** With the video app in front, long-press
  the overlay (as 6.4), then go back to the video app and pull down the shade and tap **Ride in progress**.
  Expect: the long press brings Overdrive to the front. Android's rules for starting an activity from the
  background list "has a visible window" and "holds `SYSTEM_ALERT_WINDOW`" as exemptions, but a blocked start gives
  no error, only the log line `Background activity launch blocked!` (`adb logcat | grep -i "launch blocked"`), and
  nothing says what ZUI adds on top. The notification tap is the documented fallback: expect it to work either
  way. Record both results, and the Android and ROM version. Settles: spec §4.6, OV 9.5, ADR 6 (unverified table).
- [ ] **6.13 Lifecycle of the window.** Start a ride on the ride screen, then check each of these in turn:
  (a) with Overdrive in front there is no window; (b) press Home or open the video app: the window appears within
  about half a second; (c) come back to Overdrive: it disappears at once; (d) rotate the tablet while in Overdrive:
  the overlay must **not** flash; (e) pause the ride in Overdrive, then leave for the video app: the overlay shows
  the paused look; (f) let a workout end (Demo speed 60× is fine) while the video app is in front: the window goes,
  the app comes to the front with the summary and the notification says "Ride finished"; if the app did not come to
  the front, tap the notification; (g) press **Done**: the notification goes. Record (a) to (g). Settles: AC 1
  (part), ADR 0006 lifecycle.
- [ ] **6.14 Blur.** Switch **Settings → Overlay → Blur the video behind** on and play a busy, bright video. Expect:
  the video behind the overlay is blurred, the backing stays as opaque as the opacity setting says, text is
  readable. The row is hidden if the tablet does not allow blurring; write that down if so (it is also hidden while
  battery saver is on). Look at the rounded corners: the blur covers the window's rectangle, so some blurred video
  may show behind them. Check the drag still feels smooth with blur on (repeat 6.5 once). Switch it off again.
- [ ] **6.15 Bounds, rotation, cutouts and split screen.** Rotate the tablet (all four orientations the video app
  allows): the overlay stays fully on screen, on the side it was on, with the 16 dp margin. If the tablet has a
  camera cutout, check the overlay does not cover it in any orientation (the window does not subtract cutouts).
  Put the video app in split screen with Overdrive: the overlay is hidden while Overdrive is visible in the other
  half (ADR 0006 counts split screen as "on screen"); write down whether you would want it shown instead. Settles:
  OV 9.11, ADR 6 (unverified table).
- [ ] **6.16 The video app keeps working under the overlay.** With the overlay on screen: type in the video app's
  search box (the keyboard must not lose focus to the overlay), use its Back button and the system Back gesture, scrub
  and tap controls close to the overlay's edges and under where a corner of it would be. Expect: everything outside
  the overlay's rectangle works as without it, and tapping the overlay itself does not reach the video. Settles:
  spec §4.6 (never takes input focus).
- [ ] **6.17 Without the notification.** Deny notifications (App info → Notifications off), start a ride, leave for the
  video app. Expect: the overlay still shows and the ride goes on; the notification is not in the shade but the app
  is listed under active apps in the Task Manager. Then allow notifications again. Settles: ADR 0006 notification.

---

## Session 7: a whole ride, FIT export and intervals.icu (AC 2, 3, 6)

Everything a ride is, once, on all three devices, then the file it leaves.

- [ ] **7.1 A whole workout ride on all three devices.** Select the bundled **Sweet spot 3x10** (or your own workout file
  with a steady step, a ramp and an interval set). With the trainer, the strap and the Click connected, press **Start**, put
  a video app full screen and ride the whole workout, at least 20 minutes. Use the Click during the ride: gear up and down
  in a free step, intensity in an ERG step, a next and a previous interval, a pause and a resume, the volume; and pause with
  the on-screen button once. **Finish** the ride (or let the workout end) and wait for "Saved on this tablet". Expect: the
  trainer follows each target (3.3b), every Click action does what 5.6 says, the overlay shows the ride throughout, and the
  finished screen has the summary and one lap per step. Write down anything that did not happen and the time. Settles: AC 2
  and AC 3 end to end, AC 1 (part: the 60-minute version is 6.8).
- [ ] **7.2 Export.** Use the ride of 7.1 (or the recovered ride from session 8). On the finished screen, wait for "Saved
  on this tablet" and press **Share FIT file**: the system share sheet opens; send the file to an app you can reach from the PC (a file manager's "copy to", email to yourself). Then press
  **Save to folder**: the system file picker opens with the name `YYYY-MM-DD HH-mm <workout name>.fit`; save it to
  Downloads and `adb pull` it to `~/bench/`. After leaving the summary with **Done**, the same two actions are in the
  **Last ride** row of the Ready screen. Expect: a file named for the date, time and workout (not `ride-<number>`), a
  few hundred kilobytes at most, and that its extension is `.fit`, not `.bin`. Write down whether the share sheet showed
  the friendly name and whether the receiving app could open the file. Settles: spec §4.8 (files can be shared or saved to
  any folder), ADR 0008.
- [ ] **7.3 Import into intervals.icu.** Upload the file to the owner's intervals.icu account. Expect: the activity
  appears. Settles: AC 6 (part), FIT 1 (protocol version byte: the import is accepted, or the error is written down).
  If it is refused, the first suspect is `FitOptions.protocolVersion` (`0x20`; try `0x02`) and then the manufacturer
  (`FitOptions.manufacturer`, 255 is `development`, FIT 5).
- [ ] **7.4 Power, heart rate and cadence (AC 6).** Open the activity. Expect: the power, heart rate and cadence
  graphs all have data over the ride, and match the overlay roughly. Write down any graph that is empty. Settles:
  AC 6, FIT 3 (speed fields: `FitOptions.speedFields`), FIT 4 (HRV placement: `FitOptions.hrvPlacement`, if RR was
  captured in session 4 and 7.1). Paused seconds are in the file as records; a pause that intervals.icu counts as riding
  time is a finding to write down (ADR 0007).
- [ ] **7.5 Laps and sport.** Expect: one lap per workout step, as spec §4.8 says. The sport is cycling, and the
  sub-sport is virtual (spec §4.8). Write down how intervals.icu labels the activity: virtual, indoor or outdoor.
  Settles: AC 6 (laps), FIT 2 (sub-sport: `FitOptions.subSport`, 58 or 6), FIT 6 (which event and lap messages import
  needs: `FitOptions.timerEvents`, `stepLapTrigger`). Also look at the start time of the activity: if it is off by
  the UTC offset, `FitOptions.utcOffsetSeconds` is not set by the app; if the first or last second is off by one,
  `FitOptions.recordStamp`.
- [ ] **7.6 Distance and speed.** Expect: the distance matches the overlay's distance. Write down any warning on
  the import about distance and speed (FIT 7). Settles: FIT 7.
- [ ] **7.7 Optional, Strava virtual ride and map.** Upload the same file from the Strava web page, manually (Phase
  1 does not upload anything). Write down whether Strava keeps it as a virtual ride, and whether a map shows. Expect:
  unknown (spec §9 "Strava map for virtual rides"). Strava's sub-sport choice is FIT 2. Not needed for acceptance.
- [ ] **7.8 Waiting to upload (spec §4.9).** Open **Settings, Sync**. Expect: **Service address** and **Token** both say "Not
  used yet", and **Waiting to upload** counts the rides stored on the tablet (the finished ride of 7.1 is one of them; a
  tablet updated from an alpha also queues every ride that was already stored, simulated ones included, once). Nothing is
  sent: the app asks for no network permission (`adb shell dumpsys package app.overdrive.ride`, look for
  `android.permission.INTERNET` in the permission lists: expect it absent; unverified output format). Type an address like
  `https://example.org/api` and a token: its scheme and host are kept in lower case, `https://user:pw@host` is refused with a reason,
  the token shows as dots until **Show**. Force-stop and relaunch (8.2): the count is unchanged and nothing is lost or
  doubled, and the address and token are still there. Settles: spec §4.9 (a persistent outbox that survives restarts),
  ADR 0012.

---

## Session 8: crash recovery (AC 5)

- [ ] **8.1 Start and build a record.** Start a ride with some pedalling, and let it run for at least five
  minutes. Expect: nothing on screen says anything about saving (the ride is written to the app's private
  `files/rides/` as it happens, ADR 0008). If the riding screen shows "This ride is not being saved", write down when
  and what the tablet's storage looked like: that is the log failing. Note the time you force-stop it.
- [ ] **8.2 Force-stop.** Stop the app while riding:
  ```
  adb shell am force-stop app.overdrive.ride
  ```
  Expect: the app and the overlay disappear. Note the time. Optional **(unverified)**: for a debuggable build,
  `adb shell run-as app.overdrive.ride ls -l files/rides` shows `<id>.ridelog` and `<id>.meta`; the release APK does not
  allow `run-as`, and the next step shows what the app found anyway.
- [ ] **8.3 Relaunch and recover (AC 5).** Open the app again. Expect: the Ready screen has a notice, "An interrupted
  ride was found", with the date, the start time and the length of the ride, and **Save** and **Discard**. The length
  matches the time up to the force-stop (within a few seconds). Press **Save**. Expect: the notice says "Saving the
  ride…", goes away, and the **Last ride** row shows the ride with **Share** and **Save to folder**. Save the file to a
  folder and open it in intervals.icu in 7.2 to 7.5: its samples and laps should look right. Also try **Discard** on
  a second cut-off ride: it asks "Discard this ride?" first, and the ride is gone afterwards. A ride under one minute is
  deleted without a question: force-stop within the first 30 seconds of a ride and relaunch, expect no notice.
  Settles: AC 5, spec §4.2 (crash safety).
- [ ] **8.4 Swipe away.** Repeat 8.1, then swipe the app away from recents instead of force-stopping. Expect: the
  foreground service keeps running, and the overlay and trainer connection stay up. If they do not, write down
  what stopped and when. Whether the service restarts is unverified (overlay-notes §6.5). Settles: OV 9.6.

- [ ] **8.5 The outbox survives it.** After 8.3, open **Settings, Sync**. Expect: **Waiting to upload** includes the ride
  you saved from the notice (a ride that was discarded is not counted), and the count is the same after one more
  force-stop and relaunch. Settles: spec §4.9, ADR 0012.

---

## Session 9: wrap-up, and what to send back

- [ ] **9.1 Logs.** Send the log files for each session, and the snoop logs from sessions 3 to 5. Include the
  `adb bugreport` zips for any failure.
- [ ] **9.2 Bluetooth session logs: how to send them back.** The files are the evidence for the open questions FT 1 to
  FT 12 and CK 1 to CK 14, so they are the most useful thing you can send. On the tablet: **Settings, Developer, Share
  Bluetooth logs**. The row says how many files and how big ("6 files · 340 KB"); **Share** opens the system share sheet
  with every file, and you choose where they go (email to yourself, a messaging app, a cloud drive). Expect: files named
  `ble-<date>T<time>Z-trainer.txt`, `...-strap.txt` and `...-click.txt`, one per connection session, readable text starting
  with `# overdrive-ble-session 1`. They hold no device address and no serial-like word, a name only as a cleaned model
  line, **and they hold your heart rate and RR intervals**: send them only to whoever is fixing the codecs and keep them out
  of the repository and out of any public issue. If there is no share target, `adb` cannot read the app's private folder in
  a release build, so use Save to a folder from a file manager that accepts the share sheet, or ask for a debug build. Tell
  the receiver which session each file belongs to (the date and time in the name match your logcat file), the firmware of
  the Click, and the results table below.
- [ ] **9.3 Screenshots and recordings.** Send the screen recordings of the overlay tests (6.1, 6.2) and any screenshots of
  the Click row and notice (5.3), the Learn buttons screens (5.5) and Settings, Sync (7.8).
- [ ] **9.4 The FIT file.** Remove anything personal before sending. The owner's name, account details and any strap or
  trainer identifiers must not go into a shared file. Do not send the file through the repository.
- [ ] **9.5 Privacy check.** No raw recordings of the owner's body (heart rate, RR intervals) go into the repository
  (`CLAUDE.md`). Send them privately, not in a commit. Remove MAC addresses and serial numbers before sharing any file.
  The sync token (Settings, Sync) is a secret: never put it in a message with the logs.
- [ ] **9.6 Results.** Fill in the results table below and send it with the logs. Then write the date and outcome of each
  acceptance criterion into `docs/phase1-acceptance.md`.

---

## Coverage: what settles what

### Acceptance criteria (`docs/phase1-acceptance.md`)

| AC | Owner check | Settled by |
|---|---|---|
| 1. 60-minute free ride, video full screen, overlay visible | 60 min over a real video app | 6.1, 6.8, 6.9, 6.10, 6.13, 1.6, 7.1 (part) |
| 2. ERG holds target through steady, ramp and interval steps | Same workout on the D500 | 3.3, 3.3b, 3.5, 3.10, 7.1 |
| 3. All ten Click actions work from the right controller in the background | Each action with the video app in front | 5.5, 5.6, 5.7, 5.11, 7.1 |
| 4. Trainer power pulled mid-ride pauses; resumes with no data lost | Unplug the trainer | 3.11, 3.12, 3.13, 3.14 |
| 5. Force-stop mid-ride, relaunch recovers the ride | Force-stop, relaunch | 8.1, 8.2, 8.3, 8.5 |
| 6. FIT opens in intervals.icu with power, HR, cadence and laps | Import into intervals.icu | 7.2 to 7.5 |
| 7. Overlay drag drops no frames at the tablet's refresh rate | `dumpsys gfxinfo` while dragging | 6.5, 6.6, 6.14 |

### Spec §9 open checks (`docs/spec.md`)

| SP9 row | Settled by | Notes |
|---|---|---|
| Click v2 unlock bypass | 5.2, 5.3, 5.4 | The no-unlock handshake is not in public code (spec implementation notes). The bench is the only test; the app reports a lock and never works around it. |
| Click firmware | 5.1 (record the version) and the rule "never update" | Not testable on the bench without updating the Click; do not. |
| Click v2 reliability | 5.8, 5.10, 5.11 | |
| RR intervals from the Coospo strap | 4.2 | |
| Sleep stages in Health Connect | none | Companion app, not on the bench. |
| HRV from the watch | none | Watch probe, not on the bench. |
| Strava map for virtual rides | 7.7 | Optional. |
| Gear feel | 3.6 | Tune by feel; record the chosen gear count and step. |
| ZUI background limits | 6.9, 6.10, 6.11, 6.12, 6.17, 1.3, 1.6, 5.11 | |
| Literature citations | none | Desk task. |

### The Click questions (`docs/protocols/click-v2.md` §9)

| CK | Settled by | CK | Settled by |
|---|---|---|---|
| 1 side bytes | 5.1 | 8 unlock duration | 5.4 |
| 2 handshake without a response | 5.2 | 9 (firmware) | 5.1, rule "never update" |
| 3 reply length, MTU | 5.2 | 10 type `0x3C` | 5.9 |
| 4 reply version | 5.2 | 11 battery | 5.9 |
| 5 button bits | 5.5 | 12 service hidden, advertised | 5.1, 5.2, 5.3 |
| 6 `FF 04 00` | 5.2, 5.9 | 13 | 5.6 (the ten actions) |
| 7 heartbeat, keep-alive | 5.8, 5.10 | 14 silent means locked | 5.2, 5.3 |

---

## Results

Fill this in on the bench. A criterion counts as confirmed only after its owner check is done on the device
(`docs/phase1-acceptance.md`).

**First report from the bench, 2026-11-18 (informal, from the owner's own words; none of the steps below was run as
written, so no row of the table is filled in from it and no acceptance criterion is confirmed by it).** With the signed
`v0.1.0` on the tablet: the trainer pairs and connects; the overlay works; the Click's gear buttons change gears and its
media buttons work. The Click first showed **Could not connect** and retried; after changes in **Developer · Click**
(which one was needed was not noted; the Click's session file, step 9.2, would show the reply to `RideOn`) the link came
up and the row then sat on "checking it answers" **until any button was pressed**: a real Click sends nothing before
the first press, which is not the silence of a locked one. The row now says "press any button" and the silent notice starts
with that hint (this change is in the release after `v0.1.0`). Not reported, so still open: numbers and logs, hold timing,
the app in the background and with the screen off, a 60-minute ride, FIT export and import, crash recovery.

| Item | Pass / fail | Date | Notes |
|---|---|---|---|
| 1.1 Signature | | | |
| 1.2 Install | | | |
| 1.3 Restricted settings | | | |
| 1.4 Overlay permission | | | |
| 1.5 Notification permission | | | |
| 1.6 Battery optimisation | | | |
| 2.1 Nearby devices permission | | | |
| 2.2 Bluetooth off before pairing | | | |
| 3.1 Pairing | | | |
| 3.2 Feature bits | | | |
| 3.3 ERG holds target (AC 2) | | | |
| 3.3b Ramp and interval steps (AC 2) | | | |
| 3.4 Control replies | | | |
| 3.5 Start or Resume | | | |
| 3.6 Gear feel | | | |
| 3.7 Simulation limits | | | |
| 3.8 Cadence, speed, distance | | | |
| 3.9 Auto-pause | | | |
| 3.10 Zero watts when paused | | | |
| 3.11 Trainer dropout (AC 4) | | | |
| 3.12 Reconnect | | | |
| 3.13 Bluetooth off and permission | | | |
| 3.14 Power-cycle while idle | | | |
| 3.15 No trainer, no ride | | | |
| 3.16 Foreground service types | | | |
| 3.17 First recorded ride, share logs | | | |
| 4.1 HR pairing | | | |
| 4.2 RR intervals | | | |
| 4.3 Contact | | | |
| 4.4 HR dropout is a gap | | | |
| 4.5 MTU | | | |
| 5.1 Discovery and pairing | | | |
| 5.2 Plain handshake | | | |
| 5.3 Unlock notice | | | |
| 5.4 Unlock duration | | | |
| 5.5 Button bitmap, Learn buttons | | | |
| 5.6 Ten actions in the background (AC 3) | | | |
| 5.7 Hold timing | | | |
| 5.8 Keep-alive and reconnect | | | |
| 5.9 Message types, battery | | | |
| 5.10 Reliability in a ride | | | |
| 5.11 Screen off and the background | | | |
| 5.12 Forget, pair again, changed option | | | |
| 6.1 Overlay over video | | | |
| 6.2 Protected video | | | |
| 6.3 Legibility | | | |
| 6.4 Tap and long press | | | |
| 6.5 Drag frames (AC 7) | | | |
| 6.6 Binder calls in drag | | | |
| 6.7 Snap and position | | | |
| 6.8 60-minute ride, screen on (AC 1) | | | |
| 6.9 Screen off, unplugged | | | |
| 6.10 Restricted bucket | | | |
| 6.11 Battery use | | | |
| 6.12 Open the app from another app | | | |
| 6.13 Lifecycle of the window | | | |
| 6.14 Blur | | | |
| 6.15 Bounds, rotation, split screen | | | |
| 6.16 Video app keeps working | | | |
| 6.17 Without the notification | | | |
| 7.1 A whole workout ride (AC 2, 3) | | | |
| 7.2 Export | | | |
| 7.3 Import | | | |
| 7.4 Power, HR, cadence (AC 6) | | | |
| 7.5 Laps and sport | | | |
| 7.6 Distance and speed | | | |
| 7.7 Strava (optional) | | | |
| 7.8 Waiting to upload | | | |
| 8.1 to 8.3 Crash recovery (AC 5) | | | |
| 8.4 Swipe away | | | |
| 8.5 The outbox survives it | | | |

**Recording confirmed items.** When an owner check (AC 1 to 7) passes on the device, write the date and the
outcome into the table at the bottom of `docs/phase1-acceptance.md`. When a spec §9 check is answered, write
the answer into the release notes and into `docs/spec.md`. The protocol notes take their own results:
add the date, the firmware version and the snoop-log file name to `docs/protocols/click-v2.md` §9, and the
same kind of entry to `docs/protocols/ftms-hr.md` §9 and `docs/protocols/fit.md` §8. Keep the snoop logs
themselves out of the repository.

---

## Where the sources disagree

Read these before the bench, so a surprising result is not taken as a bug in the checklist.

- **Click firmware.** Spec §2 says firmware 1.3 and later needs a daily unlock. The click-v2 notes say that
  claim is in the spec only: the public code uses 1.1.0 and 1.2.0 as the reference versions (click-v2 §2).
- **BikeControl 6.0.0.** Spec §2 says it "reports the right controller working without unlock". The click-v2
  notes and the spec's own implementation notes say the 6.0.0 changelog only dropped the unlock for Pro users.
  The right-puck mode comes later (6.4.0). The no-unlock handshake is in closed code.
- **Where the no-unlock method comes from.** Spec §9 says it can be settled by reading the BikeControl
  repository. The implementation notes say it is not in the open-source code. Treat 5.2 as the only test.
- **`FF 04 00` after the handshake.** trainer-control and the newer BikeControl write it to a Click v2, the GPL-3.0 tag does
  not, and nobody knows what it means (click-v2 §3.1). The app writes it by default (`ClickOptions.sendPostHandshake`) so the
  first run has every chance of working; 5.2 and 5.9 compare with and without; it is the **Setup message** switch.
- **FIT writer status.** The writer exists since M2b-1 (`app.overdrive.core.fit`, ADR 0007) and the app's **Share FIT
  file** and **Save to folder** buttons since M2b-2 (ADR 0008). Confirm that the release under test has them (the finished
  screen and the Last ride row) before session 7. Each open FIT question is a field of `FitOptions`, so a finding in
  session 7 is a one-line change of its default.
- **Heart-rate contact.** The SIG XML says only status 3 means contact is detected. pycycling reads any non-zero
  value as contact (ftms-hr §6.5). The codec follows the XML (`HeartRateContactRule.DEFAULT`, one line to flip).
  Step 4.3 settles it.
- **Cadence flag bit.** The SIG XML labels bit 1 as cadence and bit 2 as average speed; pycycling and Ewoc read them the
  other way round (ftms-hr §2.4). The codec follows the two decoders (`IndoorBikeFlagMap.DEFAULT = DECODERS`, one line to
  flip). Step 3.8 settles it.
- **Overlay frame-stats output.** The `dumpsys gfxinfo` commands in acceptance criterion 7 come from overlay-notes
  §5.4, and that section marks their output format unverified. Record the output as printed.
- **Does the Click need a keep-alive?** The licensed tag writes nothing to the controller after the handshake. The newer
  open code sends a neutral frame to a host it emulates a controller for, which says nothing about a real controller
  (click-v2 §5). The app sends none by default (`keepAliveInterval` null, **Keep-alive: Off**); 5.8 decides.
- **Which byte says left and right.** The notes' `0x0A` for the right puck and `0x0B` for the left come from one reading of
  the advertisement and are marked unconfirmed (click-v2 §1). 5.1 settles it, and a swap is two numbers in `ClickOptions`.
- **A media key from a background process.** The app presses play and pause and the volume with `AudioManager`, which
  needs no permission; whether a video app takes a media key from a process that is not in front is not in any source
  (overlay-notes §7). 5.6 is the only test.
- **What the stored rides at an update mean to the outbox.** A tablet updated from an alpha has rides the outbox has never
  seen, simulated ones among them, and queues each once at the first start (ADR 0012 §6). That is by design, not a
  duplicate: the count in 7.8 is the number of rides stored, and no ride is ever queued twice.

---

## Crosswalk from the M3 numbering

The released notes of `v0.1.0-alpha.2` and `v0.1.0-alpha.3` refer to the numbers of the M3 version of this list. They map
as follows; every step keeps its text, and sessions 3 and 4 are unchanged.

| M3 version | This list |
|---|---|
| 1.1 to 1.6 | 1.1 to 1.6 |
| 1.7 Nearby devices permission | 2.1 |
| 2.n overlay (session 2) | 6.n (session 6) |
| 3.n trainer, 4.n strap | 3.n, 4.n |
| 5.n Click | 5.n (5.11 and 5.12 are new) |
| 6.n crash recovery (session 6) | 8.n (session 8) |
| 7.n FIT export (session 7) | 7.(n+1); 7.1 is new, 7.8 is new |
| 8.n wrap-up (session 8) | 9.n (session 9) |
