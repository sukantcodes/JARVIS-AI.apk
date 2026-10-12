package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var webView: WebView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("jarvis_local_memory", MODE_PRIVATE) }

    private var language = "en-IN"
    private var geminiApiKey = ""
    private var wakeMode = false
    private var awaitingWakeCommand = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            listenNow()
        } else {
            status("Microphone permission denied. Voice features need microphone access.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        language = prefs.getString("language", "en-IN") ?: "en-IN"
        geminiApiKey = prefs.getString("gemini_api_key", "") ?: ""

        tts = TextToSpeech(this, this)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: String?): Boolean = false
        }
        webView.addJavascriptInterface(Bridge(), "JarvisAndroid")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) {
            setTtsLanguage(language)
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        handler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun setTtsLanguage(code: String) {
        val locale = when (code) {
            "hi-IN" -> Locale("hi", "IN")
            "bn-IN" -> Locale("bn", "IN")
            else -> Locale("en", "IN")
        }
        try {
            val result = tts?.setLanguage(locale) ?: TextToSpeech.LANG_MISSING_DATA
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
        } catch (_: Exception) {
            try {
                tts?.setLanguage(Locale.US)
            } catch (_: Exception) {
                // no-op
            }
        }
    }

    private fun status(text: String) {
        runOnUiThread {
            val safeText = JSONObject.quote(text)
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.jarvisStatus && jarvisStatus($safeText)", null)
            }
        }
    }

    private fun say(text: String) {
        status(text)
        if (tts == null) return
        setTtsLanguage(language)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun speakProgress(text: String) {
        status(text)
        if (tts == null) return
        setTtsLanguage(language)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "jarvis_progress")
    }

    private fun askForMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        listenNow()
    }

    private fun listen() {
        wakeMode = false
        awaitingWakeCommand = false
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status("Speech recognition is unavailable on this device.")
            return
        }
        askForMicrophonePermission()
    }

    private fun startWakeSession() {
        wakeMode = true
        awaitingWakeCommand = false
        status("Wake-word mode enabled. Say Jarvis and then your command.")
        askForMicrophonePermission()
    }

    private fun stopWakeSession() {
        wakeMode = false
        awaitingWakeCommand = false
        recognizer?.cancel()
        status("Wake-word session stopped.")
    }

    private fun listenNow() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status("Speech recognition is unavailable.")
            return
        }

        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    status(if (wakeMode) "Listening for wake word…" else "Listening…")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val detail = when (error) {
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
                        SpeechRecognizer.ERROR_NETWORK -> "network error"
                        SpeechRecognizer.ERROR_AUDIO -> "audio error"
                        SpeechRecognizer.ERROR_SERVER -> "server error"
                        SpeechRecognizer.ERROR_CLIENT -> "client error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "no speech match"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission"
                        else -> "unknown error"
                    }
                    if (wakeMode) {
                        status("Voice error: $detail. Retrying…")
                        handler.postDelayed({ if (wakeMode) listenNow() }, 1200)
                    } else {
                        status("Voice recognition failed: $detail")
                    }
                }

                override fun onResults(results: Bundle?) {
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (wakeMode) {
                        val wakeMatch = Regex("jarvis|जार्विस", RegexOption.IGNORE_CASE).find(heard)
                        when {
                            wakeMatch != null -> {
                                val remainder = heard.removeRange(wakeMatch.range).trim().trim(',', '.', ' ')
                                if (remainder.isNotBlank()) {
                                    awaitingWakeCommand = false
                                    command(remainder)
                                } else {
                                    awaitingWakeCommand = true
                                    status("Wake word detected. Say your command now.")
                                }
                            }
                            awaitingWakeCommand && heard.isNotBlank() -> {
                                awaitingWakeCommand = false
                                command(heard)
                            }
                            else -> status("Heard: $heard — say Jarvis to activate a command.")
                        }
                        if (wakeMode) {
                            handler.postDelayed({ if (wakeMode) listenNow() }, 1100)
                        }
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PROMPT, if (wakeMode) "Say Jarvis followed by a command" else "JARVIS is listening")
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            status("Could not start speech recognition on this device.")
        }
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            say("Opening settings.")
        } catch (_: Exception) {
            say("Settings could not be opened.")
        }
    }

    private fun openAppByPackage(packageName: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            say("$label is not installed.")
            return
        }
        try {
            startActivity(intent)
            say("Opening $label.")
        } catch (_: Exception) {
            say("That app could not be launched.")
        }
    }

    private fun openCamera() {
        try {
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            startActivity(intent)
            say("Opening the camera.")
        } catch (_: Exception) {
            say("The camera app could not be opened.")
        }
    }

    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://media/external/images/media"))
            startActivity(intent)
            say("Opening your gallery.")
        } catch (_: Exception) {
            say("The gallery could not be opened.")
        }
    }

    private fun openWebSearch(query: String) {
        try {
            val url = "https://www.google.com/search?q=" + Uri.encode(query)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            say("Searching the web for $query.")
        } catch (_: Exception) {
            say("I could not open web search.")
        }
    }

    private fun deviceInfo() {
        val battery = try {
            val batteryManager = getSystemService(android.os.BatteryManager::class.java)
            batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            0
        }
        val model = Build.MANUFACTURER + " " + Build.MODEL
        val version = Build.VERSION.RELEASE
        say("Device: $model. Android $version. Battery $battery percent.")
    }

    private fun readList(key: String): List<String> = prefs.getString(key, "")?.lines()?.filter { it.isNotBlank() } ?: emptyList()

    private fun writeList(key: String, values: List<String>) {
        prefs.edit().putString(key, values.filter { it.isNotBlank() }.joinToString("\n")).apply()
    }

    private fun handleBasicCommand(raw: String) {
        val q = raw.trim()
        val s = q.lowercase(Locale.ROOT)

        when {
            q.isBlank() -> say("I didn't hear a command.")
            s == "stop listening" || s == "stop wake word" || s == "stop wakeword" -> stopWakeSession()
            s == "settings" || s == "open settings" -> openSettings()
            s == "battery" || s.contains("battery") || s == "device info" -> deviceInfo()
            s.contains("open camera") || s == "camera" -> openCamera()
            s.contains("open gallery") || s.contains("gallery") || s.contains("photos") -> openGallery()
            s.contains("open whatsapp") -> openAppByPackage("com.whatsapp", "WhatsApp")
            s.contains("open instagram") -> openAppByPackage("com.instagram.android", "Instagram")
            s.contains("open youtube") -> openAppByPackage("com.google.android.youtube", "YouTube")
            s.contains("weather") -> openWebSearch("weather near me")
            s.contains("news") -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://news.google.com/")))
            s.contains("search for ") -> {
                val query = q.substringAfter("search for ")
                openWebSearch(query)
            }
            s.startsWith("remember ") -> {
                val value = q.substringAfter("remember ")
                val list = readList("memory") + value
                writeList("memory", list.takeLast(20))
                say("I will remember that on this device.")
            }
            s.startsWith("note ") -> {
                val value = q.substringAfter("note ")
                val list = readList("notes") + value
                writeList("notes", list.takeLast(20))
                say("Saved that note.")
            }
            s.startsWith("add task ") -> {
                val value = q.substringAfter("add task ")
                val list = readList("tasks") + value
                writeList("tasks", list.takeLast(50))
                say("Task added: $value")
            }
            s == "show tasks" || s == "list tasks" -> {
                val tasks = readList("tasks")
                if (tasks.isEmpty()) {
                    say("Your task list is empty.")
                } else {
                    say("Tasks: " + tasks.joinToString(", "))
                }
            }
            s.startsWith("call ") -> {
                val name = q.substringAfter("call ")
                say("I heard a call request for $name. Please confirm in the UI or with a voice confirmation.")
            }
            else -> say("I heard: $q. I can open apps, settings, search the web, and manage basic tasks.")
        }
    }

    private fun isLocalCommand(raw: String): Boolean {
        val s = raw.trim().lowercase(Locale.ROOT)
        return s.contains("open ")
            || s.contains("settings")
            || s.contains("battery")
            || s.contains("camera")
            || s.contains("weather")
            || s.contains("news")
            || s.contains("remember ")
            || s.startsWith("note ")
            || s.startsWith("add task ")
            || s == "show tasks"
            || s == "list tasks"
            || s.startsWith("call ")
            || s == "stop listening" || s == "stop wake word" || s == "stop wakeword"
    }

    private fun command(raw: String) {
        val q = raw.trim()
        if (q.isBlank()) return
        if (geminiApiKey.isNotBlank() && !isLocalCommand(q)) {
            askGemini(q)
        } else {
            handleBasicCommand(q)
        }
    }

    private fun askGemini(text: String) {
        val key = geminiApiKey.trim()
        if (key.isBlank()) {
            status("Add your Gemini API key in the AI section first.")
            return
        }

        speakProgress("I am checking your request with Gemini.")

        Thread {
            try {
                val payload = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.2))

                val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 20000
                connection.readTimeout = 30000
                connection.setRequestProperty("Content-Type", "application/json")

                connection.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val body = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                }

                if (responseCode !in 200..299) {
                    throw IllegalStateException("Gemini error: $body")
                }

                val response = JSONObject(body)
                val reply = response
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                runOnUiThread {
                    say(reply.trim())
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status("Gemini request failed: ${e.message ?: "unknown error"}")
                }
            }
        }.start()
    }

    inner class Bridge {
        @JavascriptInterface
        fun setLanguage(code: String) {
            language = when (code.uppercase(Locale.ROOT)) {
                "HI" -> "hi-IN"
                "BN" -> "bn-IN"
                else -> "en-IN"
            }
            prefs.edit().putString("language", language).apply()
            setTtsLanguage(language)
            status("Language set to ${when (language) { "hi-IN" -> "Hindi"; "bn-IN" -> "Bengali"; else -> "English" }}")
        }

        @JavascriptInterface
        fun configureGemini(key: String) {
            geminiApiKey = key.trim()
            prefs.edit().putString("gemini_api_key", geminiApiKey).apply()
            status("Gemini key stored locally on this device.")
        }

        @JavascriptInterface
        fun getDeviceStatus(): String = JSONObject()
            .put("model", Build.MANUFACTURER + " " + Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("language", language)
            .put("geminiConfigured", geminiApiKey.isNotBlank())
            .put("speechAvailable", SpeechRecognizer.isRecognitionAvailable(this@MainActivity))
            .toString()

        @JavascriptInterface
        fun startListening() = this@MainActivity.listen()

        @JavascriptInterface
        fun startWakeWord() = this@MainActivity.startWakeSession()

        @JavascriptInterface
        fun stopWakeWord() = this@MainActivity.stopWakeSession()

        @JavascriptInterface
        fun runCommand(text: String) = this@MainActivity.command(text)

        @JavascriptInterface
        fun speak(text: String) = this@MainActivity.say(text)

        @JavascriptInterface
        fun askAI(text: String) = this@MainActivity.askGemini(text)
    }
}
