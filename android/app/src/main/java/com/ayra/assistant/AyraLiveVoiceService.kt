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
    private var language = "English"
    private var listening = false
    private var processing = false
    private var stopped = false
    private var lastUserText = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        language = intent?.getStringExtra("language") ?: language
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
                    tts?.language = speechLocale()
                    beginListening()
                }
            }
        } else {
            tts?.language = speechLocale()
            beginListening()
        }
        return START_NOT_STICKY
    }

    private fun speechLocale(): Locale = when (language) {
        "Urdu" -> Locale("ur", "PK")
        "Hindi" -> Locale("hi", "IN")
        else -> Locale("en", "US")
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
            .setContentText("AYRA is listening for your voice. Tap Stop to end the session.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }

    private fun beginListening() {
        if (stopped || processing || listening) return
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
                        if (!stopped && !processing) scheduleListen(900)
                    }
                    override fun onResults(results: android.os.Bundle?) {
                        listening = false
                        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                        if (heard.isNotBlank()) handleUserSpeech(heard) else scheduleListen(500)
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, when (language) { "Urdu" -> "ur-PK"; "Hindi" -> "hi-IN"; else -> "en-US" })
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
            listening = true
            recognizer?.startListening(intent)
        }.onFailure {
            listening = false
            scheduleListen(1500)
        }
    }

    private fun scheduleListen(delay: Long) {
        if (!stopped) handler.postDelayed({ beginListening() }, delay)
    }

    private fun handleUserSpeech(text: String) {
        if (stopped || processing) return
        lastUserText = text
        processing = true
        saveLine(text, true)
        val prefs = getSharedPreferences("ayra", MODE_PRIVATE)
        val history = runCatching { JSONArray(prefs.getString("history", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val messages = JSONArray()
        val instruction = when (language) {
            "Urdu" -> "Reply in natural Urdu script."
            "Hindi" -> "Reply in natural Hindi using Devanagari."
            else -> "Reply in clear English."
        }
        messages.put(JSONObject().put("role", "system").put("content", "You are AYRA, a friendly real-time voice companion. $instruction Keep spoken answers concise and conversational."))
        for (i in (history.length() - 12).coerceAtLeast(0) until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            messages.put(JSONObject()
                .put("role", if (item.optBoolean("user")) "user" else "assistant")
                .put("content", item.optString("text")))
        }
        val payload = JSONObject().put("model", "Qwen3.8-27B").put("messages", messages)
            .put("max_tokens", 350).put("temperature", 0.7)
        thread {
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
            }.getOrElse { "I couldn't reach the AI service. Please try speaking again." }
            handler.post {
                if (stopped) return@post
                saveLine(answer, false)
                processing = false
                tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "ayra-live-reply")
                handler.postDelayed({ beginListening() }, 700)
            }
        }
    }

    private fun saveLine(text: String, fromUser: Boolean) {
        val prefs = getSharedPreferences("ayra", MODE_PRIVATE)
        val history = runCatching { JSONArray(prefs.getString("history", "[]") ?: "[]") }.getOrElse { JSONArray() }
        history.put(JSONObject().put("text", text).put("user", fromUser))
        while (history.length() > 60) history.remove(0)
        prefs.edit().putString("history", history.toString()).apply()
    }

    private fun stopSession() {
        stopped = true
        listening = false
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
