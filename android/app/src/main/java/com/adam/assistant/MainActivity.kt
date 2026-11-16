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
                    val savedVoice = selectedVoiceName.takeIf { name -> ttsVoices.any { it.name == name } }
                    selectedVoiceName = configureMaleVoice(readyEngine, language, savedVoice) ?: ""
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
                        Text("Speech voice")
                        ExposedDropdownMenuBox(expanded = voiceMenuExpanded, onExpandedChange = { voiceMenuExpanded = !voiceMenuExpanded }) {
                            OutlinedTextField(
                                value = ttsVoices.firstOrNull { it.name == selectedVoiceName }?.name ?: "Automatic voice (male if available)",
                                onValueChange = {}, readOnly = true, modifier = Modifier.menuAnchor()
                            )
                            ExposedDropdownMenu(expanded = voiceMenuExpanded, onDismissRequest = { voiceMenuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Automatic (prefer male)") }, onClick = { selectedVoiceName = ""; voiceMenuExpanded = false })
                                ttsVoices.forEach { voice ->
                                    DropdownMenuItem(
                                        text = { Text(voice.name + if (voice.isNetworkConnectionRequired) " (online)" else "") },
                                        onClick = { selectedVoiceName = voice.name; voiceMenuExpanded = false }
                                    )
                                }
                            }
                        }
                        Text("Choose a voice installed by your Android text-to-speech engine. Male voices depend on the voices your phone provides.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
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
                        selectedVoiceName = configureMaleVoice(engine, language, selectedVoiceName.takeIf { name -> ttsVoices.any { it.name == name } }) ?: ""
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
    val matchingVoices = engine.voices.orEmpty().filter { it.locale.language == locale.language }
    val maleHints = listOf("male", "david", "mark", "daniel", "aaron", "george", "james", "tom", "matthew", "guy", "baritone")
    val chosen = matchingVoices.firstOrNull { it.name == preferredVoiceName }
        ?: matchingVoices.firstOrNull { voice ->
            val name = voice.name.lowercase(Locale.ROOT)
            maleHints.any { hint -> name.contains(hint) }
        }
    if (chosen != null) engine.voice = chosen
    engine.setPitch(0.90f)
    engine.setSpeechRate(0.94f)
    return chosen?.name
}

@Composable
private fun HologramView(listening: Boolean, thinking: Boolean, talking: Boolean) {
    val motion = rememberInfiniteTransition(label = "adam-hologram")
    val phase by motion.animateFloat(
        initialValue = 0f, targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "rotation"
    )
    val bob by motion.animateFloat(
        initialValue = -7f, targetValue = 7f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
        label = "float"
    )
    val pulse by motion.animateFloat(
        initialValue = 0.72f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "glow"
    )
    val cyan = Color(0xFF42DFFF)
    val bright = Color(0xFFB8FFFF)
    Column(Modifier.fillMaxWidth().height(270.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = when { talking -> "ADAM IS SPEAKING"; thinking -> "ADAM IS THINKING"; listening -> "ADAM IS LISTENING"; else -> "ADAM • HOLOGRAM ACTIVE" },
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            color = bright, style = MaterialTheme.typography.labelSmall
        )
        Canvas(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF090D18))) {
            val cx = size.width / 2f
            val cy = size.height * 0.47f + bob
            val radius = minOf(size.width * 0.29f, size.height * 0.39f).coerceAtLeast(42f)
            // Bright outer glow and animated orbital rings.
            drawCircle(cyan.copy(alpha = 0.10f * pulse), radius * 1.48f, Offset(cx, cy))
            for (i in 0..2) {
                val angle = phase * (if (i == 1) -1f else 1f) + i * 1.05f
                val rx = radius * (1.30f + i * 0.12f)
                val ry = radius * (0.20f + i * 0.035f)
                val path = Path()
                for (step in 0..80) {
                    val a = step / 80f * 6.28318f + angle
                    val p = Offset(cx + kotlin.math.cos(a) * rx, cy + radius * 0.78f + kotlin.math.sin(a) * ry)
                    if (step == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, cyan.copy(alpha = 0.60f * pulse), style = Stroke(width = 2f))
            }
            // Filled translucent head makes the avatar visible even on low-end devices.
            drawCircle(Color(0xFF102D43).copy(alpha = 0.92f), radius, Offset(cx, cy))
            drawCircle(cyan.copy(alpha = 0.95f * pulse), radius, Offset(cx, cy), style = Stroke(width = 2.5f))
            drawOval(
                color = cyan.copy(alpha = 0.24f),
                topLeft = Offset(cx - radius * 0.80f, cy - radius * 1.05f),
                size = androidx.compose.ui.geometry.Size(radius * 1.60f, radius * 2.10f),
                style = Stroke(width = 1.2f)
            )
            // Face grid and animated scan line.
            for (i in -3..3) {
                val x = cx + i * radius * 0.22f
                drawLine(cyan.copy(alpha = 0.22f), Offset(x, cy - radius * 0.78f), Offset(x, cy + radius * 0.78f), 1f)
            }
            val scanY = cy - radius * 0.72f + ((phase / 6.28318f) * radius * 1.44f)
            drawLine(bright.copy(alpha = 0.70f), Offset(cx - radius * 0.82f, scanY), Offset(cx + radius * 0.82f, scanY), 2f)
            // Eyes, nose and mouth.
            val eyeY = cy - radius * 0.12f
            for (side in listOf(-1f, 1f)) {
                val eyeX = cx + side * radius * 0.34f
                drawCircle(bright, radius * 0.075f, Offset(eyeX, eyeY))
                drawLine(cyan, Offset(eyeX - radius * 0.15f, eyeY - radius * 0.13f), Offset(eyeX + radius * 0.15f, eyeY - radius * 0.13f), 2f)
            }
            drawLine(bright, Offset(cx, cy - radius * 0.02f), Offset(cx - radius * 0.06f, cy + radius * 0.24f), 2f)
            val mouthHeight = if (talking) radius * (0.08f + kotlin.math.abs(kotlin.math.sin(phase * 8f)) * 0.18f) else radius * 0.035f
            drawOval(
                color = bright,
                topLeft = Offset(cx - radius * 0.22f, cy + radius * 0.43f - mouthHeight / 2f),
                size = androidx.compose.ui.geometry.Size(radius * 0.44f, mouthHeight.coerceAtLeast(3f))
            )
            // Floating particles.
            for (i in 0 until 24) {
                val a = phase + i * 0.2618f
                val orbit = radius * (1.48f + (i % 3) * 0.12f)
                drawCircle(cyan.copy(alpha = 0.72f), 2f + (i % 2), Offset(cx + kotlin.math.cos(a) * orbit, cy + kotlin.math.sin(a) * orbit * 0.78f))
            }
        }
    }
}
