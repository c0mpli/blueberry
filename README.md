# Blueberry

A voice-first Android launcher. Tap, talk, and it does the thing.

This repo currently implements **milestones 1–5** of the definition of done, verified on an
emulator. The model, the lock screen, the assist gesture, TTS and the canvas are not built yet.

## Toolchain

Everything is command-line; there is no Android Studio on this machine.

```bash
source scripts/env.sh        # JDK 21 is keg-only — JAVA_HOME is mandatory
./scripts/emulator-up.sh     # boots the `blueberry` AVD and waits for a real boot
./scripts/verify-milestones.sh
```

| | |
|---|---|
| JDK | 21.0.11, `/opt/homebrew/opt/openjdk@21` |
| SDK | `/opt/homebrew/share/android-commandlinetools` |
| Gradle / AGP / Kotlin | 9.6.1 / 9.3.1 / 2.4.10 |
| compileSdk / targetSdk / minSdk | 37.1 / 36 / 29 |
| Emulator | `system-images;android-36;google_apis;arm64-v8a` |

`targetSdk` is deliberately behind `compileSdk`: API 36 is the newest arm64 emulator image, so it is
the only runtime behaviour that actually gets exercised here. Raise it when a 37 image ships.

## What is verified

`./scripts/verify-milestones.sh` — 10 assertions, all passing.

1. **Default launcher.** Holds `android.app.role.HOME`; HOME returns to Blueberry; BACK does not
   exit; the surface is blank and tap-to-speak.
2. **App drawer.** Swipe up, searchable, real icons.
3. **Router purity.** 48 JVM unit tests including `RouterPurityTest`, which fails the build on any
   `android.*` / `androidx.*` import under `router/`.
4. **Capture.** "note down talk to OISL about success criteria" lands verbatim, **in airplane mode**.
5. **Fire on the partial.** "open chrome" launches Chrome from a *partial* transcript with no final
   transcript ever delivered.

## Spec corrections

The design document is wrong or stale in the following places. Each was verified against AOSP source
or current documentation before the code was written.

| Severity | Spec said | Actually |
|---|---|---|
| **Critical** | `LauncherApps.getActivityList` bypasses Android 11+ package visibility | It does not. `LauncherAppsService` passes the caller's uid straight to the package manager. Without a `<queries>` MAIN/LAUNCHER entry the drawer returns almost nothing **and throws nothing**. |
| **Critical** | Everything works in airplane mode, on `SpeechRecognizer` | `createSpeechRecognizer` resolves to whatever is in `VOICE_RECOGNITION_SERVICE`, usually Google's, which is network-backed — the AOSP javadoc says so. The code prefers `createOnDeviceSpeechRecognizer` (API 31+), but availability is an OEM build flag, not a capability probe. On this emulator the on-device recogniser exists (`SodaSpeechRecognizer`) and fails with `LANGUAGE_PACK_ERROR` because en-IN is not downloaded — `triggerModelDownload` is required and is **not yet implemented**. |
| **Critical** | Bundle Silero VAD and run it on the mic alongside `SpeechRecognizer`, for endpointing and barge-in | Two ordinary apps can never capture audio concurrently; the loser silently receives **zeros**, with no error. The barge-in design needs rearchitecting around one `AudioRecord` teed into the recogniser via `EXTRA_AUDIO_SOURCE` (API 33+). Not built. |
| **High** | Back is a no-op via `onBackPressed` | At targetSdk 36 `onBackPressed` is never called and `KEYCODE_BACK` is not dispatched. Uses a Compose `BackHandler` (a real `OnBackInvokedCallback`). |
| **High** | `getShortcuts()`/drawer need the launcher role | Only the *shortcut* APIs need `ROLE_HOME`. `getActivityList` needs no role at all, so the drawer is testable before Blueberry is ever the default. |
| **Medium** | `excludeFromRecents="true"` on the launcher activity | Redundant — `RecentTasks.isVisibleRecentTask` unconditionally hides home tasks. Removed. Added what AOSP Launcher3 actually sets: `taskAffinity=""`, `clearTaskOnLaunch`, `configChanges`, `resumeWhilePausing`. |
| **Medium** | Speech can be verified on an emulator | It cannot. The guest mic reads digital silence without `-allow-host-audio`, and even then "host audio" is the Mac's default input device — no flag accepts a fixture file. Transcripts are therefore an injectable interface; the recogniser needs a real phone. |
| **Medium** | DataStore is fine with the session service in a separate process | `preferencesDataStore` is explicitly single-process; two processes on one file corrupt reads and eventually throw. Relevant when `VoiceInteractionSessionService` lands. |
| **Low** | Create the daily note with `text/plain` | That produces `2026-07-29.md.txt`. Uses `text/markdown`, and always uses the Uri `createDocument` returns. |
| **Confirmed** | SAF `"wa"` genuinely appends | True, verified through the AOSP call chain. Note `"w"` is *not* safe for rewrites — it does not truncate on `ExternalStorageProvider`. |

## Architecture

The one rule holds: `router/` is pure Kotlin with no Android dependency, enforced by a test.

```
router/   Router, PreRouter, ResolutionCache, Pattern, Catalogue, ToolRegistry   (pure)
ui/       HomeActivity, the voice surface, VoiceVisualizer, AppDrawer
voice/    TranscriptSource + the platform recogniser
data/     AppCatalogue (LauncherApps), VaultRepo (SAF), PrefsRepo (DataStore)
tools/    IntentFactory — the only place an ActionSpec becomes an Intent
```

### The partial gate

The latency mechanism, and the fiddliest part. The cache runs against every partial transcript, and
fires the instant one resolves confidently. Three guards stop it firing on a half-heard sentence:

- **Free text never fires early** — "note down …" is still growing by definition.
- **An app name another app extends never fires early** — "open google" is an exact match, but
  Google Maps starts with the same word. Fires on a 300ms stability timer instead.
- **Ambiguity never fires** — that is a clarification, decided on the complete utterance.

Anything passing all three fires immediately. That is the "open spotify" case and most of daily use.

## Not built

Milestones 6–19. Specifically: llama.cpp/JNI and the model, GBNF grammar, KV-cache save/restore,
TTS and barge-in, the lock screen and `LockActivity`, the assist gesture and
`VoiceInteractionService`, the canvas, the morning brief, notification access, settings, the turn
log, contacts and calendar, and the `app_action` shortcut tool. `Llm` is an interface with an
`Unavailable` implementation, so unmatched input returns a clean failure rather than pretending.

The Obsidian SAF write path is implemented but only the local-JSON fallback is verified on the
emulator — picking a vault requires the SAF dialog. Verify it by hand on a real phone.
