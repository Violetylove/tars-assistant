package org.atovio.tars

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class TarsAccessibilityService : AccessibilityService() {
    private var floatingVoiceOverlay: FloatingVoiceOverlay? = null
    @Volatile private var foregroundPackage: String? = null
    @Volatile private var foregroundActivity: String? = null
    @Volatile private var observationVersion: Long = 0L
    override fun onServiceConnected() {
        instance = this
        sendBroadcast(android.content.Intent(ACTION_CONNECTED).setPackage(packageName))
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Preserve the latest foreground context for the next Agent request; UI remains pulled on demand.
        // Ignore input-method events: the soft keyboard emits its own window events and would
        // otherwise overwrite the real foreground app, breaking the "XML matches foreground" check.
        if (event != null) {
            val isImeEvent = try {
                windows?.firstOrNull { it.id == event.windowId }?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } catch (_: Throwable) { false }
            event.packageName?.toString()?.takeIf { it.isNotBlank() && !isImeEvent }?.let { foregroundPackage = it }
            event.className?.toString()?.takeIf { it.isNotBlank() }?.let { foregroundActivity = it }
        }
        if (event != null) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SELECTED -> observationVersion++
            }
        }
    }
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        floatingVoiceOverlay?.hide()
        floatingVoiceOverlay = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun currentUiXml(): String {
        val roots = collectVisibleWindowRoots()
        if (roots.isEmpty()) return ""
        val dm = resources.displayMetrics
        val facts = windowFacts()
        return UiTreeXml.serializeWindows(roots, facts, dm.widthPixels, dm.heightPixels)
    }

    /** All visible accessibility windows as { type, layer(z-order), bounds } facts.
     *  This includes non-application windows (soft keyboard / system / overlays) that are NOT
     *  part of the UI node tree. Exposing them as facts (not as an occlusion judgment) lets the
     *  Agent summarizer hand the model the full z-axis picture so the model can reason which
     *  node is covered by a higher-z window.
     */
    private fun windowFacts(): List<WindowFact> {
        val facts = mutableListOf<WindowFact>()
        try {
            windows?.forEach { w ->
                val label = when (w.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
                    AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
                    AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
                    AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "magnification_overlay"
                    else -> "type_${w.type}"
                }
                val r = android.graphics.Rect().also { w.getBoundsInScreen(it) }
                facts += WindowFact(label, w.layer, listOf(r.left, r.top, r.right, r.bottom))
            }
        } catch (_: Throwable) { }
        return facts
    }

    /**
     * Collect root nodes of the visible application windows, in z-order (top first),
     * excluding irrelevant system/input/overlay windows.
     *
     * This is the multi-layer counterpart of `rootInActiveWindow` so that transient
     * overlay windows (e.g. Gmail's peoplekit suggestion card) are also captured.
     * Only TYPE_APPLICATION / TYPE_ACCESSIBILITY_OVERLAY (ours is filtered below) /
     * app-owned overlay windows are considered; system and input-method windows are skipped.
     */
    fun collectVisibleWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val windows = try { windows } catch (_: Throwable) { emptyList<AccessibilityWindowInfo>() }
        // windows is already ordered by z-order (top-most first) on API 21+.
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            w.root?.let { roots += it }
        }
        // Fallback: some windows (esp. full-screen transparent app windows) can report a
        // null root via getWindows(); make sure we never hand an empty tree downstream.
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { roots += it }
        }
        return roots
    }

    /** Nodes that are visually reachable: not fully covered by an upper-layer node. */
    fun collectVisibleNodes(): List<Pair<AccessibilityNodeInfo, Int>> {
        val covered = mutableListOf<android.graphics.Rect>()
        val visible = mutableListOf<Pair<AccessibilityNodeInfo, Int>>() // (node, layerIndex)
        val roots = collectVisibleWindowRoots()
        val allWindows = try { windows } catch (_: Throwable) { emptyList<AccessibilityWindowInfo>() }
        Log.i(TAG_A11Y, String.format("collectVisible roots=%d totalWindows=%d types=%s",
            roots.size, allWindows.size, allWindows.map { it.type }.toString()))
        var totalNodes = 0
        var culled = 0
        // Occlusion only applies across layers: nodes of the same layer share one visual
        // plane (transparent containers must not occlude their siblings/children).
        val layerNodes = roots.withIndex().map { (layer, root) ->
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(root, nodes)
            totalNodes += nodes.size
            layer to nodes
        }
        // Process layers top-down; only layer 0 may occlude lower layers.
        for ((layer, nodes) in layerNodes) {
            for (n in nodes) {
                val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                // Keep zero-size interactive nodes in the ID stream. Some apps (notably
                // Gmail's compose FAB shortcut) expose a clickable semantic node with
                // bounds=[0,0][0,0]. The Python summarizer intentionally keeps these
                // nodes too, so dropping them here would shift every subsequent ID.
                val zeroSize = b.isEmpty
                // Mirror agent.ui_summarizer._is_interactive: only interactive nodes
                // participate in ID numbering, otherwise model IDs drift from execution IDs.
                if (b.left < 0 || b.top < 0) continue
                val className = n.className?.toString().orEmpty()
                val importantClass = className.contains("Button", ignoreCase = true) ||
                    className.contains("EditText", ignoreCase = true) ||
                    className.contains("CheckBox", ignoreCase = true) ||
                    className.contains("RadioButton", ignoreCase = true) ||
                    className.contains("Switch", ignoreCase = true) ||
                    className.contains("ImageButton", ignoreCase = true)
                if (!(n.isClickable || n.isFocusable || (importantClass && n.isEnabled))) continue
                // Skip full-screen container nodes from occluding anything (transparent root).
                if (b.width() >= resources.displayMetrics.widthPixels - 1 &&
                    b.height() >= resources.displayMetrics.heightPixels - 1) continue
                if (!zeroSize && layer > 0 && covered.any { it.contains(b) }) { culled++; continue }
                visible += n to layer
                if (layer == 0 && !zeroSize) covered += b
            }
        }
        Log.i(TAG_A11Y, String.format("collectVisible totalNodes=%d culled=%d visible=%d",
            totalNodes, culled, visible.size))
        return visible
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        out += node
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectAllNodes(it, out) }
    }

    /** The event stream updates before rootInActiveWindow during app transitions.
     *
     * Resolve the foreground app from the focused application window first so the
     * soft keyboard (an input-method window) is never reported as the foreground app.
     */
    fun currentAppPackage(): String? {
        val focusedApp = try {
            windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
                ?.root?.packageName?.toString()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }
        return focusedApp ?: foregroundPackage
            ?: rootInActiveWindow?.packageName?.toString()?.takeIf { it.isNotBlank() }
    }

    fun currentActivity(): String? = foregroundActivity

    fun currentObservationVersion(): Long = observationVersion

    /** Poll the root window until it differs from the pre-action UI snapshot.
     *
     * Primary signal: the foreground package changed (handles cross-app window
     * switches where rootInActiveWindow's node tree lags behind). Fallback: the
     * serialised XML differs. Timeout stays fail-closed.
     */
    fun awaitFreshUiAfter(
        previousUiXml: String,
        previousPackage: String?,
        timeoutMs: Long,
        previousObservationVersion: Long = observationVersion,
    ): Boolean {
        val start = android.os.SystemClock.elapsedRealtime()
        val baseDeadline = start + timeoutMs
        var deadline = baseDeadline
        Log.i(TAG_A11Y, String.format("awaitFresh prev_len=%d prev_pkg=%s prev_version=%d", previousUiXml.length, previousPackage, previousObservationVersion))
        // A single fresh signal can be a mid-transition snapshot: the previous app is still
        // serialised while the next app animates in, so the next round would observe a stale
        // tree and re-act on the old screen (e.g. clicking the launcher icon a second time).
        // Require the SAME fresh XML twice in a row so the next round observes a settled window.
        var stableCandidate: String? = null
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val currentUiXml = currentUiXml()
            val curPkg = currentAppPackage()
            val pkgChanged = curPkg != null && curPkg != previousPackage
            val xmlChanged = currentUiXml.isNotBlank() && currentUiXml != previousUiXml
            val eventChanged = observationVersion != previousObservationVersion
            val xmlMatchesForeground = curPkg != null && currentUiXml.contains("package=\"$curPkg\"")
            val fresh = xmlMatchesForeground && (pkgChanged || xmlChanged)
            val stable = fresh && stableCandidate == currentUiXml
            Log.i(TAG_A11Y, String.format(
                "awaitFresh poll pkg=%s len=%d blank=%b pkgChanged=%b xmlChanged=%b eventChanged=%b xmlMatchesForeground=%b fresh=%b stable=%b",
                curPkg, currentUiXml.length, currentUiXml.isBlank(), pkgChanged, xmlChanged,
                eventChanged, xmlMatchesForeground, fresh, stable,
            ))
            if (stable) return true
            stableCandidate = if (fresh) currentUiXml else null
            // A newly-launched app (cold start) can render its accessibility tree slowly, staying
            // blank for a couple of seconds. Give a package change a bounded grace so we don't
            // time out and drop the capture before the tree settles.
            val newAppGraceMs = RuntimeSettings.read(this).newAppGraceMs
            if (pkgChanged && currentUiXml.isBlank() && android.os.SystemClock.elapsedRealtime() < baseDeadline + newAppGraceMs) {
                deadline = baseDeadline + newAppGraceMs
            }
            if (eventChanged) {
                // Events frequently arrive before getWindows/rootInActiveWindow catches up. They
                // trigger another sample but never prove that its XML is fresh on their own.
                Log.i(TAG_A11Y, "awaitFresh event observed; waiting for a stable foreground XML")
            }
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
        const val ACTION_CONNECTED = "org.atovio.tars.ACCESSIBILITY_CONNECTED"
        @Volatile var instance: TarsAccessibilityService? = null
        private const val OBSERVATION_POLL_MS = 100L
        private const val TAG_A11Y = "TarsA11y"
    }
}

private data class WindowFact(val typeLabel: String, val layer: Int, val bounds: List<Int>)

private object UiTreeXml {
    fun serialize(root: AccessibilityNodeInfo): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?><hierarchy>")
        appendNode(root)
        append("</hierarchy>")
    }

    /** Serialize multiple window roots (z-order, top first) into one hierarchy.
     *
     * Each window is wrapped in a <window layer="N"> group so that downstream
     * summarizer/parser can see which nodes belong to which layer.
     */
    fun serializeWindows(roots: List<AccessibilityNodeInfo>, windowFacts: List<WindowFact>, screenW: Int, screenH: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?><hierarchy screen_w=\"$screenW\" screen_h=\"$screenH\">")
        for (f in windowFacts) {
            append("<window-info type=\"${f.typeLabel}\" layer=\"${f.layer}\" bounds=\"[${f.bounds[0]},${f.bounds[1]}][${f.bounds[2]},${f.bounds[3]}]\"/>")
        }
        for ((layer, root) in roots.withIndex()) {
            append("<window layer=\"$layer\">")
            appendNode(root)
            append("</window>")
        }
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
