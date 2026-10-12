# JARVIS AI — Android

A starter Android voice-assistant app using Kotlin, Android WebView, Android SpeechRecognizer and TextToSpeech.

## Included
- Push-to-talk speech recognition.
- Spoken responses through Android TextToSpeech.
- English, Hindi and Bengali language selection.
- Local commands for date/time, Wi-Fi, Bluetooth, settings, alarms, opening YouTube/WhatsApp, and web searches.
- GitHub Actions workflow to build a debug APK.

## Build APK
Open **Actions → Build JARVIS AI APK** and run the workflow, or push a commit to `main`. When the workflow succeeds, open its run and download the `JARVIS-AI-debug-APK` artifact. Extract the ZIP and install `app-debug.apk` on your Android device.

## Important limitations
This repository currently contains a basic JARVIS-style interface, not the full original Mark-VIII holographic HTML UI. It is a local command baseline, not a general-purpose AI assistant. Always-on wake-word detection and a cloud AI backend are not included. Speech recognition depends on the Android device's installed speech service. The APK has not been confirmed built or device-tested; check the Actions result.

## Main files
- `app/src/main/java/com/jarvis/assistant/MainActivity.kt` — voice input, speech output, command routing.
- `app/src/main/assets/index.html` — app interface.
- `.github/workflows/android-apk.yml` — cloud APK build.
