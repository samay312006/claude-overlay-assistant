# Claude Overlay Assistant — Android

## Context

The goal is an Android app that puts Claude as an overlay over everything you do:
screen-aware Q&A ("circle/screen search"), voice commands with an always-on
"hey claude" wake word, doing things inside other apps for you, real-time game
assistance, and a learning loop that improves over time.

That full vision is not one app — it is several hard, independent subsystems
(overlay UI, screen vision, voice, in-app automation, real-time loops, learning).
Building them all at once is how ambitious apps fail. This plan **decomposes** the
vision into phases and designs **Phase 1 (Screen‑to‑Ask)** in full — the thinnest
end-to-end slice that proves the core pipeline (overlay → capture screen → Claude
vision → answer in overlay) with no risky automation. Later phases build on it.

### Decisions locked in
- **Platform:** Android only. (Overlays, reading other apps' screens, tap
  automation, and background wake words are effectively Android-only; iOS forbids
  them. Also matches the Windows dev setup.)
- **Stack:** Native Kotlin. Jetpack Compose for in-app screens (onboarding,
  settings, history); classic Android Views + `WindowManager` for the overlay
  (Compose-in-overlay is possible but Views are the well-trodden path).
- **First slice:** Whole-screen capture + typed question. Region-draw ("true
  circle") and voice come in Phase 2.
- **Roles:** Claude writes the code; user runs/tests on a physical device and
  opens the project in Android Studio to tweak.

### Open decision: how the app reaches Claude
**A Claude Pro subscription cannot be used directly by a custom app** — claude.ai
has no public API, and scraping its session violates Anthropic's ToS and breaks
constantly. The sanctioned, stable route is the **Anthropic API** (billed per
token, separate from Pro; a few cents per query, capped in the Console).

To avoid blocking on this, the app is built against a **swappable `ClaudeClient`
interface**. The default/recommended implementation is `AnthropicClient`
(API key). An alternative `RelayClient` (talk to a PC running Claude via the
Agent SDK on the user's subscription — gray-area, needs PC always on, not
standalone) can be added later without touching the rest of the app. **User to
confirm the API-key path before first real run.**

---

## Phase 1 — Screen‑to‑Ask (this plan)

### User flow
1. First launch → onboarding grants: **Display over other apps**
   (`SYSTEM_ALERT_WINDOW`) and starts the overlay service; user enters/saves
   Claude backend config (API key + model).
2. A floating **bubble** sits over all apps.
3. User opens any app, taps the bubble → an **ask panel** slides out.
4. App requests a **screen capture** (`MediaProjection`; one consent dialog,
   kept alive by a foreground service so repeat asks don't re-prompt).
5. Panel shows a thumbnail of the captured frame + a text field.
6. User types a question → tap send.
7. Frame is downscaled/JPEG-compressed → sent with the question to `ClaudeClient`.
8. Streamed answer renders in the panel. Ask again or dismiss.

### Architecture (packages under `com.claudeassist.overlay`)
- `overlay/` — `OverlayService` (foreground service owning the `WindowManager`
  views), `BubbleView`, `AskPanelView`. Handles drag, expand/collapse, lifecycle.
- `capture/` — `ScreenCaptureManager`: wraps `MediaProjectionManager`, holds the
  projection + a `VirtualDisplay`/`ImageReader`, exposes
  `suspend fun captureFrame(): Bitmap`. `CaptureConsentActivity` (transparent)
  obtains the `MediaProjection` intent result.
- `claude/` — `ClaudeClient` interface (`suspend fun ask(image: Bitmap?,
  prompt: String, history: List<Turn>): Flow<String>`), `AnthropicClient`
  (Messages API, vision via base64 image block, streaming SSE), data models,
  and a `FakeClaudeClient` for tests/offline UI work.
- `data/` — `SecureSettings` over `EncryptedSharedPreferences` (API key, model,
  spend guardrails); simple `HistoryStore` (Room) for past Q&A (feeds the future
  learning loop).
- `ui/` — Compose `MainActivity` with onboarding, permission status, settings,
  and a history list.
- `di/`/`util/` — small manual wiring + image compression, permission helpers.

### Key technical notes
- **Overlay:** `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE` for the bubble;
  the ask panel toggles focusable so the keyboard works. Foreground service with
  an ongoing notification (required for both overlay persistence and projection).
- **Capture reuse:** Acquire `MediaProjection` once (per service session) and
  reuse for multiple asks — do not re-prompt each time. Re-acquire if revoked.
- **Image sizing:** Downscale longest edge to ~1280px, JPEG ~80% to keep tokens
  and latency low. (Claude vision cost scales with image dimensions.)
- **Model choice:** Default to a fast/cheap model (Haiku 4.5) for snappy answers;
  expose a setting to switch to Opus for hard questions.
- **Errors:** Distinct, user-visible handling for: no/invalid API key, overlay or
  capture permission missing/revoked, capture failure, network offline, and API
  errors (401, 429 rate-limit, 529 overload) with backoff + a retry button.
- **Privacy:** Screenshots may contain sensitive content. Capture only on
  explicit bubble tap (never continuously in Phase 1); never persist raw frames
  unless the user opts in; make that explicit in onboarding.

### Files to create (representative)
- `app/build.gradle.kts`, `settings.gradle.kts`, `AndroidManifest.xml`
  (permissions: `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`, `INTERNET`).
- `overlay/OverlayService.kt`, `overlay/BubbleView.kt`, `overlay/AskPanelView.kt`
- `capture/ScreenCaptureManager.kt`, `capture/CaptureConsentActivity.kt`
- `claude/ClaudeClient.kt`, `claude/AnthropicClient.kt`, `claude/FakeClaudeClient.kt`,
  `claude/Models.kt`
- `data/SecureSettings.kt`, `data/HistoryStore.kt` (Room)
- `ui/MainActivity.kt`, `ui/OnboardingScreen.kt`, `ui/SettingsScreen.kt`,
  `ui/HistoryScreen.kt`
- Unit tests: `AnthropicClient` request-building, image compression, settings.

### Verification (end-to-end)
1. Build & install on the physical device from Android Studio.
2. Onboarding: grant "display over other apps"; enter API key + pick model.
3. Confirm the bubble floats over the home screen and a third-party app.
4. Open e.g. a browser article, tap bubble, approve the capture consent once.
5. Ask "summarize what's on screen" → get a correct streamed answer referencing
   on-screen content. Ask a follow-up without re-approving capture.
6. Error paths: wrong API key → clear message; airplane mode → offline message +
   retry; revoke overlay permission → app guides re-grant.
7. Unit tests green (`./gradlew test`).

---

## Roadmap (later phases — designed after Phase 1 works)
- **Phase 2 — Point & speak:** region-draw selection ("true circle to search");
  voice question via on-device STT; spoken answers via TTS.
- **Phase 3 — Always-on wake word:** local "hey claude"/"hi claude" hotword
  (e.g. Porcupine or an on-device model) feeding the voice pipeline; careful
  battery + privacy design (local detection, no cloud audio until triggered).
- **Phase 4 — Do-it-for-me automation:** `AccessibilityService` to read the UI
  tree and perform taps/typing/navigation in other apps from voice/text intents,
  with a confirm-before-act safety layer.
- **Phase 5 — Real-time game assist:** low-latency perceive→decide→act loop;
  hardest, per-game logic, likely a faster/streamed vision path.
- **Cross-cutting — Learning loop:** persist interactions, user corrections, and
  preferences; build a memory/RAG store so answers and actions improve over time
  and personalize to the user.

Each later phase gets its own spec → plan → implementation cycle.
