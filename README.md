<div align="center">

# 🫧 Claude Overlay Assistant

**A floating AI bubble for Android that sits on top of every app. Tap it, circle what's on your screen, and ask Claude about it, without leaving what you're doing.**

![Android](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack_Compose-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-30-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-35-blue)
![Status](https://img.shields.io/badge/status-personal_prototype-orange)

[Features](#-features) · [How it works](#-how-it-works) · [Setup guide](#-setup-guide) · [Using it](#-using-the-overlay) · [Troubleshooting](#-troubleshooting) · [Architecture](#-architecture)

</div>

---

## ✨ Features

- **Always-on-top bubble.** A draggable chat head that floats over every app, with an animated dropdown menu.
- **Ask about your screen.** Attach a screenshot of what you're looking at to any question with one tap (📷).
- **Precise capture modes.** Drag a **rectangle**, or use the **magic pencil** to draw a freehand loop around exactly the part of the screen you mean.
- **Uses the subscription you already have.** No API key needed: the default "brain" drives the official Claude Android app for you and reads the answer back into the overlay.
- **Pluggable brains (connectors).** Switch between:
  - **Claude (app)**: automates the installed Claude app (default)
  - **PC relay (Claude Code)**: sends questions to your own computer running [server-hub](https://github.com/samay312006/server-hub) over Tailscale
  - **Fake brain**: canned answers for testing the UI
  - *ChatGPT (app)* and *Anthropic API key*: placeholders, coming soon
- **Chat history.** Every question and answer is saved locally (Room database).
- **Driver Doctor.** A debug screen that dumps the Claude app's live UI tree, so you can fix things in minutes when the Claude app updates its layout.
- **Private by design.** Settings are stored encrypted on the device, and nothing is sent anywhere except to the brain you choose.

## 🧠 How it works

```
 tap bubble ─▶ (optional) capture screen region ─▶ active Brain.ask()
                                                      │
          ┌───────────────────────────────────────────┼──────────────────────────┐
          ▼                                           ▼                          ▼
  ClaudeAppBrain                                 RelayBrain                  FakeBrain
  AccessibilityService opens the Claude app,     HTTP to your PC's           canned replies
  shares the screenshot, types the question,     server-hub over Tailscale
  watches the answer grow until it stops
  changing, then switches back
          │                                           │
          └──────────────── answer streams into the overlay panel ◀──────────┘
```

No consumer AI subscription offers an official programmatic API, so the default connector **automates the Claude app's UI** through Android's Accessibility API. As a result:

- Each question briefly switches to the Claude app and back, so **don't touch the screen** while it says *"Asking Claude…"*.
- When the Claude app updates its layout, the selectors may need adjusting. **Driver Doctor** makes that a one-file fix.
- Automating another app may go against its terms of service. This project is a **personal, sideload-only prototype** and isn't meant for the Play Store.

The full design and roadmap (voice, wake word, in-app automation, predictive help) is in [`docs/PLAN.md`](docs/PLAN.md).

## 🚀 Setup guide

### What you need

| | Requirement |
|---|---|
| 📱 Phone | Android **11 (API 30) or newer** |
| 🤖 Claude app | The official [Claude app](https://play.google.com/store/apps/details?id=com.anthropic.claude), installed and signed in (for the default brain) |
| 💻 To build | [Android Studio](https://developer.android.com/studio) (includes the JDK and Android SDK), or JDK 17 + the Android SDK with **platform 35** |

### 1. Get the code

```bash
git clone https://github.com/samay312006/claude-overlay-assistant.git
cd claude-overlay-assistant
```

### 2. Build and install

**Option A: Android Studio (easiest)**
1. **File ▸ Open** and select the project folder. Let Gradle sync finish.
2. Plug in your phone with **USB debugging** turned on (Settings ▸ About phone ▸ tap *Build number* 7 times ▸ Developer options ▸ USB debugging).
3. Select your phone in the device dropdown and press ▶ **Run**.

**Option B: command line**
```bash
./gradlew :app:installDebug          # build + install on the connected phone
# or just build the APK:
./gradlew :app:assembleDebug         # → app/build/outputs/apk/debug/app-debug.apk
```

> The `:app` module is only included when an Android SDK is found (via `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `local.properties`). Without one, Gradle builds just the pure-Kotlin `:core` module.

### 3. First launch: grant permissions

The onboarding screen walks you through each permission:

| Permission | Why |
|---|---|
| **Display over other apps** | To draw the bubble and the chat panel on top of everything |
| **Accessibility service** | To take screenshots and to drive the Claude app |
| **Notifications** | For the foreground service that keeps the bubble alive |
| **Ignore battery optimisation** *(recommended)* | Stops Android from killing the bubble in the background |

> ⚠️ **Installed the APK from a browser or file manager?** Android 13+ greys out the Accessibility toggle under *Restricted settings*. Go to **Settings ▸ Apps ▸ Claude Overlay Assistant ▸ ⋮ ▸ Allow restricted settings**, then turn the service on. Installs from Android Studio or `adb` aren't affected.

### 4. Pick a brain

Open the app ▸ **Brains**:
- **Claude (app)** works out of the box once the Claude app is installed and signed in.
- **PC relay**: in **Settings**, enter your PC's Tailscale host name, the port (default `8088`), the server-hub password, and a model. Your phone and PC must be on the same Tailscale network.

## 🫧 Using the overlay

1. Tap the **bubble** to open the menu:
   - 💬 **Ask**: a normal chat. Tap 📷 to attach your current screen.
   - ✏️ **Capture**: drag a rectangle or draw a freehand loop, then ask about that region.
   - 🅰️ **Open Claude**: jump straight into the Claude app.
   - 🎤 **Voice**: coming soon.
2. Type your question and tap ➤.
3. While it shows *"Asking Claude… don't touch the screen"*, let it work. The answer streams into the panel.
4. Tap **Open in Claude ›** to continue the conversation in the full app.
5. Drag the bubble anywhere. Use the system Back gesture to close the panel.

## 🛠️ Troubleshooting

| Problem | Fix |
|---|---|
| Accessibility toggle is greyed out | See *Allow restricted settings* in step 3 above |
| Bubble disappears after a while | Allow *Ignore battery optimisation*, and on Xiaomi, Oppo or Samsung phones also allow autostart / unrestricted battery use |
| Ask times out or nothing gets typed | The Claude app's layout probably changed. Open **Debug ▸ Driver Doctor** with the Claude app on screen, check the dumped UI tree, and update the anchors in `ClaudeAppSelectors` (in `:core`) |
| "Set the PC's Tailscale host in Settings" | Fill in host, port and password under Settings, and check that Tailscale is connected on both devices |
| "Wrong relay password" | The password must match your server-hub web UI password |
| Gradle can't find the SDK | Open the project once in Android Studio, or create `local.properties` with `sdk.dir=/path/to/Android/Sdk` |

## 🏗️ Architecture

```
:core   (pure Kotlin, no Android, fully unit-tested)
  uitree/   UiNode / UiSnapshot model + NodeMatcher combinators
  driver/   ClaudeAppSelectors, AnswerTracker (answer-stability state machine), RunLog
  brain/    BrainEvent / BrainStatus

:app    (Android, minSdk 30, targetSdk 35)   com.claudeassist.overlay
  automation/  AssistAccessibilityService, UiTreeReader, UiQuery, ActionExecutor, ScreenReader
  brain/       Brain interface, ClaudeAppBrain, RelayBrain, FakeBrain, placeholders, BrainRegistry
  overlay/     OverlayService, OverlayWindowController, BubbleView, BubbleDropdownView,
               AskPanelView, RegionSelectView, SessionBannerView
  data/        SecureSettings (encrypted), Room DB: HistoryStore, TelemetryStore
  ui/          Compose screens: Onboarding, Brains, Settings, History, Debug (Driver Doctor)
```

**Adding a new brain:** implement the `Brain` interface (`id`, `displayName`, a `status` flow, `connect()` to check it can run, and `ask(image, prompt)` returning a `Flow<BrainEvent>`), then register it in `App.kt`.

### Running the tests

```bash
./gradlew test        # :core unit tests: no Android SDK or device needed
```

## 🗺️ Roadmap

- [x] Overlay bubble, dropdown menu, chat panel
- [x] Screen capture: full, rectangle, freehand
- [x] Claude app connector + Driver Doctor
- [x] PC relay connector (server-hub)
- [ ] Voice input and wake word
- [ ] ChatGPT app and Anthropic API connectors
- [ ] In-app automation ("do this for me")
- [ ] Predictive assistance trained on local telemetry

## 🤝 Contributing

Issues and PRs are welcome, especially new brain connectors and Driver Doctor fixes for new Claude app versions. Please keep `:core` free of Android dependencies so it stays testable on the JVM.
