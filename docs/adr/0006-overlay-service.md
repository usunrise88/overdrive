# ADR 0006: The overlay service and window

Status: accepted (stage M1-B, 2026-11-18)

## Context

ADR 0005 left the overlay as composables (`OverlayCompact`, `OverlayDetailed`) and an in-app preview. Stage M1-B
turns them into what spec §4.6 describes: a small window drawn over other apps by the ride's foreground service, that
the rider can drag, tap and long-press while the video app keeps working underneath. The research behind the Android
side is `docs/android/overlay-notes.md`; its claims keep their `[documented]` and `[unverified]` tags here. Nothing in
this stage has run on the tablet.

## Decision

### Lifecycle

- **The window exists while a ride is live (riding or paused) and Overdrive is not on screen.** The ride screen already
  shows everything the overlay would, so over the app itself the overlay only covers the controls. Whether the app is on
  screen is the number of started activities (`AppVisibility`, an `ActivityLifecycleCallbacks` on the application, so
  it is also right while no activity exists).
- **The window appears 300 ms after the app goes out of sight and disappears at once when it comes back.** Recreating
  the activity (rotation, a configuration change) stops and starts it within a few frames; without the wait the overlay
  would flash. A test covers both directions in virtual time.
- **The service is started from the visible ride screen** (`EnsureOverlayService(live, overlay)`, a
  `LifecycleStartEffect` in `RideApp` that calls `OverlayController.ensureRunning()`), whenever the ride becomes live while the screen is started, and again each time
  the screen is started during a live ride. The call is idempotent. Android 12 and later refuse
  `startForegroundService` from the background with `ForegroundServiceStartNotAllowedException`; starting it from a
  visible activity is a listed exemption `[documented]`, so the limit never applies. It does nothing without the
  "Display over other apps" permission, and the ride then works exactly as before. A refused start is caught and
  logged: it costs the overlay, never the ride.
- **The service stops itself** (`ServiceStopRule`) when the ride is dismissed (the host is back in `READY` after having
  been live or finished: the **Done** button), when the finished ride's summary is on screen, or 15 s after it started if
  no ride ever showed up (the Start press was ignored, the rider backed out), so no notification is left behind.
  `START_NOT_STICKY`: the ride lives in this process, so there is nothing to restart into if the system kills it.
- **When the ride finishes** while the app is out of sight (a workout that ends), the
  window is removed, the notification changes to "Ride finished. Tap to see the summary", and the service tries to bring
  the app to the front (design/system.md §5.4: the summary is the next thing the rider needs). If the system refuses
  the launch, the notification is still there until the rider opens the app, which stops the service.
- **A known limit.** A ride that begins while the app is not visible (pedalling to start, with real devices in M3)
  cannot start the service from the background, so it has no overlay until the app is opened once. The Ready screen
  says "Start" and the rider starts from the app; the M3 design can start the service on the Ready screen instead if
  the bench shows pedal-start matters.

Rejected: a window that is always present while the service runs, hidden with `GONE` (it keeps a composition and a
surface alive for nothing while the rider is in the app), and a service that starts with the process (blocked by the
background-start limits).

### Foreground service type

`specialUse` in M1, with the required `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property and the permissions
`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`. The notes recommended `connectedDevice`, but that type
needs a Bluetooth runtime permission at the moment the service starts and the simulated ride in M1 has no Bluetooth.
`specialUse` asks Play Console for a justification, which does not apply to a sideloaded app. **M3 moves to
`connectedDevice|specialUse`** with `FOREGROUND_SERVICE_CONNECTED_DEVICE` and `BLUETOOTH_CONNECT` when the BLE adapters
exist; the overlay part keeps `specialUse` because drawing is not a connected device. The Android 15 time limit
(6 hours in 24) applies only to `dataSync` and `mediaProcessing` `[documented]`, so a long ride is not cut off.

### Notification

One channel, "Ride", `IMPORTANCE_LOW` (no sound, no peek), one notification: "Ride in progress" with "Tap to open
Overdrive", ongoing, immediate foreground behaviour so Android 12's 10 s delay for short services does not apply. The tap
opens the app through a `PendingIntent`. `POST_NOTIFICATIONS` is a runtime permission on Android 13 and later and is
requested during onboarding (below). If the rider declines it the service still runs and the overlay still shows; the
notification is hidden from the shade but the service appears in the Task Manager `[documented]`. The small icon is a
hand-written vector drawable of the Lucide `bike` glyph (ISC, already credited) in `res/drawable`, white strokes only
because the system draws small icons as an alpha mask.

### The window

- `TYPE_APPLICATION_OVERLAY`, `WRAP_CONTENT` both ways, translucent, gravity top-start with x and y in screen pixels
  (`OverlayWindowSpec`). Flags: `FLAG_NOT_FOCUSABLE` (the video app keeps its keyboard and Back button),
  `FLAG_NOT_TOUCH_MODAL` (touches outside the overlay's own bounds go to the app behind it, which is why the window must
  wrap its content and never be full screen) and `FLAG_LAYOUT_IN_SCREEN`. **Never** `FLAG_SECURE` (the rider may want
  screenshots and screen recordings of the numbers) and **never** `FLAG_NOT_TOUCHABLE` (the overlay takes its own
  touches, and Android 12 blocks pass-through touches of overlay windows that use it `[documented]`). A test asserts
  the flag set exactly, with and without blur.
- design/system.md §5 listed `FLAG_LAYOUT_NO_LIMITS`; the implementation uses `FLAG_NOT_TOUCH_MODAL |
  FLAG_LAYOUT_IN_SCREEN` instead, because the window is clamped inside the screen anyway and does not need to leave it.
  design/system.md §5 now says so.
- **Opacity** is the composable's backing colour (`OverlaySettings`), never the window's alpha. **Blur** is
  `FLAG_BLUR_BEHIND` plus `blurBehindRadius` (24 dp, token `overlay.blurRadius`) and only when the `overlay_blur`
  setting is on **and** `WindowManager.isCrossWindowBlurEnabled` is true; the service follows the setting and the system
  switch (battery saver turns blur off) through `addCrossWindowBlurEnabledListener`. Settings hides the blur row where
  the system cannot blur, as design/system.md §5.5 asks. The blur covers the window's rectangle, so the rounded corners
  of the backing show blurred video behind them; that has not been looked at on the tablet.
- **The window's context** is `createDisplayContext(defaultDisplay).createWindowContext(TYPE_APPLICATION_OVERLAY)`,
  which gives the window its own configuration and correct metrics, with a fallback to the service context if the
  platform refuses it. Whether the window context gives the right bounds on ZUI is `[unverified]`
  (overlay-notes §4); the bench list has it.
- **Compose in a service window.** A window added by a service has no lifecycle owner, so `OverlayViewOwner` is a plain
  `LifecycleOwner` and `SavedStateRegistryOwner` the service drives by hand (`RESUMED` once, `DESTROYED` after the window
  is removed). It is attached to the window's root view, above the `ComposeView`. This settles overlay-notes open
  question 10 by construction: nothing depends on whether `LifecycleService` dispatches events by itself, and a
  Robolectric test shows the composition running inside a window. No `ViewModelStore`: the overlay has no view model.
  Each time the window is shown a new `ComposeView` is built and it is disposed when the view detaches, so a hidden
  overlay costs nothing.

### Touch, drag and snap

- The window root (`OverlayRootLayout`) takes every touch inside the window and gives the primary pointer's **screen**
  coordinates to a pure classifier (`OverlayGestureDetector`), because the window moves under the finger and view
  coordinates would shift. The composable has no gestures.
- **Thresholds** (tokens, design/system.md §5.5): drag after 8 dp of movement (a distance, not per axis), long press after
  500 ms within the slop, tap on a release inside the slop before 500 ms. A drag never becomes a tap or a long press; a
  late timer is covered because the release itself checks the elapsed time. The long-press timer is the view's
  `postDelayed` and is cancelled on release, on drag start and on hide.
- **Drag** moves the window 1:1: `LayoutParams.x` and `y`, then `updateViewLayout`, nothing else. No state the composition
  reads is touched, the content never recomposes while it moves, and the position is not saved while it moves. The
  screen's bounds are read once per gesture, at the finger-down, because `currentWindowMetrics` is a call into the
  window manager service; a window update is skipped when the rounded position did not change. Android batches touch
  moves to the frame, so one update per move event is one per frame; no extra choreographer throttle. During the drag the
  window is clamped to the screen with no margin.
- **Release** (`OverlayPlacement.snap`): horizontally to the nearer side edge by the window's centre, with the 16 dp margin
  (token `overlay.edgeSnapMargin`); vertically clamped with the same margin (letterbox bars allowed, so the margin is
  from the screen, not from the video). Only x animates: 180 ms, decelerating, by a `Choreographer` frame callback that
  runs for exactly its own duration and stops (`SnapAnimation`). It is replaced by a jump when the system's animations are
  off, and a new touch cancels it. No other animation exists.
- **Position** is persisted when the drag ends (and when a resize at the bottom edge moves the window), as the top-left
  corner over the screen, 0 to 1, in `overlay_x` and `overlay_y`. The controller remembers the fractions and, on a
  rotation or another screen size, re-derives the position from them (so a round trip through portrait comes back to
  where it was) and snaps again. Without a saved position the window starts top right.
- **Tap** toggles compact and detailed and persists `overlay_mode`; the window resizes once with no animation. A window
  at the bottom limit stays at the bottom and grows upward; any other keeps its top (`keepEdgeOnResize`). The corrected
  position is applied after the layout pass that knows the new height, so a bottom-anchored resize can show one frame
  at the old position `[unverified]`.
- **Long press** brings the app to the front: `startActivity` with `FLAG_ACTIVITY_NEW_TASK | REORDER_TO_FRONT` from the
  service. Two documented exemptions to the background-activity-start limit apply: the app has a visible window, and the
  app holds `SYSTEM_ALERT_WINDOW` (developer.android.com, "Restrictions on starting activities from the background",
  read 2026-11-18). A blocked launch gives no exception, only a "Background activity launch blocked!" log line, so there
  is no way to detect it in code. **[unverified]** on the tablet, in particular whether Android 15's tightening of the
  overlay exemption and ZUI's own "pop-up from background" permission (an OEM guess, nothing documented) let it through.
  The fallback is the notification: its `PendingIntent` opens the app and a notification tap is a documented exemption.
  The notification therefore has no action button; the content tap is the fallback.

### What reaches the window, and how often

`overlayStates(rides)` maps `RideUi` with `RideMapping.overlayState`, drops every update that changes nothing the overlay
shows (the engine publishes a snapshot for every sensor reading, and the averages, the trace and the ride number are not
on the overlay), and spaces the rest at least 1 s apart: the first value goes through at once, a value that arrives early
waits for its turn and is replaced by a newer one, and the last value always arrives. **What the rider just did is shown at
once**: a change of the fifth cell (gear, target, intensity) or of the paused state bypasses the limit, because the overlay
is the Click's feedback channel (design/system.md §5.2). The pipeline is collected on `Dispatchers.Default` through
`collectAsState(context = Dispatchers.Default)`, so it also runs only while the window's composition exists. The look
(opacity and mode, nothing else of the settings) reaches the window through its own `distinctUntilChanged` flow, so the
position write at the end of a drag and any other setting change recompose nothing. The window calls, the Compose frame and
the service's own bookkeeping are the only main-thread work; the ride and the BLE adapters (M3) stay on their own thread.

### Permission onboarding

- Manifest: `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` (with the subtype property),
  `POST_NOTIFICATIONS`. No network permission, no Google Play Services.
- **Ready screen:** while "Display over other apps" is missing, a ui-kit `Notice` above the workout says
  "Show numbers over other apps. Allow “Display over other apps” so the overlay stays on screen while you watch video." with
  one button, **Allow**. On Android 13 and later it first shows the system notification question and, whatever the answer,
  then opens `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` (a refused question costs nothing, and a permission the system
  will not ask for again just goes on to the next screen). The row disappears by itself when the rider comes back with the
  permission, because the screen reads `Settings.canDrawOverlays` on every resume (the system screen returns no result).
- **Settings:** rows "Display over other apps" (Allow, or Manage when granted) and "Ride notification" (a state, or Allow,
  which opens the app's notification settings), then the existing overlay settings. The package URI of the intent is passed
  although Android 11 and later ignore it and show the whole list `[documented]`.
- If the permission is not granted nothing else changes: the top bar's overlay button still previews the overlay in the app.
- Only ui-kit components and tokens are used; no token or component had to be added, and nothing generated was touched.
- Restricted settings on Android 13 and later: whether a sideloaded APK's "Display over other apps" toggle is greyed out
  on ZUI is `[unverified]` (overlay-notes §1) and is the first item of the bench list.

### Testing

All on the JVM (Robolectric 4.18, SDK 35), no emulator:

- Pure JUnit4: placement and snap maths with the tablet's numbers and the degenerate cases, the gesture classifier
  (tap, long press by timer and by release, drag, cancel, slop as a distance), the lifecycle policy and the stop rule in
  virtual time, the distinct and rate-limited flow (N identical snapshots, a trainer's 4 Hz readings, the rider-action
  bypass), the app-visibility counter.
- Robolectric: the exact window flags and parameters; the window controller against Robolectric's window manager (real
  `MotionEvent`s to the root: one window, drag follows the finger, release snaps and saves once, nothing saved while it
  moves, tap and long press, the slide, rotation, resize at the bottom edge, blur); the service end to end against a fake
  ride (foreground notification, exactly one window, hides and shows with the app, no flash on a short leave, stops by its
  rules, finish brings the app to the front, tap and position written to the settings); the activity's two effects against a
  fake `OverlayController` (permissions are read again on resume, the service is started only while a ride is live).
- **Performance proof on the JVM** (`OverlayRecompositionTest`, real Compose in a real window): 100 snapshots that show the
  same numbers cause no recomposition after the first frame, one real change causes exactly one, and a 60-event drag moves
  the window through `updateViewLayout` without a single recomposition and saves exactly once on release.
- A Roborazzi screenshot of the Ready row and of the Settings rows (`build/shots/`, never committed).

What the JVM cannot say, and the bench list in `docs/bench-checklist.md` carries: frame time while dragging at the tablet's
refresh rate (spec §4.10 criterion 7), whether a binder call per move shows up, the battery cost of the overlay
(spec §7.3), the blur's cost and look, DRM video, the long press from a video app, the restricted-settings toggle, whether
ZUI keeps the service alive, the window context's bounds and cutouts, split screen, and the one-frame bottom-edge resize.

### R8

The release build is shrunk by R8. The service survives without a rule of our own: AAPT generates
`-keep class app.overdrive.ride.overlay.OverlayService { <init>(); }` from the manifest, which was checked in the release
build's `aapt_rules.txt` and `mapping.txt`, and the manifest of the release APK carries the service, its `specialUse` type and
the subtype property. `ic_ride_notification` is referenced from code, so resource shrinking keeps it.

## Consequences

- The overlay costs nothing while the rider is in the app, and a short app switch never flashes it.
- The ride screen, Settings and the in-app preview are unchanged apart from the two onboarding rows and the hidden blur row.
- A ride that starts while the app is out of sight has no overlay until the app is opened (see Lifecycle). Split screen with
  Overdrive on one side counts as "on screen" and hides the overlay.
- The window is positioned in the display's full bounds with a 16 dp margin; display cutouts are not subtracted.
- The overlay is not usable with TalkBack; it is a read-only instrument panel next to video, and the ride screen carries
  every control.
- M3 changes the service type declaration and adds the Bluetooth permission; the window, gestures and lifecycle do not change.

## Unverified on hardware

| Claim | Where it is settled |
|---|---|
| The sideloaded APK's "Display over other apps" toggle is available on Android 15 ZUI | bench 1.3, 1.4 |
| `createWindowContext` gives the right bounds on the tablet; cutouts and split screen | bench 2.7, 2.15 |
| Long press from the overlay brings the app to the front on Android 15 ZUI | bench 2.4, 2.12 |
| A notification tap opens the app while another app is in front | bench 2.12 |
| Dragging drops no frames at 60, 90 and 120 Hz, and makes no binder call per move | bench 2.5, 2.6 |
| The overlay is drawn over DRM video and appears (or not) in screen recordings | bench 2.2 |
| ZUI keeps the specialUse service alive for an hour with the screen off | bench 2.8, 2.9, 2.10 |
| Blur behind the window looks right and does not cost frames | bench 2.14 |
| The bottom-edge resize shows no visible jump | bench 2.7 |
| Battery overhead is under 3% an hour (spec §7.3) | bench 2.11 |
