package com.adam.assistant

// ADAM APK release rebuild

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebResourceErrorCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AdamApp() }
    }
}

private data class ChatLine(val text: String, val fromUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdamApp() {
    val context = LocalContext.current
    val httpClient = remember { OkHttpClient() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val prefs = remember { context.getSharedPreferences("adam", 0) }
    var language by remember { mutableStateOf(prefs.getString("language", "English") ?: "English") }
    var showSettings by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var speakReplies by remember { mutableStateOf(prefs.getBoolean("speak", true)) }
    var useGoogleSearch by remember { mutableStateOf(prefs.getBoolean("google_search", true)) }
    var showDeviceTools by remember { mutableStateOf(false) }
    var wakeWordEnabled by remember { mutableStateOf(prefs.getBoolean("wake_word", false)) }
    val history = remember {
        mutableStateListOf<ChatLine>().apply {
            val raw = prefs.getString("history", "[]") ?: "[]"
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(ChatLine(o.optString("text"), o.optBoolean("user")))
                }
            }
        }
    }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsVoices by remember { mutableStateOf(listOf<android.speech.tts.Voice>()) }
    var selectedVoiceName by remember { mutableStateOf(prefs.getString("tts_voice", "") ?: "") }
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    var liveStatus by remember { mutableStateOf("Live voice is off") }
    var liveVoiceActive by remember { mutableStateOf(false) }
    var screenContext by remember { mutableStateOf("") }
    var avatarTalking by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { readyEngine ->
                    ttsVoices = readyEngine.voices.orEmpty().filter { it.locale.language == ttsLocale(language).language }.sortedBy { it.name }
                    // On startup, replace any previously saved voice with the best
                    // male-identified voice available; do not silently retain an old female voice.
                    selectedVoiceName = configureMaleVoice(readyEngine, language, null) ?: ""
                    prefs.edit().putString("tts_voice", selectedVoiceName).apply()
                }
            }
        }
        tts = engine
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { mainHandler.post { avatarTalking = true } }
            override fun onDone(utteranceId: String?) { mainHandler.post { avatarTalking = false } }
            override fun onError(utteranceId: String?) { mainHandler.post { avatarTalking = false } }
        })
        onDispose { engine.stop(); engine.shutdown() }
    }

    fun persist() {
        val arr = JSONArray()
        history.forEach { arr.put(JSONObject().put("text", it.text).put("user", it.fromUser)) }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(shareIntent, "Choose app to upload image")) }
                .onFailure { history.add(ChatLine("Couldn't open an app to share that image.", false)); persist() }
        }
    }

    fun launchInstalledApp(command: String): Boolean {
        val appName = listOf("open ", "launch ", "start ")
            .firstNotNullOfOrNull { prefix ->
                if (command.startsWith(prefix)) command.removePrefix(prefix).trim() else null
            } ?: return false
        if (appName.isBlank()) return false
        // Correct common speech-to-text misspellings of YouTube before matching installed apps.
        val normalizedAppName = when (appName.lowercase(Locale.ROOT).replace(" ", "")) {
            "yourube", "youtub", "yotube", "youtubee" -> "youtube"
            else -> appName
        }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager.queryIntentActivities(launcher, 0)
        val match = apps.firstOrNull {
            it.loadLabel(context.packageManager).toString().trim().equals(normalizedAppName, ignoreCase = true)
        } ?: apps.firstOrNull {
            it.loadLabel(context.packageManager).toString().trim().contains(normalizedAppName, ignoreCase = true)
        } ?: return false
        val launchIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(match.activityInfo.packageName, match.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(launchIntent) }.isSuccess
    }

    fun handleDeviceCommand(message: String): Boolean {
        val command = message.trim().lowercase(Locale.ROOT)

        // Web and in-app search: ADAM opens the search results; the user chooses what to open.
        val searchQuery = when {
            command.startsWith("search youtube ") -> message.trim().substringAfter("search youtube ", "")
                .let { "https://www.youtube.com/results?search_query=" + URLEncoder.encode(it, "UTF-8") }
            command.startsWith("search google ") -> message.trim().substringAfter("search google ", "")
                .let { "https://www.google.com/search?q=" + URLEncoder.encode(it, "UTF-8") }
            command.startsWith("search ") -> message.trim().substringAfter("search ", "")
                .let { "https://www.google.com/search?q=" + URLEncoder.encode(it, "UTF-8") }
            command.startsWith("find ") -> message.trim().substringAfter("find ", "")
                .let { "https://www.google.com/search?q=" + URLEncoder.encode(it, "UTF-8") }
            else -> null
        }
        if (searchQuery != null) {
            val ok = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(searchQuery))) }.isSuccess
            if (ok) {
                history.add(ChatLine(message, true))
                history.add(ChatLine("Opening search results. You can review and choose a result.", false))
                persist()
            }
            return ok
        }

        // Compose an SMS, but never silently send it.
        val smsPrefix = listOf("sms to ", "text to ", "message by sms to ").firstOrNull { command.startsWith(it) }
        if (smsPrefix != null) {
            val rest = message.trim().substring(smsPrefix.length).trim()
            val number = rest.substringBefore(" ").trim()
            val body = rest.substringAfter(" ", "").trim()
            if (number.isBlank() || body.isBlank()) {
                history.add(ChatLine("Use: SMS to 03XXXXXXXXX your message", false)); persist(); return true
            }
            val smsIntent = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + android.net.Uri.encode(number))).apply {
                putExtra("sms_body", body)
            }
            val ok = runCatching { context.startActivity(smsIntent) }.isSuccess
            if (ok) {
                history.add(ChatLine(message, true))
                history.add(ChatLine("SMS draft opened. Review the recipient and message, then tap Send yourself.", false))
                persist()
            }
            return ok
        }

        // Share text to a compatible app. The receiving app owns its final publish/send action.
        val sharePrefix = listOf("share text ", "prepare post ", "post draft ").firstOrNull { command.startsWith(it) }
        if (sharePrefix != null) {
            val draft = message.trim().substring(sharePrefix.length).trim()
            if (draft.isBlank()) return false
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, draft)
            }
            val ok = runCatching { context.startActivity(Intent.createChooser(share, "Choose app to share your draft")) }.isSuccess
            if (ok) {
                history.add(ChatLine(message, true))
                history.add(ChatLine("Your draft is ready in the selected app. Review it and publish or send it yourself.", false))
                persist()
            }
            return ok
        }

        // Pick an image, then hand it to a compatible app for the user to review/upload.
        if (command in listOf("upload photo", "upload image", "share photo", "share image", "choose photo to upload")) {
            history.add(ChatLine(message, true))
            history.add(ChatLine("Choose an image. Android will let you select an app to receive it; confirm the upload inside that app.", false))
            persist()
            imagePicker.launch("image/*")
            return true
        }

        // Support an explicit app launch followed by in-app navigation.
        // Example: "open WhatsApp and navigate to Sister".
        val appNavigation = Regex(
            "^(?:open|launch|start)\\s+(.+?)\\s+(?:and|then)\\s+(?:navigate(?:\\s+to)?|go\\s+to|open\\s+chat|open)\\s+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(message.trim())
        val appNameForNavigation = appNavigation?.groupValues?.get(1)?.trim()
        val navigationTarget = appNavigation?.groupValues?.get(2)?.trim()

        val target: Intent? = when {
            listOf("wifi settings", "wi-fi settings", "open wifi", "open wi-fi").any { command.contains(it) } ->
                Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            listOf("bluetooth settings", "open bluetooth").any { command.contains(it) } ->
                Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            listOf("display settings", "brightness settings", "open display").any { command.contains(it) } ->
                Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
            listOf("sound settings", "volume settings", "open sound").any { command.contains(it) } ->
                Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
            listOf("open alarms", "show alarms", "alarm clock").any { command.contains(it) } ->
                Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
            listOf("open dialer", "phone dialer", "open phone").any { command.contains(it) } ->
                Intent(Intent.ACTION_DIAL)
            listOf("app permissions", "app settings").any { command.contains(it) } ->
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .apply { data = android.net.Uri.parse("package:" + context.packageName) }
            listOf("open browser", "browser kholo", "browser khol do", "براوزر کھولو", "ब्राउज़र खोलो").any { command.contains(it) } ->
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com"))
            listOf("open contacts", "contacts kholo", "contact kholo", "کانٹیکٹس کھولو", "कॉन्टैक्ट्स खोलो").any { command.contains(it) } ->
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)
            listOf("open messages", "open sms", "messages kholo", "میسجز کھولو", "मैसेज खोलो").any { command.contains(it) } ->
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
            listOf("open calendar", "calendar kholo", "کیلنڈر کھولو", "कैलेंडर खोलो").any { command.contains(it) } ->
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
            listOf("open maps", "maps kholo", "نقشہ کھولو", "मैप खोलो").any { command.contains(it) } ->
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)
            listOf("open music", "music kholo", "میوزک کھولو", "म्यूजिक खोलो").any { command.contains(it) } ->
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
            listOf("location settings", "open location", "location kholo").any { command.contains(it) } ->
                Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            listOf("battery settings", "open battery").any { command.contains(it) } ->
                Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
            command.contains("device settings") || command == "open settings" || command == "settings kholo" ->
                Intent(android.provider.Settings.ACTION_SETTINGS)
            else -> null
        }
        val launched = if (appNavigation != null && !appNameForNavigation.isNullOrBlank()) {
            launchInstalledApp("open $appNameForNavigation")
        } else if (target != null) {
            runCatching { context.startActivity(target) }.isSuccess
        } else {
            launchInstalledApp(command)
        }

        if (launched && appNavigation != null && !navigationTarget.isNullOrBlank()) {
            history.add(ChatLine(message, true))
            history.add(ChatLine("Android accepted the app launch. ADAM is attempting to open '$navigationTarget' after the app loads…", false))
            persist()
            mainHandler.postDelayed({
                val result = AdamScreenAccessibilityService.performVoiceCommand("tap $navigationTarget")
                history.add(ChatLine("Navigation result: $result", false))
                persist()
            }, 1600L)
            return true
        }
        // Only explicit on-screen control instructions may reach Accessibility.
        // Ordinary chat/questions must always fall through to the AI chat request.
        val isAccessibilityCommand = command in listOf("back", "go back", "scroll up", "scroll down",
            "واپس", "پیچھے", "वापस", "نیچے کرو", "نیچے اسکرول", "ऊपर स्क्रॉल", "नीचे स्क्रॉल") ||
            listOf("tap ", "click ", "press ", "type ", "write ", "enter ",
                "کھولو بٹن ", "دباؤ ", "کلک کرو ", "لکھو ", "ٹائپ کرو ", "لکھ دیں ")
                .any { command.startsWith(it) }

        if (target == null && !launched && isAccessibilityCommand) {
            val accessibilityResult = AdamScreenAccessibilityService.performVoiceCommand(message)
            if (accessibilityResult != null) {
                history.add(ChatLine(message, true))
                history.add(ChatLine(accessibilityResult, false))
                persist()
                return true
            }
            return false
        }
        // A normal message is not a device-control failure. Let sendMessage()
        // pass it through to the AI backend instead of showing a false Android error.
        if (!launched && target == null) return false

        history.add(ChatLine(message, true))
        history.add(ChatLine(if (launched) {
            if (target == null) "Opening $command." else "Opening the requested Android control. You can confirm or change it in Android."
        } else "Android could not open that control on this device.", false))
        persist()
        return true
    }

    fun sendMessage(message: String) {
        if (message.isBlank() || busy) return
        if (handleDeviceCommand(message)) { input = ""; return }
        input = ""
        history.add(ChatLine(message, true))
        persist()
        busy = true

        val requestMessages = JSONArray()
        val languageInstruction = when (language) {
            "Urdu" -> "Reply in natural Urdu script."
            "Hindi" -> "Reply in natural Hindi using Devanagari."
            else -> "Reply in clear English."
        }
        requestMessages.put(JSONObject()
            .put("role", "system")
            .put("content", "You are ADAM, a helpful friendly AI companion. $languageInstruction Keep answers concise and useful. For ordinary greetings, questions, and conversation, answer naturally and directly. Do not respond with Android control errors, and do not claim that Android could not open a control unless the user explicitly asked to open a device setting and that local action actually failed. Device actions are handled separately by the app; never invent a device-action failure as a chat response. If screen text is supplied, use it only as context for the user's question; mention when the screen text is insufficient.\n\nVisible screen text (captured by the user-enabled Accessibility service): ${prefs.getString("screen_context", "").orEmpty().take(10000)}"))
        // Exclude stale false-control replies from older app versions so they
        // cannot be fed back to the model and repeated as if they were valid answers.
        history.filterNot { line ->
            !line.fromUser && line.text.trim().equals("Android could not open that control on this device.", ignoreCase = true)
        }.takeLast(14).forEach { line ->
            requestMessages.put(JSONObject()
                .put("role", if (line.fromUser) "user" else "assistant")
                .put("content", line.text))
        }
        val payload = JSONObject()
            .put("model", "Qwen3.8-27B")
            .put("messages", requestMessages)
            .put("max_tokens", 900)
            .put("temperature", 0.6)

        thread {
            val answer = try {
                val request = Request.Builder()
                    .url("https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val responseText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException(when (response.code) {
                            429 -> "AI server is busy. Please wait a minute and try again."
                            else -> "Online AI error (${response.code}). Please try again."
                        })
                    }
                    val content = JSONObject(responseText)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content")
                        .trim()
                    if (content.isBlank()) throw IllegalStateException("The AI server returned an empty reply.")
                    content
                }
            } catch (error: Exception) {
                "I couldn't connect to the online AI service. Please check your internet and try again. (${error.message ?: "connection error"})"
            }
            mainHandler.post {
                busy = false
                history.add(ChatLine(answer, false))
                persist()
                if (speakReplies) {
                    avatarTalking = true
                    tts?.let { engine ->
                        // Re-apply ADAM's male-preferred voice immediately before
                        // every ordinary chat reply, not only during app startup.
                        val activeVoice = configureMaleVoice(engine, language, selectedVoiceName)
                        if (!activeVoice.isNullOrBlank()) {
                            selectedVoiceName = activeVoice
                            prefs.edit().putString("tts_voice", activeVoice).apply()
                        }
                        engine.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "adam-reply")
                    }
                }
            }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) sendMessage(spoken)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, when (language) { "Urdu" -> "ur-PK"; "Hindi" -> "hi-IN"; else -> "en-US" })
            }
            runCatching { speechLauncher.launch(intent) }
        }
    }

    val livePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val serviceIntent = Intent(context, AdamLiveVoiceService::class.java)
                .setAction(AdamLiveVoiceService.ACTION_START)
                .putExtra("language", language)
            runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
                .onSuccess { liveVoiceActive = true; liveStatus = "Live voice is active in the background." }
                .onFailure { liveStatus = "Could not start live voice: ${it.message}" }
        } else liveStatus = "Microphone permission is required for live voice."
    }

    fun startLive() {
        wakeWordEnabled = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val serviceIntent = Intent(context, AdamLiveVoiceService::class.java)
                .setAction(AdamLiveVoiceService.ACTION_START)
                .putExtra("language", language)
            runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
                .onSuccess { liveVoiceActive = true; liveStatus = "Live voice is active in the background." }
                .onFailure { liveStatus = "Could not start live voice: ${it.message}" }
        } else livePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun stopLive() {
        runCatching { context.startService(Intent(context, AdamLiveVoiceService::class.java).setAction(AdamLiveVoiceService.ACTION_STOP)) }
        liveVoiceActive = false
        liveStatus = "Live voice is off"
    }

    val wakePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        wakeWordEnabled = granted
    }

    DisposableEffect(wakeWordEnabled, language) {
        if (!wakeWordEnabled || !SpeechRecognizer.isRecognitionAvailable(context)) {
            onDispose { }
        } else {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val handler = Handler(Looper.getMainLooper())
            val listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            fun restartListening() {
                if (wakeWordEnabled) {
                    handler.postDelayed({ runCatching { recognizer.startListening(listenIntent) } }, 650)
                }
            }
            fun checkWakePhrase(bundle: Bundle?): Boolean {
                val phrases = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val heard = phrases.joinToString(" ").lowercase(Locale.ROOT)
                    .replace("-", " ").replace(",", " ")
                if (heard.contains("hey adam") || heard.contains("hey ira") || heard.contains("hey aira")) {
                    wakeWordEnabled = false
                    runCatching { recognizer.cancel() }
                    startLive()
                    return true
                }
                return false
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { restartListening() }
                override fun onResults(results: Bundle?) { if (!checkWakePhrase(results)) restartListening() }
                override fun onPartialResults(partialResults: Bundle?) { checkWakePhrase(partialResults) }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            restartListening()
            onDispose {
                handler.removeCallbacksAndMessages(null)
                runCatching { recognizer.stopListening(); recognizer.cancel(); recognizer.destroy() }
            }
        }
    }


    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF65E7F2), secondary = Color(0xFFB68CFF),
        background = Color(0xFF090D18), surface = Color(0xFF151C2B)
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column { Text("ADAM", color = Color(0xFF65E7F2)); Text("Your AI companion", style = MaterialTheme.typography.labelSmall) } },
                    actions = {
                        TextButton(onClick = { showDeviceTools = true }) { Text("Device", color = Color(0xFF65E7F2)) }
                        TextButton(onClick = { if (liveVoiceActive) stopLive() else startLive() }) {
                            Icon(Icons.Default.HeadsetMic, "Live voice", tint = if (liveVoiceActive) Color(0xFF65E7F2) else Color.LightGray)
                            Text(if (liveVoiceActive) "STOP LIVE" else "LIVE VOICE", color = if (liveVoiceActive) Color(0xFF65E7F2) else Color.LightGray, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF101727))
                )
            },
            containerColor = Color(0xFF090D18),
            bottomBar = {
                Row(Modifier.fillMaxWidth().background(Color(0xFF101727)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("Message ADAM…") }, shape = RoundedCornerShape(24.dp),
                        maxLines = 4
                    )
                    IconButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, when (language) { "Urdu" -> "ur-PK"; "Hindi" -> "hi-IN"; else -> "en-US" })
                            }
                            runCatching { speechLauncher.launch(intent) }
                        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Icon(Icons.Default.Mic, "Speak", tint = Color(0xFF65E7F2)) }
                    IconButton(onClick = { sendMessage(input) }, enabled = input.isNotBlank() && !busy) {
                        Icon(Icons.Default.Send, "Send", tint = Color(0xFF65E7F2))
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp)) {
                if (liveStatus != "Live voice is off") {
                    Text(liveStatus, Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), color = Color(0xFF65E7F2), style = MaterialTheme.typography.labelMedium)
                }
                HologramView(
                    listening = liveVoiceActive,
                    thinking = busy,
                    talking = avatarTalking
                )

                if (history.isEmpty()) {
                    Spacer(Modifier.height(56.dp))
                    Text("Hello, I'm ADAM", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF65E7F2))
                    Text("Talk to me, ask anything, or use the microphone.", color = Color.LightGray)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(history) { line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
                            Surface(color = if (line.fromUser) Color(0xFF253B57) else Color(0xFF182235), shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 310.dp)) {
                                Text(line.text, Modifier.padding(13.dp), color = Color.White)
                            }
                        }
                    }
                    if (busy) item { Text("ADAM is thinking…", color = Color(0xFF65E7F2)) }
                }
            }
        }
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("ADAM Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Language")
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                            OutlinedTextField(value = language, onValueChange = {}, readOnly = true, modifier = Modifier.menuAnchor())
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                listOf("English", "Urdu", "Hindi").forEach { item ->
                                    DropdownMenuItem(text = { Text(item) }, onClick = { language = item; expanded = false })
                                }
                            }
                        }
                        Text("Speech voice")
                        ExposedDropdownMenuBox(expanded = voiceMenuExpanded, onExpandedChange = { voiceMenuExpanded = !voiceMenuExpanded }) {
                            OutlinedTextField(
                                value = ttsVoices.firstOrNull { it.name == selectedVoiceName }?.name ?: "Automatic voice (male if available)",
                                onValueChange = {}, readOnly = true, modifier = Modifier.menuAnchor()
                            )
                            ExposedDropdownMenu(expanded = voiceMenuExpanded, onDismissRequest = { voiceMenuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Automatic (prefer male)") }, onClick = {
                                    selectedVoiceName = ""
                                    tts?.let { engine ->
                                        selectedVoiceName = configureMaleVoice(engine, language, null) ?: ""
                                        ttsVoices = engine.voices.orEmpty().filter { it.locale.language == ttsLocale(language).language }.sortedBy { it.name }
                                    }
                                    voiceMenuExpanded = false
                                })
                                ttsVoices.forEach { voice ->
                                    DropdownMenuItem(
                                        text = { Text(voice.name + if (voice.isNetworkConnectionRequired) " (online)" else "") },
                                        onClick = {
                                            selectedVoiceName = voice.name
                                            tts?.let { engine ->
                                                runCatching {
                                                    engine.setLanguage(ttsLocale(language))
                                                    engine.voice = voice
                                                    engine.setPitch(1.0f)
                                                    engine.setSpeechRate(0.94f)
                                                }
                                            }
                                            voiceMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Text("Select a voice, then test it. Available voices depend on your Android TTS engine; install additional English voices in your phone's Text-to-speech settings if needed.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                        TextButton(onClick = {
                            tts?.let { engine ->
                                val selected = ttsVoices.firstOrNull { it.name == selectedVoiceName }
                                if (selected != null) {
                                    runCatching {
                                        engine.setLanguage(ttsLocale(language))
                                        engine.voice = selected
                                        engine.setPitch(1.0f)
                                        engine.setSpeechRate(0.94f)
                                        engine.speak("Hello. I am ADAM. This is my selected voice.", TextToSpeech.QUEUE_FLUSH, null, "adam-voice-test")
                                    }
                                } else {
                                    configureMaleVoice(engine, language, null)
                                    engine.speak("Hello. I am ADAM. This is my automatic voice.", TextToSpeech.QUEUE_FLUSH, null, "adam-voice-test")
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Test selected voice") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = speakReplies, onCheckedChange = { speakReplies = it })
                            Text("Speak AI replies aloud")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = useGoogleSearch, onCheckedChange = { useGoogleSearch = it })
                            Text("Search the web with Google")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = wakeWordEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        wakePermission.launch(Manifest.permission.RECORD_AUDIO)
                                    } else wakeWordEnabled = enabled
                                }
                            )
                            Column {
                                Text("Hey ADAM wake word")
                                Text("Listens only while this app is open; say “Hey ADAM”.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                            }
                        }
                        Text("Online messages are sent to OVHcloud AI to generate replies. Chat history is stored on this device.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = {
                    prefs.edit().remove("gemini_key").putString("language", language).putBoolean("speak", speakReplies).putBoolean("google_search", false).putBoolean("wake_word", wakeWordEnabled).putString("tts_voice", selectedVoiceName).apply()
                    tts?.let { engine ->
                        ttsVoices = engine.voices.orEmpty().filter { it.locale.language == ttsLocale(language).language }.sortedBy { it.name }
                        val selected = ttsVoices.firstOrNull { it.name == selectedVoiceName }
                        if (selected != null) {
                            runCatching {
                                engine.setLanguage(ttsLocale(language))
                                engine.voice = selected
                                engine.setPitch(1.0f)
                                engine.setSpeechRate(0.94f)
                            }
                        } else {
                            selectedVoiceName = configureMaleVoice(engine, language, null) ?: ""
                        }
                    }
                    showSettings = false
                }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } }
            )
        }
        if (showDeviceTools) {
            AlertDialog(
                onDismissRequest = { showDeviceTools = false },
                title = { Text("Android device tools") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Enable ADAM's optional accessibility permission to let it read visible controls, tap buttons and enter text inside apps. Android will show its system permission screen; you can turn it off anytime.")
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            showDeviceTools = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("Enable ADAM app control permission") }
                        Text("ADAM can prepare messages and posts, but will leave Send, Publish, Delete and payment confirmations to you.")
                        val actions = listOf(
                            "Wi-Fi" to android.provider.Settings.ACTION_WIFI_SETTINGS,
                            "Bluetooth" to android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
                            "Display" to android.provider.Settings.ACTION_DISPLAY_SETTINGS,
                            "Sound" to android.provider.Settings.ACTION_SOUND_SETTINGS,
                            "App permissions" to android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        )
                        actions.forEach { (label, action) ->
                            TextButton(onClick = {
                                val intent = if (action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                                    Intent(action).apply { data = android.net.Uri.parse("package:" + context.packageName) }
                                } else Intent(action)
                                runCatching { context.startActivity(intent) }
                                showDeviceTools = false
                            }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                        }
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) }
                            showDeviceTools = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("Alarms") }
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL)) }
                            showDeviceTools = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("Phone dialer") }
                        val appShortcuts = listOf(
                            "Browser" to Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com")),
                            "Contacts" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS),
                            "Messages" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
                            "Calendar" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR),
                            "Maps" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS),
                            "Music" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC),
                            "Location settings" to Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                            "Battery settings" to Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
                        )
                        appShortcuts.forEach { (label, intent) ->
                            TextButton(onClick = {
                                runCatching { context.startActivity(intent) }
                                showDeviceTools = false
                            }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showDeviceTools = false }) { Text("Close") } }
            )
        }
    }
}



private fun ttsLocale(language: String): Locale = when (language) {
    "Urdu" -> Locale("ur", "PK")
    "Hindi" -> Locale("hi", "IN")
    else -> Locale.US
}

private fun configureMaleVoice(engine: TextToSpeech, language: String, preferredVoiceName: String? = null): String? {
    val locale = ttsLocale(language)
    val languageResult = engine.setLanguage(locale)
    if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
        engine.setPitch(0.90f)
        engine.setSpeechRate(0.94f)
        return null
    }
    val matchingVoices = engine.voices.orEmpty()
        .filter { it.locale.language == locale.language }
    // Never keep a saved voice unless it also matches a male-voice hint.
    // Android does not expose a reliable gender field for TTS voices, so use
    // explicit voice-name hints and otherwise report that a male voice is unavailable.
    val maleHints = listOf("male", "male_", "masculine", "david", "mark", "daniel", "aaron", "george", "james", "tom", "matthew", "guy", "baritone", "-tpm", "_tpm", "#male")
    val isMaleHinted: (android.speech.tts.Voice) -> Boolean = { voice ->
        val name = voice.name.lowercase(Locale.ROOT)
        maleHints.any { hint -> name.contains(hint) }
    }
    // Prioritize a voice whose engine name identifies it as male.
    // Only use a saved/selected voice if the engine exposes no male-identified voice.
    // Reuse the exact voice already selected for ADAM Live Voice whenever it is available.
    val chosen = matchingVoices.firstOrNull { it.name == preferredVoiceName }
        ?: matchingVoices.firstOrNull(isMaleHinted)
    if (chosen != null) {
        engine.voice = chosen
        engine.setPitch(1.0f)
        engine.setSpeechRate(0.94f)
        return chosen.name
    }
    // Do not pretend a lower pitch proves the voice is male.
    engine.setPitch(1.0f)
    engine.setSpeechRate(0.94f)
    return null
}

@Composable
private fun HologramView(listening: Boolean, thinking: Boolean, talking: Boolean) {
    val motion = rememberInfiniteTransition(label = "adam-hologram")
    val phase by motion.animateFloat(0f, 6.28318f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "phase")
    val scan by motion.animateFloat(0f, 1f,
        animationSpec = infiniteRepeatable(tween(2300, easing = LinearEasing)), label = "scan")
    val pulse by motion.animateFloat(0.65f, 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse")
    val blink by motion.animateFloat(0f, 1f,
        animationSpec = infiniteRepeatable(tween(3600), RepeatMode.Reverse), label = "blink")
    val cyan = Color(0xFF54EFFF)
    val pale = Color(0xFFB8FFFF)
    val violet = Color(0xFFB45CFF)
    Column(Modifier.fillMaxWidth().height(330.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = when { talking -> "ADAM IS SPEAKING"; thinking -> "ADAM IS THINKING"; listening -> "ADAM IS LISTENING"; else -> "ADAM • HOLOGRAPHIC LINK" },
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            color = pale, style = MaterialTheme.typography.labelSmall
        )
        Canvas(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF050817))) {
            val w = size.width
            val h = size.height
            val cx = w * 0.50f
            val headTop = h * 0.08f
            val headBottom = h * 0.57f
            val headW = w * 0.235f
            val headH = headBottom - headTop
            val headCx = cx + kotlin.math.sin(phase) * 3.5f

            // Dark futuristic server-room backdrop and equipment racks.
            drawRect(Color(0xFF080B20))
            for (side in 0..1) {
                val left = if (side == 0) 0f else w * 0.83f
                val right = if (side == 0) w * 0.16f else w
                drawRect(Color(0xFF10152F).copy(alpha = 0.78f), Offset(left, 0f),
                    androidx.compose.ui.geometry.Size(right - left, h * 0.92f))
                drawRect(violet.copy(alpha = 0.20f), Offset(left + 4f, h * 0.04f),
                    androidx.compose.ui.geometry.Size(3f, h * 0.78f))
                for (row in 0..9) {
                    val y = h * (0.09f + row * 0.078f)
                    drawLine(Color(0xFF35466F).copy(alpha = 0.65f), Offset(left + 9f, y),
                        Offset(right - 8f, y), 2f)
                    val light = if (row % 3 == 0) violet else cyan
                    drawRect(light.copy(alpha = 0.65f), Offset(left + 13f, y + 5f),
                        androidx.compose.ui.geometry.Size(7f, 3f))
                    drawRect(Color(0xFFFFB66E).copy(alpha = 0.8f), Offset(right - 25f, y + 5f),
                        androidx.compose.ui.geometry.Size(10f, 4f))
                }
            }
            // Perspective grid behind the projection.
            for (i in 0..12) {
                val x = w * (0.25f + i * 0.042f)
                drawLine(Color(0xFF315B91).copy(alpha = 0.22f), Offset(x, h * 0.05f),
                    Offset(x, h * 0.79f), 1f)
            }
            for (i in 0..10) {
                val y = h * (0.08f + i * 0.062f)
                drawLine(Color(0xFF315B91).copy(alpha = 0.22f), Offset(w * 0.24f, y),
                    Offset(w * 0.76f, y), 1f)
            }
            // Purple scanning beam entering from the upper-right.
            val beam = Path().apply {
                moveTo(w * 0.98f, h * 0.18f)
                lineTo(cx + headW * 0.45f, h * 0.67f)
                lineTo(cx + headW * 0.20f, h * 0.69f)
                close()
            }
            drawPath(beam, violet.copy(alpha = 0.10f * pulse))
            drawLine(violet.copy(alpha = 0.68f * pulse), Offset(w * 0.98f, h * 0.18f),
                Offset(cx + headW * 0.35f, h * 0.64f), 3f)

            // Floating hologram pedestal and projection rays.
            val baseY = h * 0.88f
            for (i in 0..5) {
                val x = cx - headW * 0.48f + i * headW * 0.19f
                drawLine(cyan.copy(alpha = 0.24f + pulse * 0.12f), Offset(x, h * 0.63f),
                    Offset(x, baseY - 5f), 2f)
            }
            for (i in 0..3) {
                val rx = headW * (1.55f + i * 0.17f)
                val ring = Path()
                for (step in 0..80) {
                    val a = step / 80f * 6.28318f
                    val p = Offset(cx + kotlin.math.cos(a) * rx,
                        baseY + kotlin.math.sin(a) * h * 0.025f)
                    if (step == 0) ring.moveTo(p.x, p.y) else ring.lineTo(p.x, p.y)
                }
                drawPath(ring, cyan.copy(alpha = (0.72f - i * 0.13f) * pulse), style = Stroke(width = 2f))
            }
            drawOval(Color(0xFF42DFFF).copy(alpha = 0.12f),
                Offset(cx - headW * 1.8f, baseY - h * 0.025f),
                androidx.compose.ui.geometry.Size(headW * 3.6f, h * 0.05f))

            // Shoulder and upper torso wireframe bust.
            val shoulders = Path().apply {
                moveTo(headCx - headW * 0.28f, headBottom - 2f)
                lineTo(headCx - headW * 0.52f, h * 0.64f)
                lineTo(cx - headW * 1.75f, h * 0.72f)
                lineTo(cx - headW * 1.95f, h * 0.84f)
                quadraticTo(cx, h * 0.90f, cx + headW * 1.95f, h * 0.84f)
                lineTo(cx + headW * 1.75f, h * 0.72f)
                lineTo(headCx + headW * 0.52f, h * 0.64f)
                lineTo(headCx + headW * 0.28f, headBottom - 2f)
                close()
            }
            drawPath(shoulders, cyan.copy(alpha = 0.09f))
            drawPath(shoulders, cyan.copy(alpha = 0.95f * pulse), style = Stroke(width = 2.2f))
            for (row in 0..7) {
                val y = h * (0.68f + row * 0.026f)
                val half = headW * (0.65f + row * 0.15f)
                drawLine(cyan.copy(alpha = 0.65f), Offset(cx - half, y),
                    Offset(cx + half, y), 1.2f)
            }
            for (i in -7..7) {
                val x = cx + i * headW * 0.23f
                drawLine(cyan.copy(alpha = 0.52f), Offset(cx, h * 0.65f),
                    Offset(x, h * 0.85f), 1f)
            }
            // Neck with vertical luminous filaments.
            for (i in -4..4) {
                val x = headCx + i * headW * 0.12f
                drawLine(cyan.copy(alpha = 0.72f), Offset(x, headBottom - 3f),
                    Offset(x, h * 0.72f), 1.4f)
            }

            // Holographic head silhouette: softly filled and outlined.
            val face = Path().apply {
                moveTo(headCx, headTop)
                cubicTo(headCx - headW * 0.88f, headTop, headCx - headW, headTop + headH * 0.25f, headCx - headW * 0.88f, headTop + headH * 0.58f)
                cubicTo(headCx - headW * 0.80f, headTop + headH * 0.84f, headCx - headW * 0.48f, headBottom, headCx, headBottom)
                cubicTo(headCx + headW * 0.48f, headBottom, headCx + headW * 0.80f, headTop + headH * 0.84f, headCx + headW * 0.88f, headTop + headH * 0.58f)
                cubicTo(headCx + headW, headTop + headH * 0.25f, headCx + headW * 0.88f, headTop, headCx, headTop)
                close()
            }
            drawPath(face, Color(0xFF39DDF5).copy(alpha = 0.12f))
            drawPath(face, cyan.copy(alpha = 0.98f * pulse), style = Stroke(width = 2.5f))
            // Ear outlines.
            for (side in listOf(-1f, 1f)) {
                drawOval(cyan.copy(alpha = 0.85f), Offset(headCx + side * headW * 0.82f - 4f, headTop + headH * 0.43f),
                    androidx.compose.ui.geometry.Size(9f, headH * 0.20f), style = Stroke(width = 1.5f))
            }
            // Curved latitude and longitude mesh over the face.
            for (row in 1..13) {
                val t = row / 14f
                val y = headTop + headH * t
                val half = headW * kotlin.math.sqrt((1f - (2f * t - 1f) * (2f * t - 1f)).coerceAtLeast(0.08f)) * 0.91f
                val mesh = Path().apply {
                    moveTo(headCx - half, y)
                    quadraticTo(headCx, y + kotlin.math.sin(t * 3.14159f) * 5f, headCx + half, y)
                }
                drawPath(mesh, pale.copy(alpha = 0.62f), style = Stroke(width = 1f))
            }
            for (col in -6..6) {
                val offset = col / 7f
                val mesh = Path().apply {
                    moveTo(headCx + offset * headW * 0.10f, headTop + 2f)
                    cubicTo(headCx + offset * headW * 0.82f, headTop + headH * 0.30f,
                        headCx + offset * headW * 0.80f, headTop + headH * 0.72f,
                        headCx + offset * headW * 0.40f, headBottom - 3f)
                }
                drawPath(mesh, cyan.copy(alpha = 0.58f), style = Stroke(width = 1f))
            }
            // Bright eyes, subtle blink, nose and speaking mouth.
            val eyeY = headTop + headH * 0.48f
            val eyeH = (headH * 0.045f * (1f - blink * 0.88f)).coerceAtLeast(2f)
            for (side in listOf(-1f, 1f)) {
                val ex = headCx + side * headW * 0.40f
                drawOval(cyan.copy(alpha = 0.32f), Offset(ex - headW * 0.19f, eyeY - eyeH * 1.8f),
                    androidx.compose.ui.geometry.Size(headW * 0.38f, eyeH * 3.6f))
                drawOval(pale.copy(alpha = 0.98f), Offset(ex - headW * 0.13f, eyeY - eyeH / 2f),
                    androidx.compose.ui.geometry.Size(headW * 0.26f, eyeH))
            }
            drawLine(pale.copy(alpha = 0.8f), Offset(headCx, eyeY + 4f),
                Offset(headCx - 3f, headTop + headH * 0.72f), 1.5f)
            val mouthY = headTop + headH * 0.83f
            val mouthOpen = if (talking) 3f + kotlin.math.abs(kotlin.math.sin(phase * 8f)) * 7f else 2f
            drawOval(pale.copy(alpha = 0.95f), Offset(headCx - headW * 0.20f, mouthY),
                androidx.compose.ui.geometry.Size(headW * 0.40f, mouthOpen))
            // Scanning line sweeps down through the head.
            val scanY = headTop + headH * scan
            drawLine(pale.copy(alpha = 0.92f), Offset(headCx - headW * 0.92f, scanY),
                Offset(headCx + headW * 0.92f, scanY), 2f)
            // Tiny particles around the projected bust.
            for (i in 0 until 34) {
                val a = phase + i * 0.184f
                val rx = headW * (1.15f + (i % 4) * 0.25f)
                val py = h * (0.42f + kotlin.math.sin(a * 1.7f) * 0.32f)
                drawCircle(cyan.copy(alpha = 0.62f * pulse), 1.5f + (i % 2), Offset(cx + kotlin.math.cos(a) * rx, py))
            }
        }
    }
}
