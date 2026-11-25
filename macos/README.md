# Overdrive for macOS (Apple Silicon MacBooks)

Native macOS ride application (`SwiftUI` + `AppKit NSPanel` + `CoreBluetooth`) backed by the shared Kotlin Multiplatform `core` (`OverdriveCore.xcframework`, `macosArm64`). Architecture and decisions: [ADR 0044](../docs/adr/0044-macos-ride-app.md).

## Prerequisites

- Apple Silicon MacBook (`arm64`) running **macOS 14.0 Sonoma** or newer
- **Xcode 16+** with Command Line Tools
- **JDK 17+** (for building `OverdriveCore.xcframework` from `../core`)
- **Node 20/24** (only needed if regenerating design tokens/icons)
- **XcodeGen**: `brew install xcodegen`

## Quick start (local build, no distribution)

```bash
# 1. Build the shared Kotlin Multiplatform framework for macosArm64
make macos-core

# 2. (Optional, recommended) Set your free Apple Personal Team ID so macOS preserves
#    Bluetooth & Accessibility permissions across rebuilds:
cp macos/Config/LocalSigning.xcconfig.example macos/Config/LocalSigning.xcconfig
# Edit DEVELOPMENT_TEAM in macos/Config/LocalSigning.xcconfig

# 3. Generate Overdrive.xcodeproj and open in Xcode
make macos-gen
open macos/Overdrive.xcodeproj
```

Press **⌘R** in Xcode to build and run on your MacBook.

Run automated tests (Kotlin/Native `core` + Swift `OverdriveTests`, no hardware required):

```bash
make macos-test
```

## Keyboard shortcuts (when Overdrive window is focused)

| Shortcut | Action |
|---|---|
| `Space` | Pause / resume ride |
| `↑` / `↓` | Gear up / down (or ±5% ERG intensity / ±2 bpm HR target) |
| `←` / `→` | Previous / next interval |
| `M` | Mute Live Coach |
| `⌘O` | Open local `.json` or `.zwo` workout (drag-and-drop onto Ready also works) |
| `⌘⇧O` | Toggle "Always show overlay while riding" |
| `⌘,` | Settings |

## MacBook permissions

- **Bluetooth** (required for real trainer, HR strap, and Click v2): prompted on first launch (`NSBluetoothAlwaysUsageDescription`).
- **Accessibility** (optional): only required so Zwift Click Button 3 can send Play/Pause media keys (`CGEvent`) to Chrome, Safari, or IINA. Volume (CoreAudio), gears, intervals, and ride pause work without Accessibility.
