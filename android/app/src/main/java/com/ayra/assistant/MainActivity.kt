package com.ayra.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
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
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }
        tts = engine
        onDispose { engine.stop(); engine.shutdown() }
    }

    fun persist() {
        val arr = JSONArray()
        history.forEach { arr.put(JSONObject().put("text", it.text).put("user", it.fromUser)) }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    fun sendMessage(message: String) {
        if (message.isBlank() || busy) return
        history.add(ChatLine(message, true))
        persist()
        input = ""
        if (apiKey.isBlank()) {
            history.add(ChatLine("Please add your Gemini API key in Settings to start chatting.", false))
            persist()
            return
        }
        busy = true
        val prompt = buildString {
            append("You are AYRA, a helpful friendly AI assistant. Reply in $language unless the user requests another language.\n")
            history.takeLast(12).forEach { append(if (it.fromUser) "User: " else "AYRA: "); append(it.text); append("\n") }
        }
        thread {
            val answer = runCatching {
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .toString().toRequestBody("application/json".toMediaTypeCompat())
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8")
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

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF65E7F2), secondary = Color(0xFFB68CFF),
        background = Color(0xFF090D18), surface = Color(0xFF151C2B)
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column { Text("AYRA", color = Color(0xFF65E7F2)); Text("Your AI companion", style = MaterialTheme.typography.labelSmall) } },
                    actions = { IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") } },
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
                        Text("Your key and chat history are stored on this device.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = {
                    prefs.edit().putString("gemini_key", apiKey).putString("language", language).putBoolean("speak", speakReplies).apply()
                    showSettings = false
                }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } }
            )
        }
    }
}

private fun String.toMediaTypeCompat() = okhttp3.MediaType.Companion.toMediaType(this)
