package com.jarvis.assistant

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioManager.STREAM_MUSIC
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var lang = "en-IN"
    private var wakeMode = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("jarvis_local_memory", MODE_PRIVATE) }
    private var pendingSharePackage: String? = null
    private var pendingShareLabel: String = "the selected app"

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            say("No photo selected.")
        } else {
            shareSelectedMedia(uri, "image/*")
        }
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            say("No video selected.")
        } else {
            shareSelectedMedia(uri, "video/*")
        }
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listenNow() else status("Microphone permission denied. Voice features need microphone access.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
        }
        web.addJavascriptInterface(Bridge(), "JarvisAndroid")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) tts?.language = Locale.US
    }

    private fun status(text: String) {
        runOnUiThread {
            val safe = org.json.JSONObject.quote(text)
            if (::web.isInitialized) web.evaluateJavascript("window.jarvisStatus && jarvisStatus($safe)", null)
        }
    }

    private fun listen() {
        wakeMode = false
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status("Speech recognition is unavailable. Enable a speech service on your phone.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else listenNow()
    }

    private fun startWakeSession() {
        wakeMode = true
        status("Wake-word session starting. Keep JARVIS open and say “Jarvis” followed by a command. Tap Stop to end.")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else listenNow()
    }

    private fun stopWakeSession() {
        wakeMode = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        status("Wake-word session stopped.")
    }

    private fun listenNow() {
        if (!wakeMode && !SpeechRecognizer.isRecognitionAvailable(this)) return
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    status(if (wakeMode) "Wake-word session active — say “Jarvis” and a command." else "Listening…")
                }
                override fun onBeginningOfSpeech() { if (!wakeMode) status("Voice detected") }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (!wakeMode) status("Processing…") }
                override fun onError(error: Int) {
                    if (wakeMode) handler.postDelayed({ if (wakeMode) listenNow() }, 650)
                    else status("Speech recognition stopped. Tap the microphone and try again.")
                }
                override fun onResults(results: Bundle?) {
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (wakeMode) {
                        val lower = heard.lowercase(Locale.ROOT)
                        val wakeMatch = Regex("jarvis|जार्विस", RegexOption.IGNORE_CASE).find(heard)
                        if (wakeMatch != null) {
                            val afterWake = heard.removeRange(wakeMatch.range).trim().trim(',', '.', ' ')
                            if (afterWake.isNotBlank()) command(afterWake) else say(if (lang == "hi-IN") "जी, बताइए।" else "Yes, I'm listening.")
                        } else {
                            status("Heard: $heard — say “Jarvis” before your command.")
                        }
                        if (wakeMode) handler.postDelayed({ if (wakeMode) listenNow() }, 700)
                    } else {
                        status("You said: $heard")
                        command(heard)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PROMPT, if (wakeMode) "Say Jarvis followed by a command" else "JARVIS is listening")
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            status("Could not start speech recognition. Check your speech service and microphone permission.")
        }
    }

    private fun say(text: String) {
        status(text)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun safeLaunch(intent: Intent, successMessage: String) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            say(successMessage)
        } catch (_: Exception) {
            say("That action is not available on this device.")
        }
    }

    private fun webSearch(query: String) {
        safeLaunch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))), "Searching the web for $query.")
    }

    private fun openApp(packageName: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) say("$label is not installed.") else safeLaunch(intent, "Opening $label.")
    }

    private fun openAppAndSearch(packageName: String, label: String, query: String) {
        if (query.isBlank()) { openApp(packageName, label); return }
        if (!JarvisAccessibilityService.isEnabled()) {
            say("To search inside $label by voice, enable JARVIS Accessibility only if you trust this app. I'll open $label now.")
            openApp(packageName, label)
            safeLaunch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Enable JARVIS in Accessibility settings, then return to the app.")
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) { say("$label is not installed."); return }
        safeLaunch(intent, "Opening $label to search for $query.")
        handler.postDelayed({
            if (JarvisAccessibilityService.runAction("tap search")) {
                handler.postDelayed({
                    if (JarvisAccessibilityService.runAction("type $query")) {
                        say("Search text entered: $query. Review the results and choose the correct account or chat.")
                    } else say("I opened search, but couldn't type the query. Tap the search field and say: type $query.")
                }, 500)
            } else say("$label is open. Tap its Search control, then say: type $query.")
        }, 1000)
    }

    private fun chooseMedia(kind: String, target: String) {
        pendingSharePackage = when (target) {
            "whatsapp" -> "com.whatsapp"
            "instagram" -> "com.instagram.android"
            else -> null
        }
        pendingShareLabel = target
        when (kind) {
            "photo" -> {
                say("Choose the photo you want to share. JARVIS won't scan your whole gallery.")
                pickPhoto.launch("image/*")
            }
            else -> {
                say("Choose the video you want to share. JARVIS won't scan your whole gallery.")
                pickVideo.launch("video/*")
            }
        }
    }

    private fun shareSelectedMedia(uri: Uri, mimeType: String) {
        val target = pendingSharePackage
        val label = pendingShareLabel
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "JARVIS selected media", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (target != null) setPackage(target)
        }
        safeLaunch(Intent.createChooser(send, "Choose where to share with $label"), "Media selected. Choose the destination or chat in $label and review it before sending.")
    }

    private fun saveNote(note: String) {
        val clean = note.trim().take(1500)
        if (clean.isBlank()) { say("Tell me what note to save."); return }
        val existing = prefs.getString("notes", "").orEmpty()
        val updated = (existing.lines().filter { it.isNotBlank() } + "• $clean").takeLast(50).joinToString("\n")
        prefs.edit().putString("notes", updated).apply()
        say("Saved that note on this device.")
    }

    private fun remember(item: String) {
        val clean = item.trim().take(500)
        if (clean.isBlank()) { say("Tell me what you want me to remember."); return }
        val existing = prefs.getString("memory", "").orEmpty()
        val updated = (existing.lines().filter { it.isNotBlank() } + clean).takeLast(30).joinToString("\n")
        prefs.edit().putString("memory", updated).apply()
        say("I'll remember that on this device.")
    }

    private fun showMemory() {
        val memory = prefs.getString("memory", "").orEmpty()
        val notes = prefs.getString("notes", "").orEmpty()
        val result = buildString {
            append("Saved memory: ")
            append(if (memory.isBlank()) "nothing saved yet" else memory.replace("\n", "; "))
            append(". Notes: ")
            append(if (notes.isBlank()) "no notes saved" else notes.replace("\n", "; "))
        }
        say(result.take(1800))
    }

    private fun deviceInfo() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val model = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
        val version = android.os.Build.VERSION.RELEASE
        say("Device: $model. Android version $version. Battery $level percent.")
    }

    private fun makeReminder(title: String) {
        val cleanTitle = title.trim().ifBlank { "JARVIS reminder" }.take(200)
        val start = System.currentTimeMillis() + 60 * 60 * 1000L
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, cleanTitle)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 30 * 60 * 1000L)
        }
        safeLaunch(intent, "Opening your calendar to create a reminder. Review and save it there.")
    }

    private fun musicAction(action: String) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val key = when (action) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            say("Sent the $action media command. Your music app may decide how to handle it.")
        } catch (_: Exception) {
            safeLaunch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), "Opening your music player.")
        }
    }

    private fun accessibilityAction(action: String) {
        if (!JarvisAccessibilityService.isEnabled()) {
            say("Accessibility control is not enabled. Open Accessibility settings and enable JARVIS only if you trust this app.")
            safeLaunch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Opening Android Accessibility settings.")
            return
        }
        if (JarvisAccessibilityService.runAction(action)) say("Done: $action.")
        else say("I couldn't perform $action on this screen. Android or the current app may block that action.")
    }

    private fun command(raw: String) {
        val q = raw.trim()
        val s = q.lowercase(Locale.ROOT)
        when {
            q.isBlank() -> say("I didn't hear a command.")
            s == "stop listening" || s == "stop wake word" || s == "stop wakeword" -> stopWakeSession()
            s == "help" || s == "what can you do" || s == "madad" || s.contains("kya kar sakte ho") || s.contains("क्या कर सकते हो") || s.contains("मदद") -> say(if (lang == "hi-IN") "मैं ऐप्स और सेटिंग्स खोल सकता हूँ, वेब खोज सकता हूँ, मौसम और समाचार दिखा सकता हूँ, कैमरा खोल सकता हूँ, संगीत नियंत्रित कर सकता हूँ, नोट्स और यादें सहेज सकता हूँ, रिमाइंडर बना सकता हूँ और बैटरी की जानकारी बता सकता हूँ।" else "I can open settings and apps, search the web, show weather and news, open the camera, control basic media keys, save notes and memory, create calendar reminders, and report battery and device details.")
            s.contains("what time") || s == "time" || s.contains("what date") || s == "date" || s.contains("kitne baje") || s.contains("samay batao") || s.contains("time batao") || s.contains("tareekh batao") || s.contains("कितने बजे") || s.contains("समय बताओ") || s.contains("तारीख बताओ") -> say((if (lang == "hi-IN") "अभी तारीख और समय है: " else "The date and time is: ") + DateFormat.getDateTimeInstance().format(Date()))
            s.startsWith("remember ") -> remember(q.substringAfter("remember ", ""))
            s.startsWith("remember that ") -> remember(q.substringAfter("remember that ", ""))
            s.startsWith("yaad rakho ") -> remember(q.substringAfter("yaad rakho ", ""))
            s.startsWith("yaad rakhna ") -> remember(q.substringAfter("yaad rakhna ", ""))
            s.startsWith("याद रखो ") -> remember(q.substringAfter("याद रखो ", ""))
            s.startsWith("note ") || s.startsWith("save note ") || s.startsWith("take a note ") || s.startsWith("note likho ") || s.startsWith("note save karo ") || s.startsWith("नोट लिखो ") -> {
                val note = when {
                    s.startsWith("note likho ") -> q.substringAfter("note likho ")
                    s.startsWith("note save karo ") -> q.substringAfter("note save karo ")
                    s.startsWith("नोट लिखो ") -> q.substringAfter("नोट लिखो ")
                    s.startsWith("save note ") -> q.substringAfter("save note ")
                    s.startsWith("take a note ") -> q.substringAfter("take a note ")
                    else -> q.substringAfter("note ")
                }
                saveNote(note)
            }
            s.contains("what do you remember") || s.contains("show my notes") || s.contains("read my notes") || s.contains("meri notes dikhao") || s.contains("kya yaad hai") || s.contains("मेरे नोट्स दिखाओ") || s.contains("क्या याद है") -> showMemory()
            s.contains("unlock phone") || s.contains("unlock device") || s.contains("enter my password") || s.contains("phone unlock karo") || s.contains("mobile unlock karo") || s.contains("फोन अनलॉक करो") || s.contains("मोबाइल अनलॉक करो") -> say(if (lang == "hi-IN") "आपकी सुरक्षा के लिए JARVIS आपका लॉक पासवर्ड नहीं ले सकता और न ही लॉक स्क्रीन को बायपास कर सकता है। कृपया अपने फोन का सामान्य PIN, पासवर्ड, पैटर्न या बायोमेट्रिक्स इस्तेमाल करें।" else "For your security, JARVIS cannot collect or enter your screen-lock password or bypass Android's lock screen. Unlock using your phone's normal PIN, password, pattern, or biometrics.")
            s.contains("enable accessibility") || s.contains("accessibility settings") || s.contains("accessibility kholo") || s.contains("एक्सेसिबिलिटी खोलो") -> safeLaunch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), if (lang == "hi-IN") "एक्सेसिबिलिटी सेटिंग्स खोल रहा हूँ। सेवा चालू करने से पहले अनुमतियाँ पढ़ें।" else "Opening Accessibility settings. Review the service permissions before enabling.")
            s == "go home" || s == "home screen" || s.contains("ghar jao") || s.contains("home par jao") || s.contains("होम पर जाओ") || s.contains("घर जाओ") || s.contains("होम स्क्रीन पर जाओ") -> accessibilityAction("home")
            s == "go back" || s == "press back" || s.contains("wapas jao") || s.contains("peeche jao") || s.contains("वापस जाओ") || s.contains("पीछे जाओ") -> accessibilityAction("back")
            s.contains("recent apps") || s == "open recents" || s.contains("recent apps kholo") || s.contains("हाल के ऐप") || s.contains("recent kholo") -> accessibilityAction("recents")
            s.contains("open notifications") || s == "notifications" || s.contains("notification kholo") || s.contains("notifications kholo") || s.contains("नोटिफिकेशन खोलो") || s.contains("सूचनाएं दिखाओ") -> accessibilityAction("notifications")
            s.contains("quick settings") || s.contains("quick settings kholo") || s.contains("क्विक सेटिंग्स खोलो") -> accessibilityAction("quick settings")
            s.contains("lock device") || s == "lock screen" || s.contains("phone lock karo") || s.contains("mobile lock karo") || s.contains("फोन लॉक करो") || s.contains("मोबाइल लॉक करो") -> accessibilityAction("lock screen")
            s == "scroll down" || s.contains("neeche scroll karo") || s.contains("नीचे स्क्रॉल करो") || s.contains("neeche jao") -> accessibilityAction("scroll down")
            s == "scroll up" || s.contains("upar scroll karo") || s.contains("ऊपर स्क्रॉल करो") || s.contains("upar jao") -> accessibilityAction("scroll up")
            s.startsWith("tap ") -> accessibilityAction(q)
            s.startsWith("click ") -> accessibilityAction(q)
            s.contains("battery") || s.contains("device info") || s.contains("phone information") || s.contains("battery kitni hai") || s.contains("phone ki jankari") || s.contains("बैटरी कितनी है") || s.contains("बैटरी दिखाओ") || s.contains("फोन की जानकारी") -> deviceInfo()
            s.contains("open camera") || s == "camera" || s.contains("take a photo") || s.contains("camera kholo") || s.contains("photo lo") || s.contains("कैमरा खोलो") || s.contains("फोटो लो") -> safeLaunch(Intent(MediaStore.ACTION_IMAGE_CAPTURE), if (lang == "hi-IN") "कैमरा खोल रहा हूँ। फोटो लेने के लिए कैमरे में बटन दबाएँ।" else "Opening the camera. Press the shutter in your camera app.")
            s.contains("weather") || s.contains("mausam") || s.contains("मौसम") -> webSearch("weather near me")
            s.contains("news") || s.contains("khabar") || s.contains("samachar") || s.contains("खबर") || s.contains("समाचार") -> safeLaunch(Intent(Intent.ACTION_VIEW, Uri.parse("https://news.google.com/")), if (lang == "hi-IN") "ताज़ा समाचार खोल रहा हूँ।" else "Opening the latest news.")
            s.contains("open chatgpt") || s.startsWith("ask ai") || s.startsWith("ai chat") -> safeLaunch(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")), "Opening ChatGPT. AI chat is handled by that service, not locally inside JARVIS.")
            s.startsWith("remind me to ") -> makeReminder(q.substringAfter("remind me to "))
            s.startsWith("mujhe yaad dilana ") -> makeReminder(q.substringAfter("mujhe yaad dilana "))
            s.startsWith("mujhe yaad dilao ") -> makeReminder(q.substringAfter("mujhe yaad dilao "))
            s.startsWith("मुझे याद दिलाना ") -> makeReminder(q.substringAfter("मुझे याद दिलाना "))
            s.startsWith("set alarm ") -> {
                val match = Regex("(\\d{1,2})(?::(\\d{2}))?").find(s.substringAfter("set alarm "))
                if (match != null) {
                    val hour = match.groupValues[1].toIntOrNull() ?: 7
                    val minute = match.groupValues[2].toIntOrNull() ?: 0
                    if (hour in 0..23 && minute in 0..59) {
                        safeLaunch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute).putExtra(AlarmClock.EXTRA_SKIP_UI, false), "Opening the alarm confirmation screen.")
                    } else say("Please give a valid time, like set alarm 7:30.")
                } else safeLaunch(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Opening alarms.")
            }
            s.contains("alarm") -> safeLaunch(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Opening alarms.")
            s.contains("pause music") || s == "pause" || s.contains("music roko") || s.contains("gaana roko") || s.contains("गाना रोको") || s.contains("म्यूजिक रोक दो") -> musicAction("pause")
            s.contains("next song") || s.contains("next track") || s.contains("agla gaana") || s.contains("अगला गाना") -> musicAction("next")
            s.contains("previous song") || s.contains("previous track") || s.contains("pichla gaana") || s.contains("पिछला गाना") -> musicAction("previous")
            s.contains("play music") || s.contains("resume music") || s == "play" || s.contains("music chalao") || s.contains("gaana chalao") || s.contains("गाना चलाओ") || s.contains("म्यूजिक चलाओ") -> musicAction("play")
            s.contains("music") || s.contains("open spotify") -> {
                val music = packageManager.getLaunchIntentForPackage("com.spotify.music")
                if (music != null) safeLaunch(music, "Opening Spotify.") else safeLaunch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), "Opening your music player.")
            }
            s.contains("wifi") || s.contains("wi-fi") || s.contains("वाई फाई") || s.contains("वाई-फाई") || s.contains("wifi kholo") || s.contains("वाई-फाई खोलो") -> safeLaunch(Intent(Settings.ACTION_WIFI_SETTINGS), if (lang == "hi-IN") "वाई-फाई सेटिंग्स खोल रहा हूँ। कनेक्शन आप वहाँ बदल सकते हैं।" else "Opening Wi-Fi settings. Android requires you to change the connection there.")
            s.contains("bluetooth") || s.contains("ब्लूटूथ") -> safeLaunch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), if (lang == "hi-IN") "ब्लूटूथ सेटिंग्स खोल रहा हूँ।" else "Opening Bluetooth settings.")
            s.contains("location settings") || s.contains("gps") -> safeLaunch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), "Opening location settings.")
            s.contains("brightness") || s.contains("display settings") -> safeLaunch(Intent(Settings.ACTION_DISPLAY_SETTINGS), "Opening display settings.")
            s.contains("volume settings") -> safeLaunch(Intent(Settings.ACTION_SOUND_SETTINGS), "Opening sound settings.")
            s == "settings" || s.contains("open settings") || s.contains("settings kholo") || s.contains("सेटिंग्स खोलो") -> safeLaunch(Intent(Settings.ACTION_SETTINGS), if (lang == "hi-IN") "सेटिंग्स खोल रहा हूँ।" else "Opening settings.")
            s.startsWith("whatsapp search ") || s.startsWith("search whatsapp for ") -> {
                val term = if (s.startsWith("whatsapp search ")) q.substringAfter("whatsapp search ") else q.substringAfter("search whatsapp for ")
                openAppAndSearch("com.whatsapp", "WhatsApp", term)
            }
            s.startsWith("instagram search ") || s.startsWith("search instagram for ") -> {
                val term = if (s.startsWith("instagram search ")) q.substringAfter("instagram search ") else q.substringAfter("search instagram for ")
                openAppAndSearch("com.instagram.android", "Instagram", term)
            }
            s.contains("share photo on whatsapp") || s.contains("send photo on whatsapp") || s.contains("फोटो व्हाट्सएप पर भेजो") -> chooseMedia("photo", "whatsapp")
            s.contains("share video on whatsapp") || s.contains("send video on whatsapp") || s.contains("वीडियो व्हाट्सएप पर भेजो") -> chooseMedia("video", "whatsapp")
            s.contains("share photo on instagram") || s.contains("send photo on instagram") || s.contains("फोटो इंस्टाग्राम पर भेजो") -> chooseMedia("photo", "instagram")
            s.contains("share video on instagram") || s.contains("send video on instagram") || s.contains("वीडियो इंस्टाग्राम पर भेजो") -> chooseMedia("video", "instagram")
            s == "share photo" || s == "send photo" || s == "फोटो भेजो" -> chooseMedia("photo", "an app you choose")
            s == "share video" || s == "send video" || s == "वीडियो भेजो" -> chooseMedia("video", "an app you choose")
            s.startsWith("type ") -> accessibilityAction(q)
            s == "send message" || s == "message bhejo" || s == "संदेश भेजो" || s == "मैसेज भेजो" -> {
                if (!JarvisAccessibilityService.isEnabled()) say("Enable JARVIS Accessibility only if you trust it. I can't press Send without that permission.")
                else if (JarvisAccessibilityService.runAction("send message")) say("Send action requested. Check the chat to confirm the message was sent.")
                else say("I couldn't find a Send control. Review the message and send it manually.")
            }
            s.contains("open instagram") || s.contains("instagram kholo") || s.contains("इंस्टाग्राम खोलो") -> openApp("com.instagram.android", "Instagram")
            s.contains("open youtube") || s.contains("youtube kholo") || s.contains("यूट्यूब खोलो") -> openApp("com.google.android.youtube", "YouTube")
            s.contains("open whatsapp") || s.contains("whatsapp kholo") || s.contains("व्हाट्सएप खोलो") -> openApp("com.whatsapp", "WhatsApp")
            s.contains("open gallery") || s.contains("open photos") || s.contains("gallery kholo") || s.contains("गैलरी खोलो") || s.contains("फोटो गैलरी खोलो") -> safeLaunch(Intent(Intent.ACTION_VIEW).setType("image/*"), "Opening available photo apps.")
            s.contains("open phone") || s.contains("dialer") -> safeLaunch(Intent(Intent.ACTION_DIAL), "Opening the phone dialer. You choose whether to place the call.")
            s.startsWith("search for ") || s.startsWith("google ") -> webSearch(q.substringAfter(' '))
            s.startsWith("google par ") -> webSearch(q.substringAfter("google par "))
            s.startsWith("search karo ") || s.startsWith("खोजो ") -> webSearch(q.substringAfter(' '))
            s.startsWith("open website ") -> {
                val url = q.substringAfter("open website ").trim()
                val safeUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                safeLaunch(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)), "Opening website.")
            }
            else -> say("I heard: $q. Try saying help to hear supported commands.")
        }
    }

    inner class Bridge {
        @JavascriptInterface fun startListening() = listen()
        @JavascriptInterface fun startWakeWord() = startWakeSession()
        @JavascriptInterface fun openAccessibilitySettings() = safeLaunch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Opening Accessibility settings. Review the service permissions before enabling.")
        @JavascriptInterface fun stopWakeWord() = stopWakeSession()
        @JavascriptInterface fun setLanguage(code: String) {
            lang = when (code.uppercase(Locale.ROOT)) { "HI" -> "hi-IN"; "BN" -> "bn-IN"; else -> "en-IN" }
            tts?.language = when (code.uppercase(Locale.ROOT)) { "HI" -> Locale("hi", "IN"); "BN" -> Locale("bn", "IN"); else -> Locale.US }
        }
        @JavascriptInterface fun speak(text: String) = say(text)
        @JavascriptInterface fun saveNote(text: String) = this@MainActivity.saveNote(text)
        @JavascriptInterface fun remember(text: String) = this@MainActivity.remember(text)
        @JavascriptInterface fun showMemory() = this@MainActivity.showMemory()
        @JavascriptInterface fun askAI(text: String) {
            status("Opening ChatGPT in your browser. For built-in AI chat, configure a secure backend endpoint and API key outside the APK.")
            safeLaunch(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")), "Opening ChatGPT.")
        }
        @JavascriptInterface fun runCommand(text: String) = command(text)
    }

    override fun onDestroy() {
        wakeMode = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
