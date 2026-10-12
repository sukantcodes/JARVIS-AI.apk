# JARVIS AI — Android

Kotlin/WebView Android voice-assistant starter with an animated sci-fi interface.

## Implemented in source
- Push-to-talk speech recognition and Android Text-to-Speech.
- English, Hindi and Bengali language selection.
- Explicit, foreground-only wake-word session: say “Jarvis” followed by a command; tap Stop to end. This is repeated Android speech recognition, not a low-power offline hotword engine and not designed to run while the app is closed.
- Local commands for date/time, Wi-Fi/Bluetooth/location/display/sound settings, alarms, camera launch, phone dialer, opening YouTube/WhatsApp, web search, weather search and Google News.
- Media play/pause/next/previous key dispatch (behavior depends on the active media app).
- Local notes and memory stored in app-private SharedPreferences.
- Battery percentage and basic device/Android version details.
- Calendar reminder handoff that opens the calendar event editor for the user to review and save.
- Animated holographic-style interface, quick actions, notes, memory and AI-chat launcher.
- GitHub Actions workflow to build a debug APK.

## AI chat and APIs
The AI-chat button currently opens ChatGPT in the browser. A real in-app AI conversation requires a backend service that keeps the provider API key server-side. Do not place API keys in Android source or the APK. Weather currently opens a web search and news opens Google News, so no weather/news API key is required for this baseline.

## Permissions and Android limits
- Microphone permission is requested when voice input is started.
- Camera action launches the installed camera app; JARVIS itself does not capture or store photos.
- Calendar reminders use Android's calendar insert screen; the user reviews and saves the event.
- Opening phone dialer does not place a call automatically.
- Settings screens are opened for Wi-Fi, Bluetooth, location, display and sound; Android does not allow ordinary apps to silently control every system setting.
- This app cannot fully control the entire device, bypass Android security, silently toggle all radios, read private data from other apps, or operate invisibly. A wake-word session must be explicitly started and kept in the foreground; Android restricts background microphone use and foreground-service startup.
- No contacts, call-log, SMS, accessibility, device-admin, location, or broad file permissions are requested by this baseline.

## Build APK
Open **Actions → Build JARVIS AI APK** and run the workflow, or push a commit to `main`. After success, download the `JARVIS-AI-debug-APK` artifact from the workflow run and extract `app-debug.apk`. The source has been updated, but compilation and real-device behavior must still be verified in GitHub Actions and on an Android phone.

## Main files
- `app/src/main/java/com/jarvis/assistant/MainActivity.kt` — native voice input, commands, notes/memory, reminders, device info and media controls.
- `app/src/main/assets/index.html` — animated interface and controls.
- `.github/workflows/android-apk.yml` — APK build workflow.
