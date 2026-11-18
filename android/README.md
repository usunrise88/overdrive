# android

One Gradle build for everything that runs on Android. The shared `core` is a separate build in
`../core`, pulled in with `includeBuild` (see `settings.gradle.kts`).

| Module | Contents | Spec |
|---|---|---|
| `ble` | Bluetooth transport behind an interface | §7.1 |
| `data` | Local database, outbox, sync client | §4.9, §7.1 |
| `ui-kit` | Design system: tokens, type, components, charts | §7.4 |
| `app-ride` | Tablet app: ride screens, overlay service | §4 |
| `app-companion` | Phone app: statistics, Health Connect reader | §6.1 |

Dependency direction: apps → `ui-kit`, `data`, `ble` → `core`.

## Building

Needs a JDK 17+ and the Android SDK with platform 37 (`ANDROID_HOME`, or `sdk.dir` in a git-ignored
`local.properties`).

```bash
./gradlew check assembleDebug   # tests, lint, ktlint, debug APKs
./gradlew spotlessApply         # format
```

APKs land in `app-*/build/outputs/apk/debug/` and are sideloaded (spec §7.6). No dependency on
Google Play Services, anywhere.

## SDK levels

`minSdk` 31 (modern Bluetooth permissions; both target devices run newer Android). `targetSdk` 36:
raise it only after testing the new runtime behaviour. `compileSdk` 37 is required by the current
Compose release. All three live in `../gradle/libs.versions.toml`.
