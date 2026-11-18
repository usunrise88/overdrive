# core

The shared pure-Kotlin core (spec §7.1): protocol codecs (FTMS, heart rate, Click), ride engine,
workout model and player, physics, gear logic, FIT writer, gesture detection, and simulated
devices for hardware-free rides.

**No Android or other platform imports.** This is a separate Gradle build so the rule is enforced
structurally: it cannot see the Android SDK, and it builds and tests with only a JDK. `android/`
consumes it with `includeBuild("../core")`.

```bash
./gradlew check          # tests + ktlint
./gradlew spotlessApply  # format
```

Currently implemented: the native workout model (`app.overdrive.core.workout`), checked against the
shared fixtures in `../contracts`.

Targets: JVM only. iOS is a non-goal (spec §1); keeping this module platform-free leaves the door
open.
