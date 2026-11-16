package com.adam.assistant

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
    var liveStatus by remember { mutableStateOf("Live voice is off") }
    var liveVoiceActive by remember { mutableStateOf(false) }
    var screenContext by remember { mutableStateOf("") }
    var avatarTalking by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureMaleVoice(engine, language)
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

    fun launchInstalledApp(command: String): Boolean {
        val appName = listOf("open ", "launch ", "start ")
            .firstNotNullOfOrNull { prefix ->
                if (command.startsWith(prefix)) command.removePrefix(prefix).trim() else null
            } ?: return false
        if (appName.isBlank()) return false
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager.queryIntentActivities(launcher, 0)
        val match = apps.firstOrNull {
            it.loadLabel(context.packageManager).toString().trim().equals(appName, ignoreCase = true)
        } ?: apps.firstOrNull {
            it.loadLabel(context.packageManager).toString().trim().contains(appName, ignoreCase = true)
        } ?: return false
        val launchIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(match.activityInfo.packageName, match.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(launchIntent) }.isSuccess
    }

    fun handleDeviceCommand(message: String): Boolean {
        val command = message.lowercase(Locale.ROOT)
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
        history.add(ChatLine(message, true))
        val launched = if (target != null) {
            runCatching { context.startActivity(target) }.isSuccess
        } else {
            launchInstalledApp(command)
        }
        if (target == null && !launched) {
            history.removeAt(history.lastIndex)
            return false
        }
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
            .put("content", "You are ADAM, a helpful friendly AI companion. $languageInstruction Keep answers concise and useful. If screen text is supplied, use it only as context for the user’s question; mention when the screen text is insufficient.\n\nVisible screen text (captured by the user-enabled Accessibility service): ${prefs.getString("screen_context", "").orEmpty().take(10000)}"))
        history.takeLast(14).forEach { line ->
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
                    tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "adam-reply")
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
                    prefs.edit().remove("gemini_key").putString("language", language).putBoolean("speak", speakReplies).putBoolean("google_search", false).putBoolean("wake_word", wakeWordEnabled).apply()
                    tts?.let { configureMaleVoice(it, language) }
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



private fun configureMaleVoice(engine: TextToSpeech, language: String) {
    val locale = when (language) {
        "Urdu" -> Locale("ur", "PK")
        "Hindi" -> Locale("hi", "IN")
        else -> Locale.US
    }
    engine.language = locale
    val maleHints = listOf("male", "david", "mark", "alex", "daniel", "aaron", "george", "james", "tom", "matthew", "guy")
    val matchingVoices = engine.voices.orEmpty().filter { it.locale.language == locale.language }
    val maleVoice = matchingVoices.firstOrNull { voice ->
        val voiceName = voice.name.lowercase(Locale.ROOT)
        maleHints.any { hint -> voiceName.contains(hint) }
    }
    if (maleVoice != null) engine.voice = maleVoice
}

@Composable
private fun HologramView(listening: Boolean, thinking: Boolean, talking: Boolean) {
    val motion = rememberInfiniteTransition(label = "hologram-motion")
    val phase by motion.animateFloat(
        initialValue = 0f, targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "phase"
    )
    val scan by motion.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "scan"
    )
    val blinkPhase by motion.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "blink"
    )
    Column(Modifier.fillMaxWidth().height(245.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width * 0.5f
                val cy = size.height * 0.46f
                val scale = minOf(size.width * 0.39f, size.height * 0.40f)
                val yaw = kotlin.math.sin(phase) * 0.13f + if (listening) kotlin.math.sin(phase * 2f) * 0.06f else 0f
                val bob = kotlin.math.sin(phase * 1.7f) * 2.5f
                val cyan = Color(0xFF42DFFF)
                val bright = Color(0xFF8AFFFF)
                fun point(u: Float, v: Float): Offset {
                    val y = v * 1.12f
                    val width = kotlin.math.sqrt(maxOf(0.001f, 1f - v * v)) * (0.72f - 0.08f * v)
                    val z = kotlin.math.sqrt(maxOf(0.001f, 1f - v * v)) *
                        kotlin.math.sqrt(maxOf(0.02f, 1f - u * u)) * 0.62f
                    val x = u * width
                    val xx = x * kotlin.math.cos(yaw) + z * kotlin.math.sin(yaw)
                    val zz = -x * kotlin.math.sin(yaw) + z * kotlin.math.cos(yaw)
                    val perspective = 3.8f / (3.8f - zz)
                    return Offset(cx + xx * scale * perspective, cy + bob - y * scale * perspective)
                }
                drawCircle(Color(0x2225DFFF), radius = scale * 1.12f, center = Offset(cx, cy))
                // Wireframe latitude lines
                for (row in 0..18) {
                    val v = -0.94f + row * 0.104f
                    val path = Path()
                    for (col in 0..48) {
                        val p = point(-0.99f + col * 0.04125f, v)
                        if (col == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    drawPath(path, cyan.copy(alpha = 0.52f), style = Stroke(width = 1f))
                }
                // Wireframe meridians
                for (col in 0..24) {
                    val u = -0.98f + col * 0.0816f
                    val path = Path()
                    for (row in 0..38) {
                        val p = point(u, -0.96f + row * 0.0505f)
                        if (row == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    drawPath(path, cyan.copy(alpha = 0.46f), style = Stroke(width = 0.9f))
                }
                fun feature(points: List<Pair<Float, Float>>, color: Color = bright, width: Float = 2f) {
                    val path = Path()
                    points.forEachIndexed { index, pair ->
                        val p = point(pair.first, pair.second)
                        if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    drawPath(path, color, style = Stroke(width = width))
                }
                val blink = if (blinkPhase > 0.965f) 0.15f else 1f
                for (side in listOf(-1f, 1f)) {
                    val x = side * 0.31f
                    feature(listOf(x - 0.14f to 0.15f, x - 0.07f to (0.15f + 0.045f * blink), x to (0.15f + 0.055f * blink), x + 0.07f to (0.15f + 0.045f * blink), x + 0.14f to 0.15f), bright, 2.4f)
                    feature(listOf(x - 0.13f to 0.27f, x to 0.30f, x + 0.13f to 0.27f), cyan, 1.1f)
                    val eye = point(x, 0.16f)
                    drawCircle(bright, radius = scale * 0.022f, center = eye)
                }
                feature(listOf(0f to 0.13f, 0f to -0.04f, -0.05f to -0.13f, 0f to -0.16f, 0.05f to -0.13f), bright, 1.6f)
                feature(listOf(-0.10f to -0.19f, -0.04f to -0.21f, 0f to -0.20f, 0.04f to -0.21f, 0.10f to -0.19f), cyan, 1.2f)
                val mouthOpen = if (talking) 0.035f + kotlin.math.abs(kotlin.math.sin(phase * 13f)) * 0.10f else 0.012f
                feature(listOf(-0.19f to -0.34f, -0.10f to (-0.33f + mouthOpen), 0f to (-0.34f + mouthOpen * 1.2f), 0.10f to (-0.33f + mouthOpen), 0.19f to -0.34f), bright, 2f)
                feature(listOf(-0.18f to -0.36f, -0.09f to (-0.37f - mouthOpen * 0.5f), 0f to (-0.38f - mouthOpen * 0.6f), 0.09f to (-0.37f - mouthOpen * 0.5f), 0.18f to -0.36f), cyan, 1.2f)
                feature(listOf(-0.47f to -0.42f, -0.34f to -0.73f, 0f to -0.83f, 0.34f to -0.73f, 0.47f to -0.42f), cyan, 1f)
                feature(listOf(-0.30f to -0.78f, -0.27f to -1.05f, 0f to -1.12f, 0.27f to -1.05f, 0.30f to -0.78f), cyan, 1.2f)
                // Floating particles
                for (i in 0 until 36) {
                    val px = ((i * 47f + phase * 17f) % size.width)
                    val py = ((i * 31f - phase * 19f) % size.height + size.height) % size.height
                    drawCircle(cyan.copy(alpha = 0.35f), radius = 1.2f + (i % 3) * 0.45f, center = Offset(px, py))
                }
                // Horizontal moving hologram scan band
                val scanY = scan * size.height
                drawLine(color = Color(0x5538EAFF), start = Offset(0f, scanY), end = Offset(size.width, scanY), strokeWidth = 12f)
                // Ground/orbit rings
                drawOval(cyan.copy(alpha = 0.35f), topLeft = Offset(cx - scale * 0.72f, cy + scale * 0.68f), size = androidx.compose.ui.geometry.Size(scale * 1.44f, scale * 0.25f), style = Stroke(width = 1f))
            }
            Text(
                text = when { talking -> "RESPONDING"; thinking -> "PROCESSING"; listening -> "LISTENING"; else -> "SYSTEM READY" },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp),
                color = Color(0xFF62FFF0),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
