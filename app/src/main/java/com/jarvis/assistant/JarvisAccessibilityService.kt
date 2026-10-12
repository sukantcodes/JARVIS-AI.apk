package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.Locale

/**
 * Optional user-enabled accessibility bridge.
 * It performs explicit, user-requested navigation/actions; it does not read passwords,
 * bypass the lock screen, or automate security credential entry.
 */
class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: JarvisAccessibilityService? = null

        fun runAction(action: String): Boolean = instance?.performJarvisAction(action) ?: false
        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Do not collect or log screen text. We only act when a command is explicitly requested.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun performJarvisAction(raw: String): Boolean {
        val action = raw.trim()
        val lower = action.lowercase(Locale.ROOT)
        return when (lower) {
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "recents", "recent apps" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications", "open notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick settings", "open quick settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "lock screen", "lock device" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
            }
            "scroll down" -> scrollFirstScrollable(rootInActiveWindow, forward = true)
            "scroll up" -> scrollFirstScrollable(rootInActiveWindow, forward = false)
            "send message" -> clickByText("send") || clickByDescription("send")
            else -> when {
                lower.startsWith("tap ") -> clickByText(action.substringAfter(" ").trim())
                lower.startsWith("click ") -> clickByText(action.substringAfter(" ").trim())
                lower.startsWith("type ") -> setTextInFocusedField(action.substringAfter(" ").trim())
                else -> false
            }
        }
    }

    private fun clickByText(query: String): Boolean {
        if (query.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(query)
        for (node in nodes) {
            var candidate: AccessibilityNodeInfo? = node
            while (candidate != null) {
                if (candidate.isClickable && candidate.isEnabled) {
                    if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
                candidate = candidate.parent
            }
        }
        return false
    }

    private fun clickByDescription(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickDescriptionRecursive(root, query.lowercase(Locale.ROOT))
    }

    private fun clickDescriptionRecursive(node: AccessibilityNodeInfo, query: String): Boolean {
        val description = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
        if (description.contains(query) && node.isClickable && node.isEnabled &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickDescriptionRecursive(child, query)) return true
        }
        return false
    }

    private fun setTextInFocusedField(text: String): Boolean {
        if (text.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable && focused.isEnabled) {
            return focused.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
            )
        }
        val editable = findFirstEditable(root) ?: return false
        return editable.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        )
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun scrollFirstScrollable(node: AccessibilityNodeInfo?, forward: Boolean): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.isEnabled) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.performAction(action)) return true
        }
        for (i in 0 until node.childCount) {
            if (scrollFirstScrollable(node.getChild(i), forward)) return true
        }
        return false
    }
}
