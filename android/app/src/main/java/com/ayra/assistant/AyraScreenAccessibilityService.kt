package com.ayra.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper

class AyraScreenAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var active: AyraScreenAccessibilityService? = null

        fun performVoiceCommand(command: String): String? =
            active?.performCommand(command)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val root = rootInActiveWindow ?: return
        val lines = ArrayList<String>()
        fun collect(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 18 || lines.size >= 180) return
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val value = if (text.isNotBlank()) text else description
            if (value.isNotBlank() && lines.lastOrNull() != value) lines.add(value.take(500))
            for (i in 0 until node.childCount) collect(node.getChild(i), depth + 1)
        }
        try {
            collect(root, 0)
            val snapshot = lines.joinToString("\n").take(12000)
            if (snapshot.isNotBlank()) {
                getSharedPreferences("ayra", MODE_PRIVATE).edit()
                    .putString("screen_context", snapshot)
                    .putLong("screen_context_time", System.currentTimeMillis())
                    .apply()
            }
        } finally {
            root.recycle()
        }
    }

    private fun performCommand(raw: String): String? {
        val command = raw.trim()
        val lower = command.lowercase()
        val protectedActions = listOf(
            "send", "publish", "post", "delete", "remove", "pay", "purchase", "buy",
            "send message", "send whatsapp", "send sms", "publish post", "like", "share"
        )
        if (protectedActions.any { lower.startsWith(it) } ||
            listOf("بھیج دو", "پوسٹ کر دو", "ڈیلیٹ", "خرید لو", "پیسے ادا").any { lower.contains(it) }) {
            return when {
                lower.contains("post") || lower.contains("publish") || lower.contains("پوسٹ") ->
                    "I can prepare the post, but you must review and tap Publish yourself."
                else -> "I can prepare the message or form, but you must review and confirm the final action yourself."
            }
        }

        val root = rootInActiveWindow ?: return null
        try {
            if (lower in listOf("go back", "back", "واپس", "پیچھے", "वापस")) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return "Going back."
            }
            if (lower in listOf("scroll down", "نیچے کرو", "نیچے اسکرول", "नीचे स्क्रॉल")) {
                root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                return "Scrolling down."
            }
            if (lower in listOf("scroll up", "اوپر کرو", "اوپر اسکرول", "ऊपर स्क्रॉल")) {
                root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                return "Scrolling up."
            }

            val clickPrefixes = listOf("tap ", "click ", "press ", "کھولو بٹن ", "دباؤ ", "کلک کرو ")
            val clickTarget = clickPrefixes.firstNotNullOfOrNull { p ->
                if (lower.startsWith(p)) command.substring(p.length).trim() else null
            }
            if (!clickTarget.isNullOrBlank()) {
                val node = findNode(root, clickTarget) ?: return "I can't find the '$clickTarget' control on this screen."
                val clickable = clickableParent(node)
                val success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                if (clickable !== node) clickable.recycle()
                return if (success) "Tapped $clickTarget." else "That control isn't clickable right now."
            }

            val typePrefixes = listOf("type ", "write ", "enter ", "لکھو ", "ٹائپ کرو ", "لکھ دیں ")
            val textToType = typePrefixes.firstNotNullOfOrNull { p ->
                if (lower.startsWith(p)) command.substring(p.length) else null
            }
            if (!textToType.isNullOrBlank()) {
                val field = findEditable(root) ?: return "I can't find a text field on this screen."
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
                }
                val success = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                field.recycle()
                return if (success) "Text entered. Please review it before sending or publishing." else "I couldn't enter text in that field."
            }
            return null
        } finally {
            root.recycle()
        }
    }

    private fun findNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val found = root.findAccessibilityNodeInfosByText(target)
        return found.firstOrNull { it.isVisibleToUser } ?: found.firstOrNull()
    }

    private fun clickableParent(original: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = AccessibilityNodeInfo.obtain(original)
        repeat(8) {
            if (current.isClickable) return current
            val parent = current.parent ?: return current
            current.recycle()
            current = parent
        }
        return current
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 18) return null
            if (node.isEditable && node.isVisibleToUser) return AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val result = walk(node.getChild(i), depth + 1)
                if (result != null) return result
            }
            return null
        }
        return walk(root, 0)
    }

    override fun onInterrupt() = Unit
}
