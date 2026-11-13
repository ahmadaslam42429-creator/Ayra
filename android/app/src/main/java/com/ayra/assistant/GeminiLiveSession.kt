package com.ayra.assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Bidirectional Gemini Live audio transport.
 * The caller must obtain RECORD_AUDIO permission before start().
 */
class GeminiLiveSession(
    private val apiKey: String,
    private val language: String,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String, Boolean) -> Unit
) {
    companion object {
        private const val INPUT_RATE = 16_000
        private const val OUTPUT_RATE = 24_000
        private const val MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        private const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var ready = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null

    fun start() {
        if (running) return
        if (apiKey.isBlank()) {
            onStatus("Add your Gemini API key in Settings first.")
            return
        }
        running = true
        onStatus("Connecting to AYRA Live…")
        val url = "$ENDPOINT?key=" + URLEncoder.encode(apiKey, "UTF-8")
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject()
                    .put("setup", JSONObject()
                        .put("model", "models/$MODEL")
                        .put("generationConfig", JSONObject()
                            .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                            .put("speechConfig", JSONObject()
                                .put("voiceConfig", JSONObject()
                                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Aoede")))))
                        .put("systemInstruction", JSONObject()
                            .put("parts", org.json.JSONArray().put(JSONObject().put(
                                "text",
                                "You are AYRA, a friendly personal AI assistant. Speak naturally and concisely. Reply in $language unless the user asks for another language."
                            )))))
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    if (root.has("setupComplete")) {
                        ready = true
                        onStatus("Live voice connected. Speak to AYRA.")
                        startMicrophone(webSocket)
                    }
                    val serverContent = root.optJSONObject("serverContent")
                    val inputText = serverContent?.optJSONObject("inputTranscription")?.optString("text")
                    if (!inputText.isNullOrBlank()) onTranscript(inputText, true)
                    val outputText = serverContent?.optJSONObject("outputTranscription")?.optString("text")
                    if (!outputText.isNullOrBlank()) onTranscript(outputText, false)
                    val parts = serverContent?.optJSONObject("modelTurn")?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                            val encoded = inline.optString("data")
                            if (encoded.isNotBlank()) playAudio(Base64.decode(encoded, Base64.DEFAULT))
                        }
                    }
                    if (serverContent?.optBoolean("turnComplete") == true) {
                        onStatus("Listening…")
                    }
                }.onFailure { onStatus("Live audio error: ${it.message ?: "unknown error"}") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("Live connection failed: ${t.message ?: "network error"}")
                stop()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("Live voice disconnected.")
                stop()
            }
        })
    }

    private fun startMicrophone(webSocket: WebSocket) {
        val minBuffer = AudioRecord.getMinBufferSize(
            INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            onStatus("This device does not support the required microphone format.")
            stop()
            return
        }
        val bufferSize = maxOf(minBuffer, 4096)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            onStatus("Could not start the microphone.")
            stop()
            return
        }
        recorder = audioRecord
        audioRecord.startRecording()
        thread(name = "ayra-live-mic") {
            val samples = ShortArray(2048)
            while (running && ready) {
                val count = audioRecord.read(samples, 0, samples.size)
                if (count > 0) {
                    val bytes = ByteArray(count * 2)
                    for (i in 0 until count) {
                        val value = samples[i].toInt()
                        bytes[i * 2] = value.toByte()
                        bytes[i * 2 + 1] = (value shr 8).toByte()
                    }
                    val payload = JSONObject().put(
                        "realtimeInput",
                        JSONObject().put(
                            "audio",
                            JSONObject()
                                .put("mimeType", "audio/pcm;rate=$INPUT_RATE")
                                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        )
                    )
                    if (!webSocket.send(payload.toString())) {
                        onStatus("Live audio stream was interrupted.")
                        break
                    }
                }
            }
        }
    }

    private fun playAudio(bytes: ByteArray) {
        if (!running || bytes.isEmpty()) return
        var track = player
        if (track == null) {
            val minBuffer = AudioTrack.getMinBufferSize(
                OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(OUTPUT_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(maxOf(minBuffer, bytes.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            player = track
            track.play()
        }
        track.write(bytes, 0, bytes.size)
    }

    fun stop() {
        running = false
        ready = false
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { player?.pause(); player?.flush(); player?.stop(); player?.release() }
        player = null
        socket?.close(1000, "User ended live session")
        socket = null
        onStatus("Live voice stopped.")
    }
}
