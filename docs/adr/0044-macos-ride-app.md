# ADR 0044: macOS ride application for Apple Silicon MacBooks

Status: accepted (2026-11-25). **Built without a MacBook or Xcode on the authoring host**: the Kotlin Multiplatform `core` JVM build, the design-token and icon generators, and the contrast checks pass locally; `macosArm64` native compilation, `xcodebuild test`, and the real hardware bench run on an Apple Silicon MacBook and on the `macos-15` GitHub Actions job.

## Context

Overdrive's ride app (`android/app-ride`) was built for an Android tablet in front of the bike (spec §4). The owner now wants a macOS ride application targeting **Apple Silicon MacBooks** (`macosArm64`, macOS 14.0 Sonoma and later), with **no external distribution yet** (local Xcode builds signed with a Personal Team, no `.dmg` packaging or notarization).

Two architectural properties of the repository shape the decision:

1. `core/src/commonMain` has **zero Android or JVM imports**: all protocol codecs (FTMS, Heart Rate, Zwift Click v2), `LinkSupervisor` (including hybrid background connect, ADR 0040), `RideEngine`, `FlatPhysicsSpeedModel` + `RouteGrade`, `StallGuard`, `HeartRateHold`, `WorkoutPlayer`, `FitWriter`, `RideLog`, `ActionRouter`/`HandlebarControls`/`ButtonLearner`, `PlanRepository`, `ProfileRepository`, `RouteLibrary`, `LiveSessionController`/`SseParser`, and `SyncCoordinator` are pure Kotlin Multiplatform.
2. On macOS, the hardest technical requirements are an **always-on-top, non-activating HUD overlay over native macOS Full-Screen video** (Safari, Chrome/YouTube, IINA, VLC), **CoreBluetooth** (including `ManufacturerData` for Zwift Click v2 and background wake), **system volume and media keys**, **notch-safe placement**, **App Nap / sleep prevention**, and **low battery/RAM overhead on MacBooks**.

## Decision

### 1. Stack: Native Swift (AppKit + SwiftUI) over shared KMP `OverdriveCore.xcframework`

- **`core/` adds the `macosArm64()` target** alongside `jvm()`, exporting a static `OverdriveCore.xcframework` (`baseName = "OverdriveCore"`).
- **`core/src/appleMain`** provides:
  - POSIX `FileByteSink` and `FileByteSource` (`open`/`write`/`fsync`/`close`) matching `core/src/jvmMain`'s crash-safe contract;
  - `AppleBridge.kt`: thin, zero-plugin Swift-interop helpers (`AppleFlowWatcher`, `AppleBytes` for O(1) `NSData` ↔ `ByteArray` conversion, `AppleScopes`, factory helpers for constructors with default arguments). We intentionally avoid third-party compiler plugins (such as SKIE) so `core` stays decoupled from plugin lag on Kotlin 2.4+.
- **`android/` is left untouched**: zero regression risk to Android builds and tests.
- **`macos/` is a native Swift (SwiftUI + AppKit) app** defined declaratively with **XcodeGen** (`macos/project.yml`, deployment target macOS 14.0, Apple Silicon `arm64`, App Sandbox disabled for local builds).

Rejected: **Compose Multiplatform Desktop (JVM) + Swift/JNI bridge**. Compose Desktop windows are backed by AWT `NSWindow`, not `NSPanel(.nonactivatingPanel)`, making overlay over native macOS Full-Screen Spaces fragile; CoreBluetooth, media keys, Keychain, and audio still require a native Swift/ObjC bridge; and a bundled JRE increases memory and battery overhead on MacBooks.

### 2. Design tokens and icons stay single-source

`design/tools/build-tokens.mjs` and `design/tools/build-icons.mjs` now emit Swift outputs alongside CSS and Compose Kotlin, verified by `--check` in CI:

- `macos/Overdrive/Design/Generated/OverdriveTokens.swift`
- `macos/Overdrive/Design/Generated/OverdriveIcons.swift`
- Bundled Geist static TTF fonts (`geist_regular.ttf`, `geist_medium.ttf`, `geist_semibold.ttf`) and OFL / ISC licence texts.

### 3. macOS-specific platform subsystems

| Subsystem | Implementation | Key macOS / MacBook constraint |
|---|---|---|
| **Overlay HUD** | `OverlayPanel: NSPanel` (`[.borderless, .nonactivatingPanel]`, `canBecomeKey = false`, `hidesOnDeactivate = false`, `level = .statusBar`, `collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]`) | Draws over native macOS Full-Screen video without switching Spaces or stealing keyboard focus. Insets vertical bounds by `NSScreen.safeAreaInsets.top` to stay clear of the MacBook notch. 1:1 `mouseDragged` origin moves (no SwiftUI invalidation), 180 ms snap on X, bottom-edge resize anchoring, single-click compact/detailed toggle, double-click or 500 ms long-press to focus main window. |
| **Menu bar** | `MenuBarController` (`NSStatusItem`) | Compact bike glyph + live power in watts during an active ride; dropdown to open window, toggle overlay, pause/resume, mute live coach, or quit. |
| **Sleep & App Nap** | `SleepAssertion` (`ProcessInfo.beginActivity` with `[.userInitiated, .idleSystemSleepDisabled, .idleDisplaySleepDisabled, .latencyCritical]`) | Held while `RIDING` or `PAUSED`, released on `FINISHED`/`READY`. Prevents MacBook display/system sleep and App Nap throttling of BLE and the 1 Hz loop while video is full-screen. |
| **CoreBluetooth** | `MacBleEnvironment`, `MacGattScanner`, `MacGattTransport`, `MacGattTransportFactory` over a testable `CoreBluetoothPlatform` protocol, on a dedicated `DispatchQueue("app.overdrive.ble")` | `DeviceId` = `CBPeripheral.identifier.uuidString`. `ManufacturerData` splits the first 2 little-endian bytes (`companyId`, `0x094A` for Zwift) from `payload`. `requestMtu` returns `maximumWriteValueLength(.withoutResponse) + 3`. `discoverServices` chains `discoverCharacteristics` per service. `enable` uses `setNotifyValue(true, for:)`. `ConnectMode.BACKGROUND` uses `centralManager.connect` (no platform timeout, naturally wakes sleeping Click v2 per ADR 0040) with a scan fallback if `retrievePeripherals` misses. Raw BLE sessions record via `core`'s `RecordingGattTransport` and export as one `.zip`. |
| **Handlebar & media** | `HandlebarService` + `core`'s `ActionRouter` + `MacMediaController` | System volume via **CoreAudio** (`kAudioHardwareServiceDeviceProperty_VirtualMainVolume`, ±1/16 step, no permissions needed, HID fallback for HDMI). Play/Pause via `CGEvent` `NX_KEYTYPE_PLAY` (requires optional **Accessibility** permission; Devices/Settings show a single notice when missing, never blocking the ride). Keyboard shortcuts (`Space`, arrows, `M`, `⌘O`, `⌘,`) mirror controls when the main window is focused. |
| **Live Coach** | `URLSessionTransport.stream` (`URLSession.bytes`) + `MacCuePlayer` (`AVAudioPlayer(data:)`) | Plays MP3 directly from memory without temp files. Never opens mic/HFP audio sessions so AirPods stay in A2DP stereo. Configurable Coach volume slider in Settings (macOS has no cross-app transient ducking API). |
| **Storage & Sync** | `~/Library/Application Support/Overdrive/` + `KeychainStore` + `URLSessionTransport` | Crash-safe `.ridelog` (`fsync`), atomic `.json` + `.fit`, `FileOutbox` journal, offline caches for plan/profile/routes/recovery. Bearer token stored in macOS Keychain (`kSecClassGenericPassword`), never in plist or logs. Ephemeral `URLSession` with redirects blocked and `NSAllowsLocalNetworking = true` for LAN HTTP. |

### 4. Local development and CI (no distribution yet)

- App Sandbox is off (`macos/Config/Overdrive.entitlements`). Developers copy `macos/Config/LocalSigning.xcconfig.example` to `LocalSigning.xcconfig` (gitignored) with their free Apple Personal Team ID so macOS preserves Bluetooth and Accessibility grants across rebuilds.
- `.github/workflows/ci.yml` adds a `macos` job on `macos-15` that runs `./gradlew :macosArm64Test :assembleOverdriveCoreXCFramework`, `xcodegen generate`, and `xcodebuild test` (unsigned).

## Unverified on hardware (MacBook bench)

1. `NSPanel` visibility above native Full-Screen Safari, Chrome (DRM video), IINA, and VLC without Space switching, and notch clearance on 14"/16" MacBook Pro and 13"/15" MacBook Air.
2. Sleeping Zwift Click v2 wake latency over `CBCentralManager.connect` (`ConnectMode.BACKGROUND`) and `0x094A` manufacturer data parsing on macOS `bluetoothd`.
3. Van Rysel D500 FTMS Control Point indications, Reset-after-Request-Control, and dropout recovery over CoreBluetooth.
4. `CGEvent` `NX_KEYTYPE_PLAY` and CoreAudio volume steps across built-in speakers, AirPods, and HDMI outputs.
5. `AVAudioPlayer` Live Coach playback over video with AirPods (no HFP downgrade).
6. 60-minute ride on MacBook battery: zero display/system sleep, zero App Nap throttling, smooth drag at 120 Hz ProMotion.

## Consequences

- `core/` now builds for `jvm` and `macosArm64`; non-macOS hosts still pass `./gradlew check` because Kotlin Multiplatform skips Apple compilation tasks on Linux/Windows.
- Every design token or Lucide icon change automatically updates CSS, Android Compose, and macOS Swift in one step and is enforced by CI.
- Distribution (Developer ID signing, `notarytool` notarization, `.dmg` release asset) is intentionally deferred to a later ADR once the MacBook bench passes.
