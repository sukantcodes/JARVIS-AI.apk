package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
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
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var lang = "en-IN"

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listenNow() else status("Microphone permission was denied.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "JarvisAndroid")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
    }

    private fun status(text: String) {
        runOnUiThread {
            val safe = org.json.JSONObject.quote(text)
            if (::web.isInitialized) web.evaluateJavascript("window.jarvisStatus && jarvisStatus($safe)", null)
        }
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status("Speech recognition is unavailable. Enable a speech service on your phone.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else listenNow()
    }

    private fun listenNow() {
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = status("Listening…")
                override fun onBeginningOfSpeech() = status("Voice detected")
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() = status("Processing…")
                override fun onError(error: Int) = status("Speech error. Tap the microphone and try again.")
                override fun onResults(results: Bundle?) {
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    status("You said: $heard")
                    command(heard)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "JARVIS is listening")
            }
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            status("Could not start speech recognition.")
        }
    }

    private fun say(text: String) {
        status(text)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun command(raw: String) {
        val q = raw.trim()
        val s = q.lowercase(Locale.ROOT)
        when {
            s.contains("time") || s.contains("date") -> say(java.text.DateFormat.getDateTimeInstance().format(java.util.Date()))
            s.contains("wifi") || s.contains("wi-fi") -> { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); say("Opening Wi-Fi settings.") }
            s == "settings" || s.contains("open settings") -> { startActivity(Intent(Settings.ACTION_SETTINGS)); say("Opening settings.") }
            s.contains("bluetooth") -> { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); say("Opening Bluetooth settings.") }
            s.contains("alarm") -> { startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)); say("Opening alarms.") }
            s.contains("open youtube") -> openApp("com.google.android.youtube", "YouTube")
            s.contains("open whatsapp") -> openApp("com.whatsapp", "WhatsApp")
            s.startsWith("search for ") || s.startsWith("google ") -> {
                val query = q.substringAfter(' ')
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
                say("Searching the web.")
            }
            s.startsWith("open website ") -> {
                val url = q.substringAfter("open website ").trim()
                val safeUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
                say("Opening website.")
            }
            else -> say("I heard: $q. That command is not supported yet.")
        }
    }

    private fun openApp(packageName: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) say("$label is not installed.") else { startActivity(intent); say("Opening $label.") }
    }

    inner class Bridge {
        @JavascriptInterface fun startListening() = listen()
        @JavascriptInterface fun setLanguage(code: String) {
            lang = when (code.uppercase(Locale.ROOT)) { "HI" -> "hi-IN"; "BN" -> "bn-IN"; else -> "en-IN" }
            tts?.language = when (code.uppercase(Locale.ROOT)) { "HI" -> Locale("hi", "IN"); "BN" -> Locale("bn", "IN"); else -> Locale.US }
        }
        @JavascriptInterface fun speak(text: String) = say(text)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
