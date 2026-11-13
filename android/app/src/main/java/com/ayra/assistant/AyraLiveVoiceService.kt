package com.ayra.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AyraLiveVoiceService : Service() {
    companion object {
        const val ACTION_START = "com.ayra.assistant.LIVE_START"
        const val ACTION_STOP = "com.ayra.assistant.LIVE_STOP"
        private const val CHANNEL_ID = "ayra_live_voice"
        private const val NOTIFICATION_ID = 7301
    }

    private val handler = Handler(Looper.getMainLooper())
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var language = "English"
    @Volatile private var listening = false
    @Volatile private var processing = false
    @Volatile private var speaking = false
    @Volatile private var stopped = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences("ayra", MODE_PRIVATE)
        language = intent?.getStringExtra("language")
            ?: prefs.getString("language", "English")
            ?: "English"
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        stopped = false
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSession()
            return START_NOT_STICKY
        }
        if (tts == null) {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    configureTts()
                    installTtsListener()
                }
                beginListening()
            }
        } else {
            configureTts()
            beginListening()
        }
        return START_STICKY
    }

    private fun speechLocale(): Locale = when (language) {
        "Urdu" -> Locale("ur", "PK")
        "Hindi" -> Locale("hi", "IN")
        else -> Locale("en", "US")
    }

    private fun configureTts() {
        val engine = tts ?: return
        val result = engine.setLanguage(speechLocale())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
    }

    private fun installTtsListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking = true
            }
            override fun onDone(utteranceId: String?) {
                handler.post {
                    speaking = false
                    if (!stopped && !processing) scheduleListen(250)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post {
                    speaking = false
                    if (!stopped && !processing) scheduleListen(500)
                }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                onError(utteranceId)
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AYRA live voice", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, AyraLiveVoiceService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 7302, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AYRA live voice is active")
            .setContentText("AYRA is listening. Tap Stop to end the session.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }

    private fun beginListening() {
        if (stopped || processing || speaking || listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        runCatching {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { listening = false }
                    override fun onError(error: Int) {
                        listening = false
                        if (!stopped && !processing && !speaking) {
                            // Recreate the recognizer after transient Android speech-service errors.
                            runCatching { recognizer?.cancel(); recognizer?.destroy() }
                            recognizer = null
                            scheduleListen(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1400 else 800)
                        }
                    }
                    override fun onResults(results: android.os.Bundle?) {
                        listening = false
                        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty().trim()
                        if (heard.isNotBlank()) handleUserSpeech(heard) else scheduleListen(350)
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }
            val localeTag = when (language) {
                "Urdu" -> "ur-PK"
                "Hindi" -> "hi-IN"
                else -> "en-US"
            }
            val listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
            listening = true
            recognizer?.startListening(listenIntent)
        }.onFailure {
            listening = false
            runCatching { recognizer?.cancel(); recognizer?.destroy() }
            recognizer = null
            scheduleListen(1200)
        }
    }

    private fun scheduleListen(delay: Long) {
        if (!stopped) {
            handler.removeCallbacks(listenRunnable)
            handler.postDelayed(listenRunnable, delay)
        }
    }

    private val listenRunnable = Runnable { beginListening() }

    private fun handleUserSpeech(text: String) {
        if (stopped || processing) return
        processing = true
        saveLine(text, true)

        val commandReply = runDeviceCommand(text)
        if (commandReply != null) {
            saveLine(commandReply, false)
            processing = false
            speaking = true
            val result = tts?.speak(commandReply, TextToSpeech.QUEUE_FLUSH, null, "ayra-live-command")
            if (result == TextToSpeech.ERROR || tts == null) {
                speaking = false
                scheduleListen(400)
            }
            return
        }

        val prefs = getSharedPreferences("ayra", MODE_PRIVATE)
        val history = runCatching { JSONArray(prefs.getString("history", "[]") ?: "[]") }
            .getOrElse { JSONArray() }
        val messages = JSONArray()
        val instruction = when (language) {
            "Urdu" -> "Reply in natural Urdu script (اردو). Understand spoken Urdu and commands. If the user speaks Roman Urdu, understand it and reply in Urdu script."
            "Hindi" -> "Reply in natural Hindi using Devanagari. Understand spoken Hindi and commands."
            else -> "Reply in clear English. Understand spoken English and commands."
        }
        messages.put(JSONObject().put("role", "system").put(
            "content",
            "You are AYRA, a helpful real-time voice assistant. $instruction Follow the user's latest instruction directly. Keep spoken answers concise and conversational."
        ))
        for (i in (history.length() - 14).coerceAtLeast(0) until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            messages.put(JSONObject()
                .put("role", if (item.optBoolean("user")) "user" else "assistant")
                .put("content", item.optString("text")))
        }
        val payload = JSONObject().put("model", "Qwen3.8-27B").put("messages", messages)
            .put("max_tokens", 450).put("temperature", 0.6)

        thread(name = "ayra-live-ai") {
            val answer = runCatching {
                val request = Request.Builder()
                    .url("https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException("AI service error ${response.code}")
                    JSONObject(body).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content").trim()
                }
            }.getOrElse {
                when (language) {
                    "Urdu" -> "معذرت، ابھی AI سروس سے رابطہ نہیں ہو سکا۔ دوبارہ بولیں۔"
                    "Hindi" -> "माफ़ कीजिए, अभी AI सेवा से संपर्क नहीं हो सका। फिर से बोलें।"
                    else -> "I couldn't reach the AI service. Please try speaking again."
                }
            }
            handler.post {
                if (stopped) return@post
                saveLine(answer, false)
                processing = false
                speaking = true
                val result = tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "ayra-live-reply")
                if (result == TextToSpeech.ERROR || tts == null) {
                    speaking = false
                    scheduleListen(500)
                }
            }
        }
    }

    private fun runDeviceCommand(spoken: String): String? {
        AyraScreenAccessibilityService.performVoiceCommand(spoken)?.let { return it }
        val command = spoken.lowercase(Locale.ROOT).trim()
        val quickActions = listOf(
            listOf("open wifi", "open wi-fi", "wifi settings", "wi-fi settings", "وائی فائی کھولو", "وائی فائی سیٹنگز") to Intent(android.provider.Settings.ACTION_WIFI_SETTINGS),
            listOf("open bluetooth", "bluetooth settings", "بلوٹوتھ کھولو", "بلوٹوتھ سیٹنگز") to Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
            listOf("open settings", "device settings", "سیٹنگز کھولو", "ترتیبات کھولو", "सेटिंग्स खोलो") to Intent(android.provider.Settings.ACTION_SETTINGS),
            listOf("open browser", "browser kholo", "براوزر کھولو", "ब्राउज़र खोलो") to Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com")),
            listOf("open contacts", "contacts kholo", "کانٹیکٹس کھولو", "संपर्क खोलो") to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS),
            listOf("open messages", "open sms", "messages kholo", "میسجز کھولو", "मैसेज खोलो") to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            listOf("open calendar", "calendar kholo", "کیلنڈر کھولو", "कैलेंडर खोलो") to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR),
            listOf("open maps", "maps kholo", "نقشہ کھولو", "मैप खोलो") to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS),
            listOf("open music", "music kholo", "میوزک کھولو", "म्यूजिक खोलो") to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        )
        val selected = quickActions.firstOrNull { entry -> entry.first.any { command.contains(it) } }?.second
        if (selected != null) {
            val opened = runCatching { selected.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(selected) }.isSuccess
            if (opened) return when (language) {
                "Urdu" -> "جی، مطلوبہ ایپ یا سیٹنگ کھول دی ہے۔"
                "Hindi" -> "जी, अनुरोधित ऐप या सेटिंग खोल दी है।"
                else -> "Done. I've opened it."
            }
        }

        val prefixes = listOf("open ", "launch ", "start ", "کھولو ", "کھولیں ", "खोलो ")
        val suffixes = listOf(" kholo", " khol do", " کھولو", " کھول دو", " खोलो", " खोल दो")
        var appName = prefixes.firstNotNullOfOrNull { prefix ->
            if (command.startsWith(prefix)) spoken.substring(prefix.length).trim() else null
        }
        if (appName == null) {
            val suffix = suffixes.firstOrNull { command.endsWith(it) }
            if (suffix != null) appName = spoken.dropLast(suffix.length).trim()
        }
        if (appName.isNullOrBlank()) return null
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(packageManager).toString().trim().equals(appName, ignoreCase = true)
        } ?: packageManager.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(packageManager).toString().contains(appName, ignoreCase = true)
        } ?: return null
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(match.activityInfo.packageName, match.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { startActivity(launch) }.isSuccess
        if (!opened) return null
        return when (language) {
            "Urdu" -> "جی، $appName کھول رہا ہوں۔"
            "Hindi" -> "जी, $appName खोल रहा हूँ।"
            else -> "Opening $appName."
        }
    }

    private fun saveLine(text: String, fromUser: Boolean) {
        val prefs = getSharedPreferences("ayra", MODE_PRIVATE)
        val history = runCatching { JSONArray(prefs.getString("history", "[]") ?: "[]") }
            .getOrElse { JSONArray() }
        history.put(JSONObject().put("text", text).put("user", fromUser))
        while (history.length() > 60) history.remove(0)
        prefs.edit().putString("history", history.toString()).apply()
    }

    private fun stopSession() {
        stopped = true
        listening = false
        processing = false
        speaking = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.stopListening(); recognizer?.cancel(); recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
