# Ride overlay: Android research notes

Research for the ride overlay (spec §4.6, §4.7, §7.3, §9). Input for the implementer. Written as
research only; no project code was changed.

**Legend.** Each statement is one of:

- **[documented]**: stated on developer.android.com (link given). Quoted from the page where noted.
- **[source-confirmed]**: confirmed in AOSP or AndroidX source. None of these were fetched in this
  pass, so no statement carries this tag yet.
- **[unverified]**: not confirmed from a fetched source. Recollection or inference. Treat as a
  hypothesis for the bench test.

**Targets.** minSdk 31 (Android 12), targetSdk 36, compileSdk 37 (`gradle/libs.versions.toml`). Test
devices: Lenovo Xiaoxin Pad Pro 2025 (Android 15, ZUI) and Xiaomi 14 Ultra.

**Coverage limits.** Several developer.android.com reference pages were too long to read in full
through the fetch tool. Where the text was cut off, the item is marked [unverified]. `support.google.com`
and `dontkillmyapp.com` did not resolve from this session, so their content is absent.

---

## 0. Three gotchas first

1. **The overlay must be sized to its content, never `MATCH_PARENT`.** A window that is not
   touch-modal swallows only touches inside its own bounds. A full-screen overlay would take every
   touch on the video app. Move the window with `x`/`y`, not by making it full screen.
   [documented: `FLAG_NOT_TOUCH_MODAL`, `FLAG_NOT_FOCUSABLE`]
2. **Android 15+ FGS exemption needs a visible overlay.** A foreground service may be started from
   the background when the app holds `SYSTEM_ALERT_WINDOW`, but on Android 15+ the app must also
   currently have a visible overlay window. The ride service must therefore be started while the
   ride screen is visible. Starting it cold from boot will fail. [documented]
3. **Overlay windows are not trusted for touch.** A `TYPE_APPLICATION_OVERLAY` window that uses
   `FLAG_NOT_TOUCHABLE` gets its pass-through touches blocked by Android 12+. Our overlay takes
   touches itself and does not use `FLAG_NOT_TOUCHABLE`, so this does not block our own input, but
   it rules out a click-through design. [documented]

---

## 1. Permission and onboarding

| Item | Fact | Status |
|---|---|---|
| Manifest permission | `android.permission.SYSTEM_ALERT_WINDOW`. Summary: "Allows an app to create windows using the type `TYPE_APPLICATION_OVERLAY`, shown on top of all other apps." | [documented] https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW (summary only; the detail section was cut off) |
| Check | `Settings.canDrawOverlays(context)`. Summary: "Checks if the specified context can draw on top of other apps." | [documented] https://developer.android.com/reference/android/provider/Settings |
| Request intent | `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`, API 23. Before Android 11 (R), the data URI `package:<pkg>` opens the screen for this app. From R onward that URI is not honoured as far as the page says, so the user lands on the general list and must find the app. Output: "Nothing". | [documented] https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION |
| Detecting the return | No result is returned, so re-check `Settings.canDrawOverlays` in `onResume()`, not in `onActivityResult`. | Follows from "Output: Nothing" [documented] |
| Android 13+ restricted settings | Sideloaded apps may have restricted settings ("Allow restricted settings"). Whether this blocks the overlay permission is **unverified**. The Android 13 "all apps" page did not show the section, and `support.google.com` did not resolve. | [unverified] |
| Bench check | On Android 15 ZUI, open the overlay screen with the sideloaded APK and confirm the toggle is not greyed out. Note where the toggle sits. | bench test |

**Gotcha:** `ACTION_MANAGE_OVERLAY_PERMISSION` gives no callback. Show a rationale screen first,
then re-check on resume.

---

## 2. The overlay window

### 2.1 Flags and type

| Constant | Meaning (documented) | Status |
|---|---|---|
| `TYPE_APPLICATION_OVERLAY` | Shown above activity windows, below critical system windows such as the status bar or IME. It replaces the deprecated `TYPE_SYSTEM_ALERT` for non-system apps, deprecated in API 26. Needs `SYSTEM_ALERT_WINDOW`. | [documented] https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY (summary; the detail section was cut off) |
| `FLAG_NOT_FOCUSABLE` | The window never gets key focus. Key events go to the window behind it. This flag also turns on `FLAG_NOT_TOUCH_MODAL`. | [documented] https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_FOCUSABLE |
| `FLAG_NOT_TOUCH_MODAL` | With this set, pointer events outside the window's bounds go to the windows behind it. Without it, the window consumes all pointer events. | [documented] https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCH_MODAL |
| `FLAG_LAYOUT_NO_LIMITS` | Lets the window extend outside the screen. | [documented] https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_LAYOUT_NO_LIMITS |
| `FLAG_LAYOUT_IN_SCREEN` | Not fetched. Use it with explicit `x`/`y` in screen coordinates. | [unverified] |
| `FLAG_HARDWARE_ACCELERATED` | Not fetched. Compose windows are hardware-accelerated by default. Do not set it by hand without a measurement. | [unverified] |
| `FLAG_SECURE` | "treat the content of the window as secure, preventing it from appearing in screenshots or from being viewed on non-secure displays". It also hides the window during media projection and screen share. The page does not mention screen recording. | [documented] https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE |

**Recommended combination:** `FLAG_NOT_FOCUSABLE` (which implies `FLAG_NOT_TOUCH_MODAL`),
`FLAG_LAYOUT_IN_SCREEN`, `WRAP_CONTENT` width and height, `PixelFormat.TRANSLUCENT`. Do not set
`FLAG_SECURE`: it would hide the overlay from screen share and screenshots, which the rider may want
for a data screen. [recommendation based on the documented flags]

**Passing taps through outside the overlay:** the overlay's bounds define its touch area. Taps
outside those bounds go to the video app. This works because `NOT_FOCUSABLE` implies
`NOT_TOUCH_MODAL`. [documented, inferred from the two flag descriptions above]

**Our own taps:** the overlay receives taps inside its bounds, for drag, tap and long press. It does
not use `FLAG_NOT_TOUCHABLE`, so the untrusted-touch rule below does not apply to it.
[documented, inferred]

### 2.2 Untrusted touch (Android 12+, all apps)

- Android 12 blocks touches that pass through an overlay that obscures the app underneath, for
  apps whose windows use `FLAG_NOT_TOUCHABLE`. [documented]
  https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events-are-blocked
- "Windows of type `TYPE_APPLICATION_OVERLAY` **aren't** trusted." [documented]
- Exceptions: invisible windows (`GONE`/`INVISIBLE` root), windows with `alpha` 0.0, and system
  alert windows whose combined opacity is at or below the system maximum. The default maximum is
  **0.8**. [documented]
- Logcat message when blocked: `Untrusted touch due to occlusion by PACKAGE_NAME`. [documented]
- Adb test switches (documented):
  - `adb shell am compat disable BLOCK_UNTRUSTED_TOUCHES <pkg>` (per app)
  - `adb shell settings put global block_untrusted_touches 1` (log only, all apps; use 0 to allow)
  - `adb shell settings put global block_untrusted_touches 2` (back to default)

**Gotcha:** this rule is about pass-through. The overlay's own touches are not affected unless we
turn on `FLAG_NOT_TOUCHABLE`. Do not turn it on: it would also make the overlay's own taps
unreachable.

**`setHideOverlayWindows`:** the name in the brief could not be confirmed. The `Activity` reference
page was too long to read, and no page fetched in this pass confirms the method or its permission.
[unverified]. Do not depend on it.

### 2.3 Behaviour over DRM / secure video

- Not verified. The overlay is a separate window above the video surface, so the expected behaviour
  is that the overlay stays visible while protected video plays. Whether the video surface itself
  goes black is a property of the video app and the DRM level, not of this window.
- `FLAG_SECURE` does not apply here because we do not set it. [documented]
- Bench test: play a DRM-protected title (Netflix-style, Widevine L1 if available) with the overlay
  on. Note whether the overlay is drawn, and whether a screen recording or screenshot captures it.
  [unverified]

### 2.4 Minimal window (illustrative, not from docs)

```kotlin
val params = WindowManager.LayoutParams(
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT,
).apply {
    gravity = Gravity.TOP or Gravity.START
    x = savedX // in screen pixels, restored from DataStore
    y = savedY
}
windowManager.addView(composeView, params)
```

Constructor and fields follow the documented `LayoutParams` API; the exact values were not
checked on a device.

---

## 3. Compose inside a Service window

### 3.1 Owners needed

- `ViewTreeLifecycleOwner.set(view, owner)` is documented in `androidx.lifecycle:lifecycle-runtime`
  (added 2.3.0). The docs say it "should only be called by constructs such as activities or
  fragments" and that the owner "should report that it is destroyed if the view tree is removed".
  [documented] https://developer.android.com/reference/androidx/lifecycle/ViewTreeLifecycleOwner
- `LifecycleService` is in `androidx.lifecycle:lifecycle-service` (added 2.0.0). It extends
  `Service` and implements `LifecycleOwner`. [documented]
  https://developer.android.com/reference/androidx/lifecycle/LifecycleService
- A `SavedStateRegistryOwner` is needed for the saved state of `ComposeView`. Use
  `androidx.savedstate:savedstate`; `ViewTreeSavedStateRegistryOwner.set` mirrors the lifecycle one.
  [unverified: exact method names not fetched]
- A `ViewModelStoreOwner` is needed only if the overlay uses a `ViewModel`. Keep it out of the
  first version: use a plain state holder. [recommendation]

### 3.2 Lifecycle and disposal

- Drive the `LifecycleService` by hand: `onCreate` to CREATED, `onStart` to STARTED, RESUMED when
  the overlay is shown. Whether `LifecycleService` already dispatches these events automatically is
  **unverified** (the page was cut off). Test it by logging the lifecycle state.
- Dispose the composition with `ComposeView.setViewCompositionStrategy(...)`. The
  `ViewCompositionStrategy` reference page returned 404 from this session. The default and the
  strategy names are [unverified].
- Order on teardown: `windowManager.removeView(view)` first, then `super.onDestroy()`. This follows
  the documented rule that the owner should report destroyed once the view tree is removed.
  [documented rule, order inferred]

### 3.3 Skeleton (illustrative; check each call on a device)

```kotlin
class OverlayService : LifecycleService(), SavedStateRegistryOwner {
    private val saveController = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = saveController.savedStateRegistry

    private lateinit var composeView: ComposeView

    override fun onCreate() {
        super.onCreate()
        saveController.performAttach()
        saveController.performRestore(null)
        composeView = ComposeView(this).apply {
            ViewTreeLifecycleOwner.set(this, this@OverlayService)
            ViewTreeSavedStateRegistryOwner.set(this, this@OverlayService)
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            setContent { OverlayContent(state) }
        }
        windowManager.addView(composeView, overlayParams())
    }

    override fun onDestroy() {
        windowManager.removeView(composeView)
        super.onDestroy()
    }
}
```

Unverified in the snippet: the `ViewCompositionStrategy` value, `savedstate` setup order, and
whether `LifecycleService` already moves to RESUMED on its own.

### 3.4 Versions (Google Maven `maven-metadata.xml`, latest stable)

| Artifact | Latest stable on Google Maven | Note |
|---|---|---|
| `androidx.lifecycle:lifecycle-service` | **2.11.0** | Latest release 2.12.0-alpha05 is not stable |
| `androidx.lifecycle:lifecycle-runtime-compose` | **2.11.0** | Use with `collectAsStateWithLifecycle` |
| `androidx.savedstate:savedstate` | **1.5.0** | |
| `androidx.datastore:datastore-preferences` | **1.3.0** | |
| `androidx.activity:activity-compose` | **1.13.0** | Matches `gradle/libs.versions.toml` |
| `androidx.compose.ui:ui` (stable) | **1.12.1** | Maven lists 1.13.0-rc01 as latest; the BOM 2026.09.00 pins the matching UI. Check the BOM's POM for the exact UI version. [unverified] |
| `androidx.compose:compose-bom` | **2026.09.00** | Matches the catalog |

Maven metadata was read through `dl.google.com/dl/android/maven2`. The versions are facts; that
they match the BOM pin is not verified.

---

## 4. Dragging and snapping

- **Move the window, not the content.** Drag by changing `params.x`/`params.y` and calling
  `WindowManager.updateViewLayout(view, params)`. The method exists on `ViewManager`. Its cost,
  threading, and whether it is an IPC to WindowManagerService are **unverified**. Measure it on the
  tablet (see §5.4).
- **Throttle drags to frames.** Do not call `updateViewLayout` on every touch event. Coalesce
  pointer moves and apply them in a `Choreographer` frame callback (or `View.postOnAnimation`, name
  from memory). The docs state that Choreographer drops a frame when work overruns the window:
  16 ms at 60 Hz, 11 ms at 90 Hz, 8 ms at 120 Hz. [documented]
  https://developer.android.com/topic/performance/vitals/render
- **Do not run continuous animation.** The spec forbids it (§4.6). A snap animation runs only for
  its own duration and then stops. Implement it as a short frame-callback loop that cancels itself.
  [recommendation]
- **Round to whole pixels** before writing `x`/`y`. Sub-pixel coordinates make the window jitter.
  [recommendation, not checked against docs]
- **Rotation and insets.** `WindowMetrics` is the documented way to query bounds; `Display.getRealSize`
  and `getRealMetrics` are deprecated from Android 12. The `createWindowContext(display,
  TYPE_APPLICATION, options)` signature is documented. Whether a `TYPE_APPLICATION_OVERLAY` window
  context gives the right bounds on the ZUI tablet is [unverified].
  https://developer.android.com/about/versions/12/behavior-changes-all#display-getrealsize-and-getrealmetrics:-deprecation-and-constraints
- **Multi-window and display cutout** behaviour of overlays: [unverified]. Measure on the tablet.
- **Persistence.** Use DataStore Preferences (`androidx.datastore:datastore-preferences` 1.3.0). The
  recommendation is one store for position, mode and opacity. SharedPreferences is simpler but
  blocks on the main thread at first read. [recommendation; the library is documented on Maven]

---

## 5. Recomposition discipline and frame measurement

### 5.1 Stability

- Strong skipping is on by default from Kotlin 2.0.20. The project uses Kotlin 2.4.21, so it is on.
  [documented]
  https://developer.android.com/develop/ui/compose/performance/stability/fix
- A flat `data class` with `val` primitive or `String` properties in a Compose-compiled module is
  inferred as stable. Do not annotate it with `@Immutable`. [documented]
- Avoid `List`, `Set` or `Map` in the overlay state. Under strong skipping, an unstable collection
  is compared by instance equality. That is good when the reference is preserved and bad when a new
  list is built each tick. Use primitives or a reused instance. [documented]
- `@Immutable` / `@Stable` are a contract, not a fix. Use them only when the compiler cannot infer
  a small class and new instances are created on every emission. [documented]

### 5.2 State shape

- One immutable state object for the overlay, for example `data class OverlayState(val power: Int,
  val hr: Int, val cadence: Int, val elapsed: Long, val gear: Int, val mode: Mode)`. The ride engine
  emits a new instance at most once a second.
- Collect with `collectAsStateWithLifecycle()` from `lifecycle-runtime-compose` 2.11.0. Whether it
  stops collection while the overlay window is detached is [unverified].
- `derivedStateOf` is for values computed from more than one state read that change less often
  than their inputs. Use it for the power-zone colour and the label text. [recommendation]
- Rate limit at the source: the ride engine publishes the overlay state at most once per second at
  rest. Rapid values (power) can be sampled in the engine, not in Compose. [spec §4.6, §7.3]

### 5.3 Drag does not recompose

- Dragging changes only `params.x`/`params.y`, which never reach Compose. The composition stays as it
  is during a drag, so the drag cost is the `updateViewLayout` call only. [recommendation, follows
  from the design above]

### 5.4 Measuring frame drops (acceptance criterion 7)

Run these with the app on the tablet, a release or non-debuggable build, and the overlay visible.
The overlay lives in the ride app's process, so the package name is the ride app.

Documented:

```
adb shell am trace-ipc start      # find binder calls on the UI thread
adb shell am trace-ipc stop --dump-file /data/local/tmp/ipc-trace.txt
adb pull /data/local/tmp/ipc-trace.txt
```

[documented] https://developer.android.com/topic/performance/vitals/render (the trace-ipc section).
Use it during a drag: a binder call inside the drag handler is the likely cause of dropped frames.

Unverified (recalled from memory; check before relying on them):

```
adb shell dumpsys gfxinfo <pkg> reset
# ... drag the overlay for 10 s ...
adb shell dumpsys gfxinfo <pkg> framestats
```

[unverified] The output includes "Janky frames" and per-frame timings. Perfetto with the FrameTimeline
data source gives the same information with more detail. [documented: FrameTimeline in Perfetto,
https://developer.android.com/topic/performance/vitals/render]

Acceptance check for criterion 7: no janky frames during a 10-second drag, measured with
`framestats`, and no `RV`/`Layout` sections on the UI thread in a Perfetto trace.

---

## 6. Foreground service

### 6.1 Service type

| Type | Requirements (documented) | Fit |
|---|---|---|
| `connectedDevice` | Manifest type plus permission `FOREGROUND_SERVICE_CONNECTED_DEVICE`. At runtime, one of the Bluetooth runtime permissions must be granted (`BLUETOOTH_CONNECT` is the one we use). "Interactions with external devices that require a Bluetooth, NFC, IR, USB, or network connection." | **Use this.** Required on Android 14+ (API 34), where a type must be declared. [documented] https://developer.android.com/develop/background-work/services/fgs/service-types |
| `mediaPlayback` | No runtime prerequisite. Not for BLE. | Not needed |
| `health` | Needs body-sensor or Health Connect permissions. | Not needed: we read HR over BLE, not via Health Connect. |
| `specialUse` | Needs a Play Console justification. | Avoid |
| `dataSync`, `mediaProcessing` | Subject to the 6 h / 24 h limit on Android 15+. | Not used |

**Companion-device alternative:** the docs say that for continuous data transfer with an external
device, the companion device manager may be better. We have no need for it now. [documented]

**Timeouts:** the Android 15 limit (6 hours per 24) applies only to `dataSync` and
`mediaProcessing`. `connectedDevice` is not in that list. [documented]
https://developer.android.com/develop/background-work/services/fgs/timeout

### 6.2 Manifest and permissions (illustrative)

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".RideService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

[documented for the permission names and type; the XML shape is illustrative.] Declare
`android:exported` on every component (Android 12+). [documented]

### 6.3 Starting from the background

- Apps targeting Android 12+ cannot start a foreground service from the background, except for the
  listed exemptions. A failed start throws `ForegroundServiceStartNotAllowedException`. [documented]
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Exemptions that matter here:
  - The app transitions from a user-visible state (an activity). **Start the ride service from the
    ride screen.** [documented]
  - The app has `SYSTEM_ALERT_WINDOW`. On Android 15+ the app must also have a **visible overlay
    window** at that moment. [documented]
  - The user turned off battery optimisation for the app. [documented]
- The while-in-use rule (creating the service from the background is blocked for camera,
  microphone, location and body-sensor types) does **not** list Bluetooth. Our `connectedDevice`
  service is not affected by it. [documented: absence in the while-in-use list]
- Boot: the `BOOT_COMPLETED` restriction on Android 15+ is listed for `dataSync`, `mediaPlayback`,
  `mediaProjection` and `phoneCall`. The `connectedDevice` section does not carry that note. Do not
  start the ride service at boot without a bench test. [documented: absence]

### 6.4 Notifications

- `POST_NOTIFICATIONS` is a runtime permission on Android 13+ (API 33). [documented]
- If the user denies it, the foreground-service notification is hidden from the drawer, but it still
  appears in the Task Manager. [documented]
  https://developer.android.com/about/versions/13/behavior-changes-13#notification-permission-fgs-appearance
- Android 13+: users can dismiss the notification, and they can stop apps with running foreground
  services from the notification drawer (Task Manager). The app must handle a user-initiated stop.
  [documented] https://developer.android.com/about/versions/13/behavior-changes-all
- Android 12+: notifications for short-lived foreground services may be delayed by 10 s. Ride
  notifications are long-lived, so the delay should not apply, but verify. [documented, with
  exceptions]

### 6.5 Restart and process death

- `START_STICKY` and the behaviour after the user swipes the app away: [unverified]. The service
  docs were not read in full. Test on the tablet.
- The `shortService` type has a 3-minute limit and cannot be sticky. It is not suitable here.
  [documented]

---

## 7. Handlebar-control prerequisites (M4)

- **Play/pause media key to "whichever app is playing":**
  - `MediaSessionManager.dispatchMediaKeyEvent` is **not** in the public method list on its
    reference page. Do not use it. [documented absence]
    https://developer.android.com/reference/android/media/session/MediaSessionManager
  - `AudioManager.dispatchMediaKeyEvent(KeyEvent)` is documented as "Sends a simulated key event for
    a media button." Its permission, API level and Android 14–16 restrictions were in the cut-off
    part of the page: [unverified]. Bench test it first.
  - `MediaSessionManager` queries and listeners require `MEDIA_CONTENT_CONTROL` or an enabled
    notification listener. Without one of these, a `SecurityException` is thrown. [documented]
    Relevant only if we ever read the active session.
- **System media volume:** `AudioManager.adjustStreamVolume(streamType, direction, flags)` is
  documented as "Adjusts the volume of a particular stream by one step in a direction." Its permission
  (`MODIFY_AUDIO_SETTINGS` is the likely one) and flags were not confirmed: [unverified].
- **Long-press repeat timing:** the spec values (500 ms hold, 150 ms repeat, §4.7) are the design
  targets. There is no Android requirement to check. [spec]

---

## 8. Battery and OEM kill risks

### 8.1 Standard Android mechanisms (documented)

- **Doze:** when the device is unplugged, stationary and the screen is off, the system suspends
  network access, ignores wake locks, defers alarms and jobs, and blocks Wi-Fi scans. [documented]
  https://developer.android.com/training/monitoring-device-state/doze-standby
  - Exit: the user moves the device, turns on the screen, or connects a charger. [documented]
  - A foreground service is listed as a reason the app is not "idle" under App Standby. [documented]
- **App Standby:** an app with a foreground service is not considered idle. [documented]
- **Standby buckets:** on Android 13+, "restricted" removes foreground services from the foreground.
  Test with `adb shell cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore`. [documented]
  https://developer.android.com/about/versions/13/behavior-changes-all
- **Battery-optimisation exemption:**
  - `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`: most apps can open this list. [documented]
  - `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: a direct dialog, for apps that meet an
    acceptable use case. [documented]
  - `PowerManager.isIgnoringBatteryOptimizations(pkg)`: check the state. [documented]
  - The docs state that Google Play policy prohibits requesting this exemption unless the core
    function is affected. Our app is sideloaded, so Play policy does not apply, but the docs'
    acceptable-use table classes a "peripheral device companion app that only connects periodically"
    as **not acceptable**. Our app keeps a persistent BLE connection to a trainer, which is closer to
    the "maintaining a persistent connection with the peripheral device" row. That row says
    acceptable only for providing internet access, so our case is borderline. [documented table]
    https://developer.android.com/training/monitoring-device-state/doze-standby#exemption-cases
- **Wake lock policy:** a partial wake lock is not enough in Doze (wake locks are ignored in Doze,
  per the docs). Acquire a partial wake lock only while a ride is running and release it on finish.
  The foreground service is the primary keep-alive. [documented + recommendation]

### 8.2 OEM behaviour (ZUI and HyperOS)

- dontkillmyapp.com was not reachable from this session. Its content is not used here.
- Everything about ZUI (Lenovo) and HyperOS (Xiaomi) killing foreground services or BLE connections
  is [unverified]. Treat it as a hypothesis for the bench test.

### 8.3 Bench checklist (adb)

Documented commands:

```
adb shell dumpsys deviceidle force-idle        # enter Doze now (device must be unplugged)
adb shell dumpsys deviceidle unforce           # leave forced Doze
adb shell dumpsys battery reset                # return to normal battery state
adb shell dumpsys battery unplug               # simulate unplugged
adb shell am set-inactive <pkg> true           # App Standby test
adb shell am set-inactive <pkg> false
adb shell am set-standby-bucket <pkg> restricted
adb shell am get-standby-bucket <pkg>          # Android 16 docs
adb shell cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore
```

Unverified (recalled, check before use):

```
adb shell dumpsys deviceidle whitelist         # battery-optimisation exemptions
adb shell dumpsys batterystats <pkg>           # battery use for the package
```

Run each scenario with the ride running: 20-minute ride, screen off then on, device unplugged
during the ride, `restricted` bucket forced mid-ride. Check the overlay, the BLE connection, the
notification and the video app.

---

## 9. Open questions for the bench test

Only the real tablet can settle these:

1. **Restricted settings** on the Lenovo: does the sideloaded APK's overlay permission toggle work,
   or is it restricted? Where is the toggle?
2. **Overlay over DRM video:** does the overlay stay drawn and does it show in a screen recording?
3. **`updateViewLayout` cost:** measure the drag path with `trace-ipc` and Perfetto. Is there a
   binder call per move?
4. **Frame drops during drag** at 60, 90 and 120 Hz: `framestats` janky-frame percentage.
5. **Long-press to open the app from the overlay:** starting an activity from the overlay's own
   touch handler. Test whether the background-start rules block it (the SAW exemption is documented
   only for services).
6. **FGS survival:** swipe the app away mid-ride; check the service, overlay and BLE connection.
   Force-stop the app; check the restart.
7. **ZUI kill behaviour:** a 30-minute ride with the screen off, plus a 15-minute `restricted` bucket
   test. Does the trainer connection drop?
8. **Media key:** does `AudioManager.dispatchMediaKeyEvent` reach a playing video app on Android
   15 ZUI and on the Xiaomi? What permission does it need?
9. **Volume:** `adjustStreamVolume` with the flags we use; does the volume bar appear?
10. **Compose lifecycle in the service:** does `LifecycleService` reach RESUMED without manual
    calls? Does the composition dispose when the window is removed?
11. **Multi-window and display cutout:** the overlay's position after rotation and when the video app
    enters split screen.

---

## 10. Sources

Fetched in this pass (developer.android.com unless noted):

- Manifest.permission#SYSTEM_ALERT_WINDOW (summary)
- Settings#ACTION_MANAGE_OVERLAY_PERMISSION; Settings.canDrawOverlays (summary)
- WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY (summary), FLAG_NOT_FOCUSABLE,
  FLAG_NOT_TOUCH_MODAL, FLAG_LAYOUT_NO_LIMITS, FLAG_SECURE
- WindowManager (class, updateViewLayout signature only)
- develop/background-work/services/fgs/service-types (connectedDevice, dataSync, mediaProcessing,
  shortService, specialUse)
- develop/background-work/services/fgs/timeout
- develop/background-work/services/fgs/restrictions-bg-start
- about/versions/12/behavior-changes-all (untrusted touch, WindowMetrics)
- about/versions/13/behavior-changes-13 (notification permission, FGS appearance)
- about/versions/13/behavior-changes-all (Task Manager, restricted bucket, dismissible notification)
- about/versions/16/behavior-changes-all (no overlay change found)
- training/monitoring-device-state/doze-standby
- topic/performance/vitals/render (frame budget, trace-ipc, FrameTimeline)
- develop/ui/compose/performance/stability/fix
- lifecycle ViewTreeLifecycleOwner and LifecycleService reference pages
- MediaSessionManager reference page; AudioManager.dispatchMediaKeyEvent (summary)
- Google Maven `maven-metadata.xml` (dl.google.com/dl/android/maven2) for artifact versions

Not reachable from this session: support.google.com (restricted settings help),
dontkillmyapp.com (OEM kill list), developer.android.com ViewCompositionStrategy reference (404).
Not read in full: the Settings, Activity and AudioManager reference pages (cut off).
