# Moonshine Speech to Text — Offline Android IME + Meeting Recorder (Moonshine v2)

> **On-device Moonshine v2 streaming (tiny / base / small / medium) for any arm64-v8a Android 8.0+ device. No internet after model download. 5-40x faster than Whisper, 6.65% WER (Medium Streaming) vs Whisper Large v3 7.44%. Queue + adaptive progress + lock-screen recording.**

Same UX as WhisperAndroid — IME, bubble, meeting recorder — but engine replaced with `ai.moonshine:moonshine-voice:0.1.5` (ergodic sliding-window encoder, ONNX Runtime `.ort`, no NDK build).

## ⬇ Download the APK

**[→ Download the latest APK from Releases](https://github.com/Jackfood2/MoonshineSpeechToText/releases/latest)**

1. Tap the APK (`MoonshineSpeechToText.apk` — also on your Desktop after local build) → allow *Install unknown apps* if asked
2. Open **Moonshine Speech to Text** → enable the keyboard → pick a Moonshine model → speak

### If Google Play Protect blocks the install ("App blocked to protect your device")

**Option A — keep Play Protect on (recommended):**
1. On the warning screen, tap **More details** (or the small ▾ arrow)
2. Tap **Install anyway** → confirm. Done — this is a one-time approval per update.

**Option B — temporarily disable Play Protect:**
1. Open the **Play Store** app → tap your profile icon (top right)
2. **Play Protect** → tap the **⚙ Settings gear** (top right)
3. Turn **off** *Scan apps with Play Protect* → confirm
4. Install the APK, then turn scanning **back on**

> Why this happens: the APK is self-signed and uses sensitive APIs (microphone service, accessibility typing bridge). It contains **no ads, no analytics, no network access after model download** — you can verify in the Privacy Dashboard inside the app.

[![Download APK](https://img.shields.io/badge/⬇_Download-APK_(Releases)-2EA44F?style=for-the-badge&logo=android)](https://github.com/Jackfood2/MoonshineSpeechToText/releases/latest) [![Donate PayPal](https://img.shields.io/badge/Donate-PayPal-blue.svg?logo=paypal)](https://www.paypal.com/paypalme/jackfood2004) [![Sponsor](https://img.shields.io/badge/Sponsor-GitHub-pink.svg?logo=github)](https://github.com/sponsors/Jackfood2)
[![Release](https://img.shields.io/github/v/release/Jackfood2/MoonshineSpeechToText?label=version)](https://github.com/Jackfood2/MoonshineSpeechToText/releases/latest)

## Why Moonshine v2 vs Whisper?

|  | Moonshine v2 | Whisper |
|---|---|---|
| **Architecture** | Ergodic sliding-window streaming encoder, no zero-padding, cached incremental decode | Full-attention 30s fixed window, zero-padding, starts from scratch each time |
| **Accuracy (OpenASR avg WER)** | Medium Streaming **6.65%** (245M) / Small **7.84%** / Tiny **12.01%** | Large v3 **7.44%** (1.5B) / Small **8.59%** / Tiny **12.81%** |
| **Latency (M3/TTFT)** | Tiny **50ms** (5.8x), Small **148ms** (13.1x), Medium **258ms** (43.7x vs Large) | Tiny 289ms, Small 1940ms, Large 11286ms |
| **On-device** | 26MB tiny → 245M medium, CPU-only, offline | 39MB tiny → 1.5B large |
| **Best for** | Live voice, streaming, edge, low-latency (<200ms) | Batch/cloud, 99 languages, offline bulk |

Paper: `arxiv:2602.12241` + `arxiv:2410.15608`. Leaderboard: `huggingface.co/spaces/hf-audio/open_asr_leaderboard`.

## Changelog (Moonshine fork)

### v1.0.0-moonshine-v2 (2026-09-01) — Initial Moonshine fork
- Replaced `whisper.cpp` + `whisper_jni.c` + `CMakeLists.txt` with `ai.moonshine:moonshine-voice:0.1.5` (AAR, ONNX Runtime `.ort`)
- `MoonshineEngine.kt` wraps `Transcriber.transcribeWithoutStreaming(float[],16000)` + `MicTranscriber.load()` auto-download (`.ort` bundles to `no_backup/moonshine-models/stt-en-{4,5}`)
- `ModelManager.kt` now maps `tiny/base/small/medium` → `JNI.MOONSHINE_MODEL_ARCH_*_STREAMING` (2/3/4/5), marker files, `ModelCache.defaultRoot()` cache
- Keeps Whisper design: `TranscriptionQueue`, `WhisperKeyboardService` (now Moonshine-yellow badge), `QuickSwitchService` bubble (3-state), `MeetingRecordService`, `ProcessingService` idle-unload (60s default, slider `seekUnloadIdle 0..12` = `Never`..360s), `TextRouter`, `OutstandingStore`
- Branding: `Moonshine Speech to Text`, `Moonshine Typing Bridge`, `MOONSHINE` badge, `method.xml` label, `strings.xml`, `keyboard_view.xml` status `● Moonshine v2`
- `app/build.gradle` no NDK/CMake, `namespace com.whisperkeyboard` + `applicationId com.moonshinekeyboard` (co-installs with Whisper), `compileSdk 34`, no `whisper.keystore` required for debug
- APK built and tested on SM_S711B (Android 16, arm64-v8a) — `MicTranscriber loaded small in 14617ms`, `TranscribeQueue` pipelined chunks, lock-screen WAV → typed via `commitText()` / `a11y` / clipboard fallback
- Inherited WhisperAndroid hardening: per-session PCM buffer, crash-safe flags, drag vs long-press, mic-conflict guard, voice-gated chunking, failed WAV retry

### Inherited from WhisperAndroid v2.4.2

### v2.1.x etc — see WhisperAndroid README history (bubble hardening, yellow-state fix, instant record + concurrent load, clipboard fallback, lock-screen overlay, 3-state bubble, resume flow, accuracy UX)

![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blue)
![Moonshine](https://img.shields.io/badge/Moonshine-v2_Streaming-FFD54F)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

## Features (same as Whisper, engine swapped)

| Feature | Detail |
|---|---|
| **Voice keyboard (IME)** | Replaces Gboard. `Speak` → `Stop` → text **types into the focused field** via `InputMethodService.commitText()`. Now shows `● Moonshine v2` + `MOONSHINE` badge. |
| **Meeting recorder** | Foreground service (`microphone`) records 30 s chunks → queue → saves clean words-only `Documents/MoonshineNotes/meeting_*.txt` (migrated from WhisperNotes) + `failed/` retry. Notification `● 12:34 | Queue: 1 pending`. |
| **Queue system** | `LinkedBlockingQueue + single-thread executor`. Speak while busy → queued FIFO. `Pause / Resume / Clear Queue / Retry Failed`. Failed WAVs auto-saved to `WhisperNotes/failed/` (compat). |
| **Adaptive progress** | Mini `ProgressBar + %` on keyboard and `MainActivity`. Expected = `audioSec * avgRatio + 0.6s` per-model EMA (`ratio_*` in `whisper_stats`). Creeps 88→98%. |
| **Lock-screen** | `ImeRecordService` + `MeetingRecordService` `PARTIAL_WAKE_LOCK + foreground` → continues with screen off. |
| **Model manager** | In-app `Download / Verify`, `Ready: Tiny Streaming v2 ~34 MB` etc, `Clear All` wipes `no_backup/moonshine-models/` + markers. Models auto-downloaded on first `Download` / `TEST` via `moonshine-voice` CDN (`.ort`, not `.bin`). Sizes: tiny 34M, base ~60M, small 123M, medium 245M (recommended). On-device mid/high-end: tiny ~0.3s/10s, small ~0.8s, medium ~1.5s (vs Whisper small 2.5s/medium 6s). |

## Screenshots

> Same layouts as Whisper: (1) keyboard with mic circle + `MOONSHINE` badge + labels row, (2) MainActivity (Moonshine header), (3) Mic bubble states (grey/green/yellow).

## 🎙 Mic Bubble — setup & usage

Same as Whisper, now toggles Moonshine IME.

### One-time setup (do this first)

1. **Install permission** — in the app: **Settings → Mic Bubble → toggle ON** → allow *Display over other apps* → toggle ON again.
2. **Accessibility (required for typing into other apps)** — Android path:
   **Settings → Apps → Moonshine Speech to Text → Permissions** *(some phones: Settings → Accessibility → Installed apps)*
   then enable **"Moonshine Typing Bridge"** → Allow.
   *Without this the bubble still records, but text is held as "pending" (also auto-copied to clipboard) until you enable it.*
3. *(Optional, instant keyboard switching)* connect phone once and run:
   `adb shell pm grant com.moonshinekeyboard android.permission.WRITE_SECURE_SETTINGS`
   `adb shell ime enable com.moonshinekeyboard/com.whisperkeyboard.WhisperKeyboardService`

### Using the bubble

| Bubble color | Meaning | Tap action |
|---|---|---|
| ⚪ Light grey | Idle | **Start recording** |
| 🟢 Green | Recording | **Stop** → chunks transcribe immediately (Moonshine streaming) |
| 🟡 Yellow | Processing | Start a **new** recording anytime (previous keeps processing) |

- Yellow returns to grey automatically when every chunk is typed/held.
- **Drag** to reposition. **Long-press (≥0.5s)** = switch to Moonshine keyboard / back to default (now `com.moonshinekeyboard/...`).
- Works on the **lock screen** (tap to stop there too).
- If no text field can receive the transcript, it's **parked**: notification counts it, it's **copied to the clipboard**, and the keyboard shows a blue *"Type pending transcript"* button — one tap inserts each entry.

## Quick Start (User)

1. Download APK from **Releases** → install on your phone (allow Unknown Apps). Or use local `C:\Users\Peter-Susan\Desktop\MoonshineSpeechToText.apk` (also `Moonshine-v2-arm64-66MB.apk`).
2. Open **Moonshine Speech to Text** → `1. Enable Keyboard` → toggle ON → `2. Switch to Moonshine Speech to Text` → picks Moonshine (now auto-switches if `WRITE_SECURE_SETTINGS` granted, else shows picker).
3. Pick `small` → `Download Model` (WiFi, first run downloads `.ort` bundles ~123MB to `no_backup/moonshine-models/stt-en-4` — see logcat `downloading encoder.ort 40%` → `MicTranscriber loaded small in 14617ms`).
4. Open any app → tap text field → tap big mic circle (now with `MOONSHINE` badge) → talk → tap ■ to stop → text appears as you pause (Moonshine streaming, much faster than Whisper).
5. Or set up the **Mic Bubble** and dictate from any app / lock screen.
6. Long meeting: app → `Start Meeting Recording` → notification shows time + queue → `Stop` → find `Documents/MoonshineNotes/` (or legacy `WhisperNotes`).

## Build from Source

### Option A: Android Studio (recommended, 5–15 min first build)

```bash
# JDK 17 required
winget install EclipseAdoptium.Temurin.17.JDK
# Android SDK: SDK 34 + NDK 26.1.10909125 (NDK only if you add native code, not needed for Moonshine)
# then:
git clone https://github.com/Jackfood2/MoonshineSpeechToText.git
cd MoonshineSpeechToText
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk -> copy to Desktop as MoonshineSpeechToText.apk
```

No `whisper.cpp` clone, no `CMake 3.22.1` required. Dependency `ai.moonshine:moonshine-voice:0.1.5` fetched from Maven Central (includes ONNX Runtime, no native build).

### Option B: GitHub Actions (no local SDK)

Push this repo to GitHub → **Actions → Build APK** runs Ubuntu + JDK 17 + `./gradlew assembleDebug` → download `MoonshineSpeechToText-apk` artifact.

Requires: `INTERNET`, `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `SYSTEM_ALERT_WINDOW`.

## Project Structure

```
app/src/main/java/com/whisperkeyboard/
  MainActivity.kt              # app UI: model/lang/entryMode, download, meeting controls, queue poll (now Moonshine header, direct SWITCH)
  WhisperKeyboardService.kt    # IME: Speak/Stop, progress bar, WakeLock via ImeRecordService (now ● Moonshine v2 + MOONSHINE badge)
  MeetingRecordService.kt      # foreground meeting: chunk 30s, WakeLock, queue
  ImeRecordService.kt          # foreground holder so IME survives lock screen
  TranscriptionQueue.kt        # queue + adaptive progress (per-model avg ratio in whisper_stats, now checks Moonshine isModelReady)
  MoonshineEngine.kt           # NEW: wraps ai.moonshine.voice.Transcriber/MicTranscriber.transcribeWithoutStreaming
  WhisperEngine.kt             # shim delegating to MoonshineEngine (keeps old call sites)
  ModelManager.kt              # Moonshine v2: maps tiny/base/small/medium -> JNI.MOONSHINE_MODEL_ARCH_*_STREAMING, marker files, ModelCache
  AudioUtils.kt                # 16 kHz PCM → WAV, RMS, no-speech filter
app/src/main/res/layout/
  activity_main.xml            # Moonshine header: "Offline • Private • Moonshine v2 Streaming • Fast"
  keyboard_view.xml            # IME: Speak/Stop + MOONSHINE badge + mini bar
No app/src/main/cpp/ (removed whisper.cpp/whisper_jni.c/CMakeLists.txt - not needed)
```

## Which Files to Upload to GitHub

**Upload these (already in this folder):**
- `app/src/**`, `app/build.gradle`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/**`
- `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `gradlew.bat`
- `build.gradle`, `settings.gradle`, `gradle.properties`, `scripts/`, `.github/workflows/build.yml` (re-add after workflow scope granted)
- `README.md`, `LICENSE`, `.gitignore`, `BUILD_INSTRUCTIONS.md`

**Do NOT upload (in .gitignore):**
- `app/build/`, `app/.cxx/`, `.gradle/`, `local.properties`
- `*.apk`, `*.bin`, `*.pt`, `*.onnx`, `*.ort` (models downloaded on device to `no_backup/moonshine-models/`)
- `app/src/main/assets/` (empty)
- `.idea/`, `captures/`

Check: `git status` should show only the files above, not `build/` or `moonshine-models/`.

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | mic for voice typing / meeting |
| `INTERNET` | one-time Moonshine `.ort` model download from CDN (via moonshine-voice) |
| `FOREGROUND_SERVICE_MICROPHONE` | meeting + IME foreground recording |
| `WAKE_LOCK` | keep recording with screen off |
| `POST_NOTIFICATIONS` | meeting REC notification with Stop action |
| `SYSTEM_ALERT_WINDOW` | mic bubble overlay |

## Performance Notes (Moonshine v2 vs Whisper)

Moonshine Medium Streaming (245M) beats Whisper Large v3 (1.5B) on OpenASR (6.65% vs 7.44% WER) while being 6x smaller and 43.7x lower latency (258ms vs 11286ms). No per-app warm-up beyond first `MicTranscriber.load()` (~14s on S711B small). Keep `small` for best balance, `medium` for best accuracy (needs WiFi first download). `tiny` for instant notes (50ms TTFT). Model unload slider still respects `unload_idle_ticks` (default 60s, set 4 for 120s Whisper-style) via `ProcessingService:47` -> `MoonshineEngine.unloadIfIdle()` -> `Transcriber.close()`.

## Troubleshooting

- **No text inserted:** long-press spacebar → switch input to **Moonshine Speech to Text** (not Whisper - Whisper now uninstalled, enabled list is honeyboard + moonshine). Or tap `2. Switch to Moonshine` again (with `WRITE_SECURE_SETTINGS` granted it auto-switches).
- **Shows Whisper keyboard after switch:** you had both installed before; now Whisper is uninstalled (`pm list packages` only `com.moonshinekeyboard`), Moonshine shows yellow `MOONSHINE` badge - update APK if still grey.
- **Download failed:** grant `INTERNET` (built-in), check WiFi, retry `Download Model` - watch logcat `downloading encoder.ort 40%` / `MicTranscriber loaded small in ...ms`.
- **Battery kills meeting:** `Settings → Apps → Moonshine Speech to Text → Battery → Unrestricted`.
- **Progress stuck at 92%:** reinstall this version — adaptive baseline fixes it after 2–3 uses (same as Whisper).

## License

MIT — see `LICENSE`. Models are MIT by default (via moonshine-voice).
