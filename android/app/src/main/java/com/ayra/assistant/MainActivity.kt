package com.ayra.assistant

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
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
        setContent { AyraApp() }
    }
}

private data class ChatLine(val text: String, val fromUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AyraApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ayra", 0) }
    var apiKey by remember { mutableStateOf(prefs.getString("gemini_key", "") ?: "") }
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
    var liveSession by remember { mutableStateOf<GeminiLiveSession?>(null) }
    var liveStatus by remember { mutableStateOf("Live voice is off") }
    var avatarTalking by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = when (language) {
                    "Urdu" -> Locale("ur", "PK")
                    "Hindi" -> Locale("hi", "IN")
                    else -> Locale("en", "US")
                }
            }
        }
        tts = engine
        onDispose { engine.stop(); engine.shutdown() }
    }

    fun persist() {
        val arr = JSONArray()
        history.forEach { arr.put(JSONObject().put("text", it.text).put("user", it.fromUser)) }
        prefs.edit().putString("history", arr.toString()).apply()
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
        } ?: return false
        history.add(ChatLine(message, true))
        val launched = runCatching { context.startActivity(target) }.isSuccess
        history.add(ChatLine(if (launched) "Opening the requested Android control. You can confirm or change it in Android." else "Android could not open that control on this device.", false))
        persist()
        return true
    }

    fun sendMessage(message: String) {
        if (message.isBlank() || busy) return
        if (handleDeviceCommand(message)) { input = ""; return }
        history.add(ChatLine(message, true))
        persist()
        input = ""
        if (apiKey.isBlank()) {
            history.add(ChatLine("Please add your Gemini API key in Settings to start chatting.", false))
            persist()
            return
        }
        busy = true
        val searchEnabled = useGoogleSearch
        val prompt = buildString {
            append("You are AYRA, a helpful friendly AI assistant. Reply in $language unless the user requests another language.\n")
            history.takeLast(12).forEach { append(if (it.fromUser) "User: " else "AYRA: "); append(it.text); append("\n") }
        }
        thread {
            val answer = runCatching {
                val payload = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                if (searchEnabled) {
                    payload.put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
                }
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8")
                val request = Request.Builder().url(url).post(body).build()
                OkHttpClient().newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("Gemini API error (${response.code}): $text")
                    JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                }
            }.getOrElse { "I couldn't connect to Gemini: ${it.message ?: "Unknown error"}" }
            (context as? ComponentActivity)?.runOnUiThread {
                history.add(ChatLine(answer, false))
                persist()
                busy = false
                if (speakReplies) tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "ayra-reply")
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

    fun createLiveSession() {
        val session = GeminiLiveSession(
            apiKey = apiKey,
            language = language,
            useGoogleSearch = useGoogleSearch,
            onStatus = { status -> (context as? ComponentActivity)?.runOnUiThread {
                liveStatus = status
                avatarTalking = status.contains("speaking", ignoreCase = true)
                if (status.contains("failed", ignoreCase = true) || status.contains("disconnected", ignoreCase = true) || status.contains("error", ignoreCase = true)) {
                    liveSession = null
                }
            } },
            onTranscript = { text, fromUser ->
                (context as? ComponentActivity)?.runOnUiThread {
                    if (!(fromUser && handleDeviceCommand(text))) {
                        history.add(ChatLine(text, fromUser))
                        persist()
                    }
                }
            }
        )
        liveSession = session
        session.start()
    }

    val livePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) createLiveSession() else liveStatus = "Microphone permission is required for Live Voice."
    }

    fun startLive() {
        wakeWordEnabled = false
        if (apiKey.isBlank()) {
            showSettings = true
            liveStatus = "Add your Gemini API key to start Live Voice."
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Handler(Looper.getMainLooper()).postDelayed({ createLiveSession() }, 700)
        } else {
            livePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(liveSession) {
        onDispose { liveSession?.stop() }
    }

    val wakePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        wakeWordEnabled = granted
    }

    DisposableEffect(wakeWordEnabled, liveSession, language) {
        if (!wakeWordEnabled || liveSession != null || !SpeechRecognizer.isRecognitionAvailable(context)) {
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
                if (wakeWordEnabled && liveSession == null) {
                    handler.postDelayed({ runCatching { recognizer.startListening(listenIntent) } }, 650)
                }
            }
            fun checkWakePhrase(bundle: Bundle?): Boolean {
                val phrases = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val heard = phrases.joinToString(" ").lowercase(Locale.ROOT)
                    .replace("-", " ").replace(",", " ")
                if (heard.contains("hey ayra") || heard.contains("hey ira") || heard.contains("hey aira")) {
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
                    title = { Column { Text("AYRA", color = Color(0xFF65E7F2)); Text("Your AI companion", style = MaterialTheme.typography.labelSmall) } },
                    actions = {
                        TextButton(onClick = { showDeviceTools = true }) { Text("Device", color = Color(0xFF65E7F2)) }
                        TextButton(onClick = {
                            if (liveSession != null) {
                                liveSession?.stop()
                                liveSession = null
                                avatarTalking = false
                            } else startLive()
                        }) {
                            Icon(Icons.Default.HeadsetMic, "Toggle Live Voice", tint = if (liveSession != null) Color(0xFF65E7F2) else Color.LightGray)
                            Text("LIVE CHAT", color = if (liveSession != null) Color(0xFF65E7F2) else Color.LightGray, style = MaterialTheme.typography.labelSmall)
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
                        placeholder = { Text("Message AYRA…") }, shape = RoundedCornerShape(24.dp),
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
                if (liveSession != null || liveStatus != "Live voice is off") {
                    Text(liveStatus, Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), color = Color(0xFF65E7F2), style = MaterialTheme.typography.labelMedium)
                }
                AndroidView(
                    factory = { viewContext ->
                        val assetLoader = WebViewAssetLoader.Builder()
                            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(viewContext))
                            .build()
                        WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = object : WebViewClientCompat() {
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    return assetLoader.shouldInterceptRequest(request.url)
                                }
                            }
                            loadUrl("https://appassets.androidplatform.net/assets/avatar.html")
                        }
                    },
                    update = { webView ->
                        webView.evaluateJavascript("window.setTalking(${avatarTalking})", null)
                    },
                    modifier = Modifier.fillMaxWidth().height(245.dp)
                )
                if (history.isEmpty()) {
                    Spacer(Modifier.height(56.dp))
                    Text("Hello, I'm AYRA", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF65E7F2))
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
                    if (busy) item { Text("AYRA is thinking…", color = Color(0xFF65E7F2)) }
                }
            }
        }
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("AYRA Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Gemini API key")
                        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, visualTransformation = PasswordVisualTransformation(), singleLine = true, placeholder = { Text("Paste API key") })
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
                                Text("Hey AYRA wake word")
                                Text("Listens only while this app is open; say “Hey AYRA”.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                            }
                        }
                        Text("Your key and chat history are stored on this device.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = {
                    prefs.edit().putString("gemini_key", apiKey).putString("language", language).putBoolean("speak", speakReplies).putBoolean("google_search", useGoogleSearch).putBoolean("wake_word", wakeWordEnabled).apply()
                    tts?.language = when (language) {
                        "Urdu" -> Locale("ur", "PK")
                        "Hindi" -> Locale("hi", "IN")
                        else -> Locale("en", "US")
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
                        Text("These shortcuts open Android's own controls. Protected actions still require your confirmation.")
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

