package com.ayra.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AyraScreenAccessibilityService : AccessibilityService() {
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
    override fun onInterrupt() = Unit
}