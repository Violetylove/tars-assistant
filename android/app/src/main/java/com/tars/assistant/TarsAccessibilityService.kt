package com.tars.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TarsAccessibilityService : AccessibilityService() {
    private var floatingVoiceOverlay: FloatingVoiceOverlay? = null
    @Volatile private var foregroundPackage: String? = null
    @Volatile private var foregroundActivity: String? = null
    override fun onServiceConnected() {
        instance = this
        sendBroadcast(android.content.Intent(ACTION_CONNECTED).setPackage(packageName))
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Preserve the latest foreground context for the next Agent request; UI remains pulled on demand.
        event?.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { foregroundPackage = it }
        event?.className?.toString()?.takeIf { it.isNotBlank() }?.let { foregroundActivity = it }
    }
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        floatingVoiceOverlay?.hide()
        floatingVoiceOverlay = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun currentUiXml(): String = rootInActiveWindow?.let { UiTreeXml.serialize(it) } ?: ""

    fun currentAppPackage(): String? = rootInActiveWindow?.packageName?.toString()
        ?.takeIf { it.isNotBlank() } ?: foregroundPackage

    fun currentActivity(): String? = foregroundActivity

    /** Poll the root window until it differs from the pre-action UI snapshot. */
    fun awaitFreshUiAfter(previousUiXml: String, timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val currentUiXml = currentUiXml()
            if (currentUiXml.isNotBlank() && currentUiXml != previousUiXml) return true
            try {
                Thread.sleep(OBSERVATION_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    fun execute(actions: List<AgentAction>, confirm: (AgentAction) -> Boolean = { false }): ActionExecutor.ExecutionSummary =
        ActionExecutor(this, confirm).execute(actions)

    fun toggleFloatingVoice(): Boolean {
        floatingVoiceOverlay?.let { it.hide(); floatingVoiceOverlay = null; return false }
        return try {
            val overlay = FloatingVoiceOverlay(this)
            if (overlay.show()) { floatingVoiceOverlay = overlay; true } else false
        } catch (_: RuntimeException) {
            false
        }
    }

    val isFloatingVoiceVisible: Boolean get() = floatingVoiceOverlay?.isVisible == true

    companion object {
        const val ACTION_CONNECTED = "com.tars.assistant.ACCESSIBILITY_CONNECTED"
        @Volatile var instance: TarsAccessibilityService? = null
        private const val OBSERVATION_POLL_MS = 100L
    }
}

private object UiTreeXml {
    fun serialize(root: AccessibilityNodeInfo): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?><hierarchy>")
        appendNode(root)
        append("</hierarchy>")
    }

    private fun StringBuilder.appendNode(node: AccessibilityNodeInfo) {
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        append("<node")
        attribute("text", node.text?.toString().orEmpty())
        attribute("content-desc", node.contentDescription?.toString().orEmpty())
        attribute("class", node.className?.toString().orEmpty())
        attribute("resource-id", node.viewIdResourceName.orEmpty())
        attribute("package", node.packageName?.toString().orEmpty())
        attribute("clickable", node.isClickable.toString())
        attribute("focusable", node.isFocusable.toString())
        attribute("focused", node.isFocused.toString())
        attribute("enabled", node.isEnabled.toString())
        attribute("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
        if (node.childCount == 0) append("/>") else {
            append(">"); for (i in 0 until node.childCount) node.getChild(i)?.let { appendNode(it) }; append("</node>")
        }
    }

    private fun StringBuilder.attribute(name: String, value: String) {
        append(' ').append(name).append("=\"")
        append(value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;"))
        append('"')
    }
}
