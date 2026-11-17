package com.adam.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AdamScreenAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var active: AdamScreenAccessibilityService? = null

        fun performVoiceCommand(command: String): String =
            active?.performCommand(command)
                ?: "ADAM app control is not connected. Open Device tools → Enable ADAM app control permission, then enable ADAM in Android Accessibility settings."
    }

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
                getSharedPreferences("adam", MODE_PRIVATE).edit()
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
        val protectedActions = listOf("send", "publish", "post", "delete", "remove", "pay", "purchase", "buy", "like", "share")
        if (protectedActions.any { lower.startsWith(it) } ||
            listOf("بھیج دو", "پوسٹ کر دو", "ڈیلیٹ", "خرید لو", "پیسے ادا").any { lower.contains(it) }) {
            return if (lower.contains("post") || lower.contains("publish") || lower.contains("پوسٹ"))
                "I can prepare the post, but you must review and tap Publish yourself."
            else "I can prepare the message or form, but you must review and confirm the final action yourself."
        }

        val root = rootInActiveWindow
            ?: return "ADAM is connected, but Android did not expose this app's screen. Wait for it to finish loading and try again."
        try {
            if (lower in listOf("go back", "back", "واپس", "پیچھے", "वापस")) {
                val ok = performGlobalAction(GLOBAL_ACTION_BACK)
                return if (ok) "Going back." else "Android could not go back on this screen."
            }
            if (lower in listOf("scroll down", "نیچے کرو", "نیچے اسکرول", "नीचे स्क्रॉल")) {
                return if (scroll(root, true)) "Scrolling down." else "I couldn't find a scrollable area on this screen."
            }
            if (lower in listOf("scroll up", "اوپر کرو", "اوپر اسکرول", "ऊपर स्क्रॉल")) {
                return if (scroll(root, false)) "Scrolling up." else "I couldn't find a scrollable area on this screen."
            }

            val clickPrefixes = listOf("tap ", "click ", "press ", "کھولو بٹن ", "دباؤ ", "کلک کرو ")
            val clickTarget = clickPrefixes.firstNotNullOfOrNull { p ->
                if (lower.startsWith(p)) command.substring(p.length).trim() else null
            }
            if (!clickTarget.isNullOrBlank()) {
                val node = findNode(root, clickTarget)
                    ?: return "I can't find the '$clickTarget' control on this screen. Try using the exact visible label."
                val clickable = clickableParent(node)
                val success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                clickable.recycle()
                node.recycle()
                return if (success) "Tapped $clickTarget." else "I found '$clickTarget', but Android would not let ADAM tap it."
            }

            val typePrefixes = listOf("type ", "write ", "enter ", "لکھو ", "ٹائپ کرو ", "لکھ دیں ")
            val textToType = typePrefixes.firstNotNullOfOrNull { p ->
                if (lower.startsWith(p)) command.substring(p.length) else null
            }
            if (!textToType.isNullOrBlank()) {
                val field = findEditable(root) ?: return "I can't find an editable text field on this screen."
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
                }
                val success = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                field.recycle()
                return if (success) "Text entered. Please review it before sending or publishing." else "Android blocked text entry in that field."
            }
            return "I didn't recognize that app-control command. Try: tap [visible label], type [text], scroll up/down, or go back."
        } finally {
            root.recycle()
        }
    }

    private fun scroll(root: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        fun walk(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > 18) return false
            if (node.isVisibleToUser && node.isScrollable && node.performAction(action)) return true
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val done = try { walk(child, depth + 1) } finally { child.recycle() }
                if (done) return true
            }
            return false
        }
        return walk(root, 0)
    }

    private fun findNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val needle = target.trim().lowercase()
        fun walk(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 18) return null
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            if (node.isVisibleToUser &&
                (text.equals(target, true) || description.equals(target, true) ||
                 text.contains(needle, true) || description.contains(needle, true))) {
                return AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = try { walk(child, depth + 1) } finally { child.recycle() }
                if (found != null) return found
            }
            return null
        }
        return walk(root, 0)
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
                val child = node.getChild(i) ?: continue
                val result = try { walk(child, depth + 1) } finally { child.recycle() }
                if (result != null) return result
            }
            return null
        }
        return walk(root, 0)
    }

    override fun onInterrupt() = Unit
}
