# Claude Overlay Assistant — Android (Multi-Brain Edition)

## Context

An Android app that puts an AI assistant as an overlay over everything you do:
screen-aware Q&A now; voice, wake word, in-app automation, and real-time
predictive assistance in later phases.

**Pivot from the original plan:** the app is not a standalone API-key app. The
brain is the AI subscription you already have, connected like an account
("Connect Claude", "Connect ChatGPT"), with Claude the most-optimized connector.
No consumer subscription offers sanctioned programmatic access, so the primary
connector is **app automation**: an `AccessibilityService` drives the installed
Claude Android app (share screenshot → type question → read the answer back).
The automation core is therefore the foundation, built first.

### Constraints (acknowledged)
1. **Foreground monopoly** — each ask visibly switches to the Claude app and
   back. Fine for discrete asks; real-time assist (Phase 6) needs a network
   brain. The connector architecture makes that swap trivial.
2. **Fragility** — driving another app's UI breaks when it updates. Anchors are
   data-driven and isolated in `ClaudeAppSelectors`; a **Driver Doctor** debug
   screen dumps the live UI tree so fixes are a one-file patch.
3. **Terms risk** — automating the Claude app likely violates its usage terms;
   personal, single-user, low-volume; sideload only, no Play Store.
4. **Latency** — a round-trip is seconds; streaming is approximated by polling
   the answer node as it grows (`AnswerTracker`).
5. **RL/predictive is Phase 6+**, but its telemetry corpus (`TelemetryStore`)
   is collected from the first driver run.

## Module & package layout

- **`:core`** — pure-Kotlin, JVM-unit-tested, no Android types:
  `uitree/` (`UiNode`/`UiSnapshot`, `NodeMatcher` + combinators),
  `driver/` (`ClaudeAppSelectors` answer/anchor logic, `AnswerTracker`
  stability/timeout state machine, `RunLog` telemetry), `brain/`
  (`BrainEvent`/`BrainStatus`).
- **`:app`** — Android (minSdk 30, targetSdk 35), packages under
  `com.claudeassist.overlay`:
  - `automation/` — `AssistAccessibilityService` (the only class touching the
    framework API), `UiTreeReader` (live tree → `UiSnapshot` + id→node map),
    `UiQuery` (snapshot matching + `await`), `ActionExecutor` (tap/type/launch
    with gesture + clipboard fallbacks), `ScreenReader` (accessibility
    screenshot).
  - `brain/` — `Brain` interface, `ClaudeAppBrain`, `FakeBrain`,
    `PlaceholderBrain`s, `BrainRegistry`; `brain/drivers/ClaudeAppDriver`.
  - `overlay/` — `OverlayService` (specialUse FGS), `OverlayWindowController`
    (bubble/panel/banner windows, IME focus toggle, Back handling), `BubbleView`,
    `AskPanelView`, `SessionBannerView`.
  - `data/` — `SecureSettings`, Room `AppDatabase` (`HistoryStore`,
    `TelemetryStore`).
  - `ui/` — Compose `MainActivity` with Onboarding / Brains / Settings / History
    / Debug (Driver Doctor).

The `:app` module is only included when an Android SDK is present (env var or
`local.properties`); otherwise `./gradlew test` builds and tests `:core` alone.

## Ask flow

bubble tap → (optional) accessibility screenshot → active `Brain.ask()` →
`ClaudeAppBrain` records previous app, drives the Claude app via
`ClaudeAppDriver` (share/launch → type → send), polls the answer through
`AnswerTracker`, emits deltas to the panel, returns to the previous app, and
writes a `RunLog` to telemetry. A foreground-package watchdog aborts cleanly if
you switch apps mid-run.

## Build order (milestones)
- **M1** Skeleton: Gradle, manifest, nav, settings, `:core` primitives + tests.
- **M2** Accessibility core: service, `UiQuery`, `ActionExecutor`; debug demo.
- **M3** `ScreenReader` + Driver Doctor tree dump.
- **M4** Overlay: FGS, bubble, ask panel, banner; `FakeBrain` end-to-end.
- **M5** `ClaudeAppDriver` text-only end-to-end + telemetry.
- **M6** Image asks (share intent → clipboard → text-only degradation).
- **M7** History wiring, polish, `:core` unit tests green.

## Device verification
1. Onboarding: overlay + accessibility + notifications.
2. Brains: "Connect Claude" detects the installed Claude app + enabled service.
3. Over an article: bubble → "summarize what's on screen" → watch it drive the
   Claude app → answer appears in the panel → previous app returns.
4. Failure paths: app not installed (card explains); selectors miss
   (`Broken(reason)` + Driver Doctor dump to fix); airplane mode (Claude's own
   error surfaced); touch screen mid-run (clean abort + retry).
5. History lists asks; Debug shows a telemetry row per run.

## Roadmap
- **Phase 2** Point & speak (region select, STT/TTS).
- **Phase 3** Wake word.
- **Phase 4** Do-it-for-me multi-step automation.
- **Phase 5** More brains (`ChatGptAppBrain`, `AnthropicApiBrain`, `PcRelayBrain`).
- **Phase 6** Real-time & predictive (needs a network brain + telemetry corpus).
- **Cross-cutting** Learning loop from telemetry.
