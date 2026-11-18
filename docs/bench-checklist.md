# Bench checklist

For the owner, at the bench. Work top to bottom: later sessions assume the earlier ones passed.
Each step is a checkbox. Record every result in the table at the end, including passes, so the
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

Anything marked **(unverified)** is a command or outcome the sources do not confirm. Run it, observe,
and write down what you see. Do not treat it as known.

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
- The Zwift Click v2. Note whether it has been unlocked through Zwift today (spec §4.7).
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
- Raw BLE session recorder: its files will be written by the M3 build. The location is not defined in the
  research notes; **see the M3 release notes** for where to find them and how to pull them.

**Conventions for results.** Each step ends with **If different**: write the screen you saw, the time,
the session number and the log file name. Do not change the expected result to match what happened.

---

## Session 1: install and permissions

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

## Session 2: overlay over a real video app

Use the build you are testing. The overlay checks do not need the trainer: the build rides a simulated one.
The service starts from the ride screen when a ride is live (ADR 0006), so start the ride from the ride screen,
then switch to the video app. The overlay is **not** drawn while Overdrive itself is on screen, and appears
about 0.3 s after you leave it. The overlay button in the top bar is only an in-app preview and has nothing to
do with the window. Demo speed (Settings, developer group) runs a ride 10 or 60 times faster if you want to see
a workout's steps without waiting.

- [ ] **2.1 Overlay over video.** Start a ride, then open a video app full screen. Expect: the overlay
  stays visible on top. Settles: AC 1 (part), OV 9.2 (for protected video, see the next step).
- [ ] **2.2 Protected video.** Repeat with a DRM-protected streaming app. Expect: the overlay is drawn
  over it. Take a screen recording. Write down whether the overlay appears in the recording. Settles: OV 9.2.
- [ ] **2.3 Legibility.** Look at compact and detailed mode over dark and bright scenes at arm's length
  and at the bike's viewing distance. Expect: power, heart rate, cadence and time are readable at a
  glance. Write down any value you cannot read. Settles: spec §4.6 (readable over video), spec §7.4.
- [ ] **2.4 Tap and long press.** Tap the overlay: it toggles compact and detailed once, without animation,
  and the mode is still the same after you reopen the app (Settings → Overlay → Mode). Long-press it for half a
  second without moving: the full app comes to the front (no haptic, the app appearing is the feedback). Expect:
  both work from the overlay while a video app is in front. If the long press does nothing, record it and try the
  notification tap (2.12). Settles: spec §4.6; OV 9.5 (background start from the overlay's touch handler).
- [ ] **2.5 Drag smoothness (AC 7).** Run these three commands around a 10-second drag of the overlay.
  The app must be a release or non-debuggable build, or the numbers are not valid (overlay-notes §5.4).
  ```
  adb shell dumpsys gfxinfo app.overdrive.ride reset
  # drag the overlay continuously for 10 s
  adb shell dumpsys gfxinfo app.overdrive.ride framestats
  ```
  Expect: no "Janky frames" during the drag. The output format is **unverified** in the research notes,
  so write down the janky-frame line as printed, and the frame count. Run the drag again at each refresh
  rate the tablet offers (60, 90, 120 Hz, OV 9.4). Write down which rate was active.
- [ ] **2.6 Binder calls during drag (AC 7).** Same drag, with IPC tracing (documented):
  ```
  adb shell am trace-ipc start
  # drag the overlay for 10 s
  adb shell am trace-ipc stop --dump-file /data/local/tmp/ipc-trace.txt
  adb pull /data/local/tmp/ipc-trace.txt ~/bench/
  ```
  Expect: no binder call per move from the drag handler (OV 9.3). Settles: AC 7, OV 9.3.
- [ ] **2.7 Snapping and position memory.** Drag the overlay by its middle: it follows the finger 1:1 and
  never leaves the screen. Let go in the left half: it slides (about 0.2 s) to the left edge with a 16 dp
  margin; in the right half, to the right edge. Drag it to the bottom, tap it to go detailed: it must grow
  upward and stay on screen, and tap again to come back down. Press Home, reopen the app and leave again: the
  overlay comes back at the same place in the same mode. **Settings → Overlay → Position → Reset** puts it back
  top right. Write down any jump at the moment of a bottom-edge resize (ADR 0006 expects at most one frame).
  Settles: spec §4.6; OV 9.11.
- [ ] **2.8 Hour-long survival, screen on (AC 1).** Start a 60-minute free ride with the video app full
  screen and the screen on. Keep the ride log running. Expect: the overlay stays visible for the whole
  hour, and the ride finishes. Note any gap, and the time of the gap. This is the owner check for AC 1
  (with the trainer connected, if the build has one).
- [ ] **2.9 Screen off and unplugged.** Start a 30-minute ride, switch the screen off with the power
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
- [ ] **2.10 Restricted bucket.** During a ride, force the app into the restricted standby bucket:
  ```
  adb shell am get-standby-bucket app.overdrive.ride
  adb shell am set-standby-bucket app.overdrive.ride restricted
  ```
  Expect: the overlay, the trainer connection and the notification keep working. Write down any change.
  Put the bucket back to the value you read first (the restore command is not in the research notes;
  check the output of `am get-standby-bucket` after the change). Also, optionally,
  `adb shell cmd appops set app.overdrive.ride RUN_ANY_IN_BACKGROUND ignore` (documented), and restore it
  afterwards with the matching command (unverified). Settles: OV 8.1, OV 9.7.
- [ ] **2.11 Battery use.** Record the app's battery use before and after a 60-minute ride with the
  overlay visible:
  ```
  adb shell dumpsys batterystats app.overdrive.ride
  ```
  (unverified: the research notes list this command as unverified, and it may print a lot of text.
  Keep the output and compare the two snapshots.) Write down the percentage of battery used. Settles:
  spec §7.3 (battery is a requirement), OV 8.3.
- [ ] **2.12 Open the app from another app (long press and notification).** With the video app in front, long-press
  the overlay (as 2.4), then go back to the video app and pull down the shade and tap **Ride in progress**.
  Expect: the long press brings Overdrive to the front. Android's rules for starting an activity from the
  background list "has a visible window" and "holds `SYSTEM_ALERT_WINDOW`" as exemptions, but a blocked start gives
  no error, only the log line `Background activity launch blocked!` (`adb logcat | grep -i "launch blocked"`), and
  nothing says what ZUI adds on top. The notification tap is the documented fallback: expect it to work either
  way. Record both results, and the Android and ROM version. Settles: spec §4.6, OV 9.5, ADR 6 (unverified table).
- [ ] **2.13 Lifecycle of the window.** Start a ride on the ride screen, then check each of these in turn:
  (a) with Overdrive in front there is no window; (b) press Home or open the video app: the window appears within
  about half a second; (c) come back to Overdrive: it disappears at once; (d) rotate the tablet while in Overdrive:
  the overlay must **not** flash; (e) pause the ride in Overdrive, then leave for the video app: the overlay shows
  the paused look; (f) let a workout end (Demo speed 60× is fine) while the video app is in front: the window goes,
  the app comes to the front with the summary and the notification says "Ride finished"; if the app did not come to
  the front, tap the notification; (g) press **Done**: the notification goes. Record (a) to (g). Settles: AC 1
  (part), ADR 0006 lifecycle.
- [ ] **2.14 Blur.** Switch **Settings → Overlay → Blur the video behind** on and play a busy, bright video. Expect:
  the video behind the overlay is blurred, the backing stays as opaque as the opacity setting says, text is
  readable. The row is hidden if the tablet does not allow blurring; write that down if so (it is also hidden while
  battery saver is on). Look at the rounded corners: the blur covers the window's rectangle, so some blurred video
  may show behind them. Check the drag still feels smooth with blur on (repeat 2.5 once). Switch it off again.
- [ ] **2.15 Bounds, rotation, cutouts and split screen.** Rotate the tablet (all four orientations the video app
  allows): the overlay stays fully on screen, on the side it was on, with the 16 dp margin. If the tablet has a
  camera cutout, check the overlay does not cover it in any orientation (the window does not subtract cutouts).
  Put the video app in split screen with Overdrive: the overlay is hidden while Overdrive is visible in the other
  half (ADR 0006 counts split screen as "on screen"); write down whether you would want it shown instead. Settles:
  OV 9.11, ADR 6 (unverified table).
- [ ] **2.16 The video app keeps working under the overlay.** With the overlay on screen: type in the video app's
  search box (the keyboard must not lose focus to the overlay), use its Back button and the system Back gesture, scrub
  and tap controls close to the overlay's edges and under where a corner of it would be. Expect: everything outside
  the overlay's rectangle works as without it, and tapping the overlay itself does not reach the video. Settles:
  spec §4.6 (never takes input focus).
- [ ] **2.17 Without the notification.** Deny notifications (App info → Notifications off), start a ride, leave for the
  video app. Expect: the overlay still shows and the ride goes on; the notification is not in the shade but the app
  is listed under active apps in the Task Manager. Then allow notifications again. Settles: ADR 0006 notification.

---

## Session 3: trainer over BLE (AC 2, 4; M3)

Close Zwift and any other app that connects to the D500 first (spec §2). The D500 accepts one client.
Turn the HCI snoop log on for this whole session.

- [ ] **3.1 Pairing.** Open the setup screen, scan, and pick the D500. Expect: it appears in the list and
  pairs. Close the app and relaunch: the trainer should connect without any action (spec §4.1). Write the
  trainer's name and the time to connect. Do not write its MAC address into any file that might be shared
  (ftms-hr §7.1).
- [ ] **3.2 Feature and status.** The app reads the Fitness Machine Feature and Status characteristics
  (spec §4.1). Check the logs for the feature bits. Expect: the bits needed for ERG and simulation are
  set (ftms-hr §10 asks for these bit checks before ERG and gears). Write down what the app does if a needed
  bit is missing. Settles: the precondition in ftms-hr §10.
- [ ] **3.3 ERG holds target (AC 2, part).** Start a steady workout step at a set target, for example
  100% of FTP. Read the D500 display and the app's power. Expect: the trainer holds the target for the whole
  step. The tolerance is not defined in the sources. Judge it on the display and write down the range you
  saw. Settles: AC 2 (on the D500), FT 4 (write status `0x08` after a target write).
- [ ] **3.3b Ramp and interval steps (AC 2).** Load a workout file with a steady step, a ramp and an interval
  set (the native JSON or a Zwift ZWO file, spec §4.5). Run it from the start. Expect: each step's target shows on
  the overlay, and the trainer's power follows each target through the ramp and every interval. Write down any
  step where power does not follow. Settles: AC 2 (every step type).
- [ ] **3.4 Control point replies.** In the snoop log, find the replies to the target and simulation
  writes. Expect: the `0x80` indication arrives. Note the time from write to indication (median and the
  worst of several), since the research notes say the timeout depends on this. Settles: FT 6.
- [ ] **3.5 Start or Resume.** Check in the snoop log whether the app sends `0x07` (Start or Resume) and
  whether the trainer needs it before a target (`0x05`). Expect: unknown. Write down the sequence that
  worked. Settles: FT 7.
- [ ] **3.6 Gear feel.** Free ride, flat. Step through the gears one at a time, up and down. Write down, for
  each gear you try, whether the change feels right. The defaults are 24 gears at 1% each, neutral at 12
  (ADR4, spec §4.4). Expect: each gear is clearly different and no step feels like a jump. Also read the
  grade on the D500 display after a gear change: is it honoured at 0.01% steps? Settles: spec §9 "Gear
  feel", FT 5, ADR4.
- [ ] **3.7 Simulation limits.** The app should log the Supported Inclination Range characteristic (`0x2AD5`).
  Find it in the log and write down the min and max. Check whether the 15% maximum in spec §2 is there.
  Then set a negative grade in a free ride and check the value on the D500 display. Expect: a negative grade
  reads as a downhill. Settles: FT 5, FT 8.
- [ ] **3.8 Cadence, speed and distance.** Ride at a steady cadence. Compare the cadence on the D500 with the
  app's cadence. Ride a known distance (for example, a lap of the trainer's own display) and compare with the
  app's distance. Expect: cadence matches. Distance matches within the trainer's own tolerance (unverified).
  Note which flag bit the cadence is in (FT 1, the codec's assumption is bit 2). Settles: FT 1, FT 2, FT 3
  (More Data: check for speed-absent or split notifications in the log).
- [ ] **3.9 Auto-pause (cadence 0).** Ride, then stop pedalling. Expect: the ride pauses after about 3 seconds
  (spec §4.2). The overlay says why (auto). Pedal again: the ride resumes. Watch the log for the cadence the
  trainer reports when you stop: does it send 0, or hold the last value? Expect: 0. If it holds the last value,
  auto-pause never fires. Settles: ADR4 ("reports cadence 0 when the rider stops").
- [ ] **3.10 Zero watts in a paused ERG step.** Start a workout ERG step, then pause it (on screen). Expect:
  the trainer's target drops to 0 W and the trainer accepts it. If it rejects 0 W, the response code appears in
  the `0x80` indication. Record the code. On resume, the target ramps in over about 3 seconds (spec §4.5).
  Settles: ADR4 ("is 0 W accepted"), spec §4.5.
- [ ] **3.11 Trainer dropout (AC 4).** Ride for a few minutes. Switch the trainer off at the wall. Expect:
  the ride pauses at once, and the overlay says the trainer disconnected. Wait 30 seconds, then switch it on.
  Expect: the trainer reconnects by itself and the ride resumes. When the ride is over, the record should show
  the seconds in between as paused samples with no power. Write down the reconnect time. Settles: AC 4, spec
  §4.2 (trainer link), FT 9 (indications still arrive while notifications are on).
- [ ] **3.12 Reconnect without the app restarting.** Repeat 3.11 with a longer gap (two minutes). Expect: the
  reconnect backoff works and the app reconnects without a restart. Note the time of each attempt in the log.
  Settles: spec §4.1 (reconnect with backoff).

---

## Session 4: heart-rate strap (HR7)

- [ ] **4.1 Pairing and first value.** Put the strap on with the electrodes damp. Pair it in the setup screen.
  Expect: a heart-rate value shows on the overlay. Write down the time to the first value.
- [ ] **4.2 RR intervals.** Check the snoop log or the M3 raw recorder (see the M3 release notes) for the
  Heart Rate Measurement flags. Expect: unknown: spec §9 lists RR intervals from the Coospo strap as an
  open check. Write down whether RR bit is set, how many RR values come per notification, and whether they
  look like 1/1024 s units (FT 10). If the app has no RR, record that. Optional: compare the RR average with the
  strap's own report, if you have nRF Connect (spec §9 names it). Settles: SP9 "RR intervals", FT 10.
- [ ] **4.3 Contact.** Take the strap off the chest. Expect: no heart rate is shown, not a stuck value (ADR4:
  0 bpm without contact). Put it back on. In the log, write down the contact status values you see (2 or 3).
  Expect: 3 when on the chest (the SIG XML's reading, ftms-hr §6.5). Settles: FT 11.
- [ ] **4.4 Dropout is only a gap.** During a ride, move the strap away for 20 seconds. Expect: the ride keeps
  going and the heart-rate samples have a gap (spec §4.2). Settles: spec §4.2 (heart-rate dropout never
  pauses).
- [ ] **4.5 MTU and long notifications.** In the log, find the negotiated MTU and whether the largest trainer
  notification (30 bytes) arrives whole. Expect: MTU at least 64, requested at connect (ftms-hr §7). Write down
  the negotiated value. Settles: FT 12.
- [ ] **4.6 Battery service (optional).** The UUID `0x180F` is unverified (ftms-hr §1, FT 13). With nRF Connect,
  check whether the strap exposes it. Write down what you see. Not needed for acceptance.

---

## Session 5: Click v2 (M4 build only)

Skip this session in M3 builds. The Click is delivered with the gesture layer in M4.
**Never update the Click's firmware.** If Zwift Companion offers an update, decline it (spec §9). Write down
the firmware version from the Companion app and the Zwift app version before each run (click-v2 §9).

- [ ] **5.1 Discovery.** Scan with the app's pairing screen, or with nRF Connect. Scan the right puck, then the
  left. Write down each puck's advertised services and the full manufacturer data. Expect: the Zwift custom
  service is advertised. The byte that says which side the puck is (`0x0A` or `0x0B`) is unknown. Settles: CK 1.
- [ ] **5.2 Plain handshake, locked right puck.** Use a right puck with no Zwift connection in the last day.
  Start the app's connect step. Expect: unknown. Write down the reply to the handshake (`RideOn`) and its
  length, and whether buttons arrive without any unlock. Also write down whether the custom service is listed
  in GATT discovery. Settles: CK 2, CK 12, CK 14, spec §9 "Click v2 unlock bypass" and spec §2 ("right
  controller working without unlock").
- [ ] **5.3 Unlock notice.** When the controller is locked, the app must tell you to unlock it through Zwift
  once and continue without the Click (spec §4.7). Expect: the notice shows, and the on-screen controls keep
  working. Write down the exact notice text. Then unlock through Zwift as the notice says, and write down the
  time. Settles: spec §4.7 (unlock handling), CK 8.
- [ ] **5.4 Unlock duration.** Check the controller after 1 hour, 12 hours, 25 hours, and after a phone reboot
  (CK 8). Expect: unknown. Write down when the service hides again, if it does. Settles: CK 8, spec §9 "Click
  firmware" context.
- [ ] **5.5 Button bitmap.** In the log or the snoop capture, press each of the five buttons on each puck in
  turn, one at a time. Expect: a pressed button clears its bit (0 = pressed, click-v2 §4). Write down the
  bitmap for each press. The spec's five buttons are not yet mapped to the bitmap (the mapping is a setting,
  spec §4.7). Write down the mapping you find, including the two Plus bits (`0x1000`, `0x2000`). Settles: CK 5,
  spec §4.7 (assign buttons once observed).
- [ ] **5.6 The ten actions (AC 3).** With the video app in the front (the app is in the background), do each
  action from the right Click and write pass or fail. Expect each row below.

  | Button | Press | Hold |
  |---|---|---|
  | Plus | Gear up | Next interval |
  | Button 2 | Gear down | Previous interval |
  | Button 3 | Play or pause the media | Pause or resume the ride |
  | Button 4 | Volume up | Volume up, repeating |
  | Button 5 | Volume down | Volume down, repeating |

  In ERG steps, the gear buttons change intensity by 5% instead (spec §4.7). Expect: the media key reaches
  the video app. Its behaviour is unverified (overlay-notes §7). Write down whether the video app paused.
  Volume: write down whether the system volume bar appears (unverified, OV 9.9).
- [ ] **5.7 Hold timing.** Time a hold of about 400 ms and one of about 600 ms on a button. Expect: the short hold
  is a press and the long one is a hold (threshold 500 ms, spec §4.7). For the volume repeat, count the repeats in
  a two-second hold. Expect about 13 (repeat every 150 ms). Settles: AC 3 (hold threshold, repeat).
- [ ] **5.8 Keep-alive and reconnect.** Stay connected and idle for 5 minutes, then 60 seconds, then 5 minutes
  again. Note any disconnect and how long it stays down (CK 7). Expect: unknown. Expect the app reconnects
  without a restart. Settles: CK 7, SP9 "Click v2 reliability".
- [ ] **5.9 Message types.** In the snoop log, note when type `0x3C` appears and what follows it (CK 10). Note
  whether battery reports (`0x19`) change as the puck drains (CK 11). Optional: send `FF 04 00` after the
  handshake, if the app allows, and record any reply (CK 6). Expect: unknown.
- [ ] **5.10 Reliability in a ride.** Count the disconnects during the 60-minute ride in 2.8 (or any ride). Expect:
  reconnect without the ride stopping; the on-screen controls still work. Settles: SP9 "Click v2 reliability".

---

## Session 6: crash recovery (AC 5)

- [ ] **6.1 Start and build a record.** Start a ride with some pedalling, and let it run for at least five
  minutes. Expect: the ride is recording (spec §4.2). Note the time you force-stop it. Where the per-second file
  is stored is not defined in the research notes: see the M3 release notes.
- [ ] **6.2 Force-stop.** Stop the app while riding:
  ```
  adb shell am force-stop app.overdrive.ride
  ```
  Expect: the app and the overlay disappear. Note the time.
- [ ] **6.3 Relaunch and recover (AC 5).** Open the app again. Expect: the unfinished ride is recovered, and its
  duration matches the time up to the force-stop (within a few seconds). Its samples are there, and the laps
  look right. Settles: AC 5, spec §4.2 (crash safety).
- [ ] **6.4 Swipe away.** Repeat 6.1, then swipe the app away from recents instead of force-stopping. Expect: the
  foreground service keeps running, and the overlay and trainer connection stay up. If they do not, write down
  what stopped and when. Whether the service restarts is unverified (overlay-notes §6.5). Settles: OV 9.6.

---

## Session 7: FIT export and intervals.icu (AC 6)

- [ ] **7.1 Export.** Finish a ride of at least 10 minutes with a workout (or with the recovered ride from session
  6). Use the app's share or save action and save the FIT file to a folder you can get to from the PC, such as
  Downloads (the exact path is not in the research notes). Then `adb pull` it to `~/bench/`. Expect: a file named
  for the ride. Settles: spec §4.8 (files can be shared or saved to any folder).
- [ ] **7.2 Import into intervals.icu.** Upload the file to the owner's intervals.icu account. Expect: the activity
  appears. Settles: AC 6 (part), FIT 1 (protocol version byte: the import is accepted, or the error is written down).
- [ ] **7.3 Power, heart rate and cadence (AC 6).** Open the activity. Expect: the power, heart rate and cadence
  graphs all have data over the ride, and match the overlay roughly. Write down any graph that is empty. Settles:
  AC 6, FIT 3 (speed fields), FIT 4 (HRV placement, if RR was captured in session 4).
- [ ] **7.4 Laps and sport.** Expect: one lap per workout step, as spec §4.8 says. The sport is cycling, and the
  sub-sport is virtual (spec §4.8). Write down how intervals.icu labels the activity: virtual, indoor or outdoor.
  Settles: AC 6 (laps), FIT 2 (sub-sport), FIT 6 (which event and lap messages import needs).
- [ ] **7.5 Distance and speed.** Expect: the distance matches the overlay's distance. Write down any warning on
  the import about distance and speed (FIT 7). Settles: FIT 7.
- [ ] **7.6 Optional, Strava virtual ride and map.** Upload the same file from the Strava web page, manually (Phase
  1 does not upload anything). Write down whether Strava keeps it as a virtual ride, and whether a map shows. Expect:
  unknown (spec §9 "Strava map for virtual rides"). Strava's sub-sport choice is FIT 2. Not needed for acceptance.

---

## Session 8: wrap-up, and what to send back

- [ ] **8.1 Logs.** Send the log files for each session, and the snoop logs from sessions 3 to 5. Include the
  `adb bugreport` zips for any failure.
- [ ] **8.2 Screenshots and recordings.** Send the screen recordings of the overlay tests (2.1, 2.2) and any
  screenshots of the Click notice (5.3).
- [ ] **8.3 The FIT file.** Remove anything personal before sending. The owner's name, account details and any
  strap or trainer identifiers must not go into a shared file. Do not send the file through the repository.
- [ ] **8.4 Privacy check.** No raw recordings of the owner's body (heart rate, RR intervals) go into the repository
  (`CLAUDE.md`). Send them privately, not in a commit. Remove MAC addresses and serial numbers before sharing
  any file.
- [ ] **8.5 Results.** Fill in the results table below and send it with the logs.

---

## Coverage: what settles what

### Acceptance criteria (`docs/phase1-acceptance.md`)

| AC | Owner check | Settled by |
|---|---|---|
| 1. 60-minute free ride, video full screen, overlay visible | 60 min over a real video app | 2.1, 2.8, 2.9, 2.10, 2.13, 1.6 |
| 2. ERG holds target through steady, ramp and interval steps | Same workout on the D500 | 3.3, 3.3b, 3.5, 3.10 |
| 3. All ten Click actions work from the right controller in the background | Each action with the video app in front | 5.5, 5.6, 5.7 |
| 4. Trainer power pulled mid-ride pauses; resumes with no data lost | Unplug the trainer | 3.11, 3.12 |
| 5. Force-stop mid-ride, relaunch recovers the ride | Force-stop, relaunch | 6.1, 6.2, 6.3 |
| 6. FIT opens in intervals.icu with power, HR, cadence and laps | Import into intervals.icu | 7.1 to 7.4 |
| 7. Overlay drag drops no frames at the tablet's refresh rate | `dumpsys gfxinfo` while dragging | 2.5, 2.6, 2.14 |

### Spec §9 open checks (`docs/spec.md`)

| SP9 row | Settled by | Notes |
|---|---|---|
| Click v2 unlock bypass | 5.2, 5.3, 5.4 | The no-unlock handshake is not in public code (spec implementation notes). The bench is the only test. |
| Click firmware | 5.1 (record version) and the rule "never update" | Not testable on the bench without updating the Click; do not. |
| Click v2 reliability | 5.8, 5.10 | |
| RR intervals from the Coospo strap | 4.2 | |
| Sleep stages in Health Connect | none | Companion app, not on the bench. |
| HRV from the watch | none | Watch probe, not on the bench. |
| Strava map for virtual rides | 7.6 | Optional. |
| Gear feel | 3.6 | Tune by feel; record the chosen gear count and step. |
| ZUI background limits | 2.9, 2.10, 2.11, 2.12, 2.17, 1.3, 1.6 | |
| Literature citations | none | Desk task. |

---

## Results

Fill this in on the bench. A criterion counts as confirmed only after its owner check is done on the device
(`docs/phase1-acceptance.md`).

| Item | Pass / fail | Date | Notes |
|---|---|---|---|
| 1.1 Signature | | | |
| 1.2 Install | | | |
| 1.3 Restricted settings | | | |
| 1.4 Overlay permission | | | |
| 1.5 Notification permission | | | |
| 1.6 Battery optimisation | | | |
| 2.1 Overlay over video | | | |
| 2.2 Protected video | | | |
| 2.3 Legibility | | | |
| 2.4 Tap and long press | | | |
| 2.5 Drag frames (AC 7) | | | |
| 2.6 Binder calls in drag | | | |
| 2.7 Snap and position | | | |
| 2.8 60-minute ride, screen on (AC 1) | | | |
| 2.9 Screen off, unplugged | | | |
| 2.10 Restricted bucket | | | |
| 2.11 Battery use | | | |
| 2.12 Open the app from another app | | | |
| 2.13 Lifecycle of the window | | | |
| 2.14 Blur | | | |
| 2.15 Bounds, rotation, split screen | | | |
| 2.16 Video app keeps working | | | |
| 2.17 Without the notification | | | |
| 3.1 Pairing | | | |
| 3.2 Feature bits | | | |
| 3.3 ERG holds target (AC 2) | | | |
| 3.4 Control replies | | | |
| 3.5 Start or Resume | | | |
| 3.6 Gear feel | | | |
| 3.7 Simulation limits | | | |
| 3.8 Cadence, speed, distance | | | |
| 3.9 Auto-pause | | | |
| 3.10 Zero watts when paused | | | |
| 3.11 Trainer dropout (AC 4) | | | |
| 3.12 Reconnect | | | |
| 4.1 HR pairing | | | |
| 4.2 RR intervals | | | |
| 4.3 Contact | | | |
| 4.4 HR dropout is a gap | | | |
| 4.5 MTU | | | |
| 5.1 Discovery | | | |
| 5.2 Plain handshake | | | |
| 5.3 Unlock notice | | | |
| 5.4 Unlock duration | | | |
| 5.5 Button bitmap | | | |
| 5.6 Ten actions (AC 3) | | | |
| 5.7 Hold timing | | | |
| 5.8 Keep-alive | | | |
| 5.9 Message types | | | |
| 5.10 Reliability | | | |
| 6.1 to 6.3 Crash recovery (AC 5) | | | |
| 6.4 Swipe away | | | |
| 7.1 Export | | | |
| 7.2 Import | | | |
| 7.3 Power, HR, cadence (AC 6) | | | |
| 7.4 Laps and sport | | | |
| 7.5 Distance and speed | | | |

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
- **FIT writer status.** `docs/protocols/fit.md` says the writer is not written yet. The roadmap plans FIT export for
  M2. Confirm which is current before session 7.
- **Heart-rate contact.** The SIG XML says only status 3 means contact is detected. pycycling reads any non-zero
  value as contact (ftms-hr §6.5). The codec follows the XML. Session 4.3 settles it.
- **Overlay frame-stats output.** The `dumpsys gfxinfo` commands in acceptance criterion 7 come from overlay-notes
  §5.4, and that section marks their output format unverified. Record the output as printed.
