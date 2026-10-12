# JARVIS AI — Android

Kotlin/WebView Android voice-assistant starter with an animated sci-fi interface.

## Implemented in source
- Push-to-talk speech recognition and Android Text-to-Speech.
- English, Hindi and Bengali language selection, plus Hindi/Hinglish command aliases for navigation, camera, battery info, weather/news, notes/memory, reminders, media playback, Wi-Fi/Bluetooth settings, YouTube and WhatsApp.
- Wake-word matching accepts “Jarvis” and “जार्विस”; Hindi recognition quality depends on the Android speech service and installed language support.
- Explicit foreground wake-word session: say “Jarvis” followed by a command; tap Stop to end. This is repeated Android speech recognition, not a low-power offline hotword engine, and is not designed to run while the app is closed.
- Local commands for date/time, settings shortcuts, alarms, camera launch, phone dialer, opening YouTube/WhatsApp/Instagram, web search, weather search and Google News.
- Generic voice launch for installed launcher apps by their visible app name (for example, “open app Calculator” or “Calculator kholo”), using Android's launcher-app query rather than scanning arbitrary package data.
- General accessibility-assisted visible UI interaction: tap/click visible text, type into an editable field, scroll, Home/Back/Recents, notifications and Quick Settings. Success varies by Android version, active screen and target app; this is not unrestricted access to app internals.
- Optional accessibility-assisted search entry for Instagram and WhatsApp: commands such as “Instagram search username” and “WhatsApp search contact” open the app and attempt to focus Search and enter the query. The user must verify results and open the correct profile/chat.
- Accessibility-assisted typing into the currently focused editable field with “type your message”, and a separate explicit “send message” command. UI labels and layouts vary by app/version; the send action may fail or target a different visible Send control, so inspect the chat before using it.
- Explicit photo/video sharing flow for WhatsApp or Instagram using Android's media picker and share intent. JARVIS only receives the single item the user picks; the user must choose/review the destination and complete the send/post action in the target app.
- Media play/pause/next/previous key dispatch (behavior depends on the active media app).
- Local notes and memory stored in app-private SharedPreferences.
- Battery percentage and basic device/Android version details.
- Calendar reminder handoff that opens the calendar event editor for the user to review and save.
- Optional Android AccessibilityService for explicit Home, Back, Recents, Notifications, Quick Settings, scrolling, tapping visible UI text, and locking the screen on supported Android versions.
- Animated holographic-style interface, quick actions, notes, memory and AI-chat launcher.
- GitHub Actions workflow to build a debug APK.

## AI chat and APIs
The AI-chat button currently opens ChatGPT in the browser. A real in-app AI conversation and natural-language planning layer requires a backend service that keeps the provider API key server-side. Do not put API keys in Android source or the APK. The current command interpreter is local rule-based command matching; it is not yet an integrated LLM agent. Weather currently opens a web search and news opens Google News, so no weather/news API key is required for this baseline.

## Permissions and Android limits
- Microphone permission is requested when voice input is started.
- Camera action launches the installed camera app; JARVIS itself does not capture or store photos.
- Calendar reminders use Android's calendar insert screen; the user reviews and saves the event.
- Opening the phone dialer does not place a call automatically.
- Settings screens are opened for Wi-Fi, Bluetooth, location, display and sound; Android requires user confirmation for many system changes.
- Accessibility access is optional and powerful. It can act on visible UI and may expose visible screen content to the service. Enable it only if you understand and trust this app. The service responds to explicit commands and does not log screen content. Android does not provide a universal permission for complete control of every app; secure screens, banking/DRM apps, protected fields, permission dialogs, and app-specific workflows may block automation or require manual action.
- Android prevents ordinary apps and accessibility services from unlocking a secure device by programmatically entering the owner's lock credential. JARVIS does not request, store, or type that credential. Unlock using the system's PIN, password, pattern, or biometrics. JARVIS can request a lock action on supported Android versions.
- Even with accessibility enabled, JARVIS cannot control every device function, silently toggle every radio, access private data from other apps, or operate invisibly. Some screens block automation and some actions require confirmation. The wake-word session must be explicitly started and kept in the foreground; Android restricts background microphone use.
- This baseline does not request contacts, call-log, SMS, device-admin, location, or broad file permissions. Photo/video sharing uses Android's system content picker rather than scanning the full gallery.

## Build APK
Open **Actions → Build JARVIS AI APK** and run the workflow, or push a commit to `main`. After success, download the `JARVIS-AI-debug-APK` artifact from the workflow run and extract `app-debug.apk`. Compilation and real-device behavior still need to be verified in GitHub Actions and on an Android phone.

## Main files
- `app/src/main/java/com/jarvis/assistant/MainActivity.kt` — native voice input, commands, notes/memory, reminders, device info and media controls.
- `app/src/main/java/com/jarvis/assistant/JarvisAccessibilityService.kt` — optional user-enabled accessibility actions.
- `app/src/main/assets/index.html` — animated interface and controls.
- `.github/workflows/android-apk.yml` — APK build workflow.
