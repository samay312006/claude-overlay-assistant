# Claude Overlay Assistant (Android)

An Android overlay that brings your AI **subscription** on top of everything:
screen-aware Q&A now; voice, wake word, in-app automation, and predictive
assistance later. The brain is a pluggable **connector** — the first,
`ClaudeAppBrain`, drives the installed Claude Android app via an
AccessibilityService (screenshot → type → read the answer back). See
[docs/PLAN.md](docs/PLAN.md).

## Modules
- **`:core`** — pure Kotlin, no Android deps: UI-tree model + matchers, the
  Claude-app selector/answer logic, the answer-stability state machine, and
  telemetry types. Fully JVM-unit-tested.
- **`:app`** — the Android app (minSdk 30 / Android 11). Only included in the
  build when an Android SDK is configured (`ANDROID_HOME`/`ANDROID_SDK_ROOT` or
  `local.properties`); this lets `:core` build and test in SDK-less CI.

## Building
- Full app: open in Android Studio (needs the Android SDK + Google's Maven
  repo), or `./gradlew :app:assembleDebug`.
- Logic tests only (no SDK): `./gradlew test` — runs `:core` tests.

## First device run
Complete onboarding (overlay + accessibility + notifications). If you sideloaded
from a browser, Android 13+ blocks the accessibility toggle under "Restricted
settings" — grant "Allow restricted settings" from App info first (adb/Android
Studio installs are exempt). Then use **Debug ▸ Driver Doctor** over the Claude
app to dump its live UI tree and correct `ClaudeAppSelectors` if the built-in
anchors don't match your Claude app version.
