package org.atovio.tars

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class TarsAccessibilityService : AccessibilityService() {
    private var floatingVoiceOverlay: FloatingVoiceOverlay? = null
    private var actionConfirmationOverlay: ActionConfirmationOverlay? = null
    @Volatile private var foregroundPackage: String? = null
    @Volatile private var foregroundActivity: String? = null
    @Volatile private var observationVersion: Long = 0L
    @Volatile private var latestEventTrace: String = "none"
    private val eventSourceLock = Any()
    private var latestEventSource: AccessibilityNodeInfo? = null
    override fun onServiceConnected() {
        instance = this
        actionConfirmationOverlay = ActionConfirmationOverlay(this)
        sendBroadcast(android.content.Intent(ACTION_CONNECTED).setPackage(packageName))
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Preserve the latest foreground context for the next Agent request; UI remains pulled on demand.
        // Ignore input-method events: the soft keyboard emits its own window events and would
        // otherwise overwrite the real foreground app, breaking the "XML matches foreground" check.
        val isImeEvent = if (event != null) try {
                windows?.firstOrNull { it.id == event.windowId }?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } catch (_: Throwable) { false } else false
        if (event != null) {
            event.packageName?.toString()?.takeIf { it.isNotBlank() && !isImeEvent }?.let { foregroundPackage = it }
            event.className?.toString()?.takeIf { it.isNotBlank() }?.let { foregroundActivity = it }
        }
        if (event != null) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                    observationVersion++
                    latestEventTrace = eventTrace(event)
                    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    ) {
                        cacheEventSource(event, isImeEvent)
                    }
                }
            }
        }
    }
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        floatingVoiceOverlay?.hide()
        floatingVoiceOverlay = null
        actionConfirmationOverlay?.dismiss()
        actionConfirmationOverlay = null
        synchronized(eventSourceLock) {
            latestEventSource?.recycle()
            latestEventSource = null
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun currentUiXml(): String = captureUiSnapshot()?.xml.orEmpty()

    /**
     * Serializes the raw application roots for local diagnosis only. A transition placeholder
     * is useful evidence in Android logs, but is never a valid Agent request payload.
     */
    fun currentDiagnosticUiXml(): String {
        val eventFallback = eventSourceFallback()
        val roots = collectApplicationWindowRoots() + listOfNotNull(eventFallback)
        if (roots.isEmpty()) {
            eventFallback?.recycle()
            return ""
        }
        return try {
            serializeUiRoots(roots)
        } finally {
            eventFallback?.recycle()
        }
    }

    private fun serializeUiRoots(roots: List<AccessibilityNodeInfo>): String {
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
                val label = windowTypeLabel(w.type)
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
    private fun collectApplicationWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val windows = try { windows } catch (_: Throwable) { emptyList<AccessibilityWindowInfo>() }
        // windows is already ordered by z-order (top-most first) on API 21+.
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            w.root?.let { roots += it }
        }
        return roots
    }

    fun collectVisibleWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = collectApplicationWindowRoots()
        // During app transitions getWindows() may expose a non-null placeholder root with
        // no children (often bounds=[0,0][0,0], enabled=false). Do not let that stale root
        // suppress the focused root fallback, which can already contain the real app tree.
        val populated = roots.filter(::hasAccessibleContent)
        if (populated.isNotEmpty()) return populated
        rootInActiveWindow?.let { fallback ->
            if (hasAccessibleContent(fallback)) return listOf(fallback)
        }
        eventSourceFallback()?.let { fallback ->
            if (hasAccessibleContent(fallback) && matchesForeground(fallback)) return listOf(fallback)
            fallback.recycle()
        }
        return emptyList()
    }

    /** True when a root contains a usable accessibility subtree rather than a placeholder. */
    private fun hasAccessibleContent(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        for (index in 0 until node.childCount) {
            if (hasAccessibleContent(node.getChild(index))) return true
        }
        return node.isEnabled && (
            node.isClickable || node.isFocusable ||
                !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            )
    }

    /** Nodes that are visually reachable: not fully covered by an upper-layer node. */
    fun collectVisibleNodes(): List<Pair<AccessibilityNodeInfo, Int>> =
        collectVisibleNodes(collectVisibleWindowRoots())

    private fun collectVisibleNodes(roots: List<AccessibilityNodeInfo>): List<Pair<AccessibilityNodeInfo, Int>> {
        val covered = mutableListOf<android.graphics.Rect>()
        val visible = mutableListOf<Pair<AccessibilityNodeInfo, Int>>() // (node, layerIndex)
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

    /** One coherent XML/node view used by both the model request and action execution. */
    fun captureUiSnapshot(): UiSnapshot? {
        val roots = collectVisibleWindowRoots()
        if (roots.isEmpty()) return null
        return UiSnapshot(
            xml = serializeUiRoots(roots),
            visibleNodes = collectVisibleNodes(roots).map { it.first },
            packageName = currentAppPackage(),
            activity = currentActivity(),
        )
    }

    /**
     * Structural capture evidence for the Android diagnostic log. It intentionally excludes
     * node text: the paired raw XML capture remains the content-level diagnostic record.
     */
    fun captureSourceState(): String {
        val windows = try { windows.orEmpty() } catch (_: Throwable) { emptyList() }
        val windowState = windows.joinToString(prefix = "[", postfix = "]") { window ->
            "{id=${window.id},type=${windowTypeLabel(window.type)},layer=${window.layer}," +
                "focused=${window.isFocused},root=${nodeTrace(window.root)}}"
        }
        return "windows=$windowState active_root=${nodeTrace(rootInActiveWindow)} latest_event=$latestEventTrace"
    }

    /** Wait for a populated foreground tree before allowing another Agent request. */
    fun awaitStableUi(timeoutMs: Long, onCaptureStateChanged: (String) -> Unit = {}): UiSnapshot? {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var stableCandidate: String? = null
        var lastCaptureState = ""
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val captureState = captureSourceState()
            if (captureState != lastCaptureState) {
                onCaptureStateChanged(captureState)
                lastCaptureState = captureState
            }
            val snapshot = captureUiSnapshot()
            val uiXml = snapshot?.xml.orEmpty()
            val packageName = snapshot?.packageName
            val usable = uiXml.isNotBlank() && packageName != null &&
                uiXml.contains("package=\"$packageName\"")
            val stable = usable && stableCandidate == uiXml
            Log.i(TAG_A11Y, String.format(
                "awaitStableUi pkg=%s len=%d usable=%b stable=%b",
                packageName, uiXml.length, usable, stable,
            ))
            if (stable) return snapshot
            stableCandidate = if (usable) uiXml else null
            if (!sleepForObservation()) return null
        }
        return null
    }

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
            val snapshot = captureUiSnapshot()
            val currentUiXml = snapshot?.xml.orEmpty()
            val curPkg = snapshot?.packageName
            val pkgChanged = curPkg != null && curPkg != previousPackage
            val xmlChanged = currentUiXml.isNotBlank() && currentUiXml != previousUiXml
            val eventChanged = observationVersion != previousObservationVersion
            val treePopulated = currentUiXml.isNotBlank()
            val xmlMatchesForeground = curPkg != null && currentUiXml.contains("package=\"$curPkg\"")
            val fresh = treePopulated && xmlMatchesForeground && (pkgChanged || xmlChanged)
            val stable = fresh && stableCandidate == currentUiXml
            Log.i(TAG_A11Y, String.format(
                "awaitFresh poll pkg=%s len=%d blank=%b populated=%b pkgChanged=%b xmlChanged=%b eventChanged=%b xmlMatchesForeground=%b fresh=%b stable=%b",
                curPkg, currentUiXml.length, currentUiXml.isBlank(), treePopulated, pkgChanged,
                xmlChanged, eventChanged, xmlMatchesForeground, fresh, stable,
            ))
            if (stable) return true
            stableCandidate = if (fresh) currentUiXml else null
            // A newly-launched app (cold start) can render its accessibility tree slowly, staying
            // blank for a couple of seconds. Give a package change a bounded grace so we don't
            // time out and drop the capture before the tree settles.
            val newAppGraceMs = RuntimeSettings.read(this).newAppGraceMs
            if (!treePopulated && android.os.SystemClock.elapsedRealtime() < baseDeadline + newAppGraceMs) {
                deadline = baseDeadline + newAppGraceMs
            }
            if (eventChanged) {
                // Events frequently arrive before getWindows/rootInActiveWindow catches up. They
                // trigger another sample but never prove that its XML is fresh on their own.
                Log.i(TAG_A11Y, "awaitFresh event observed; waiting for a stable foreground XML")
            }
            if (!sleepForObservation()) return false
        }
        return false
    }

    private fun sleepForObservation(): Boolean = try {
        Thread.sleep(OBSERVATION_POLL_MS)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun cacheEventSource(event: AccessibilityEvent, isImeEvent: Boolean) {
        if (isImeEvent) return
        val source = event.source ?: return
        val copy = try { AccessibilityNodeInfo.obtain(source) } catch (_: Throwable) { return }
        if (!hasAccessibleContent(copy)) {
            copy.recycle()
            return
        }
        synchronized(eventSourceLock) {
            latestEventSource?.recycle()
            latestEventSource = copy
        }
    }

    private fun eventSourceFallback(): AccessibilityNodeInfo? = synchronized(eventSourceLock) {
        latestEventSource?.let { AccessibilityNodeInfo.obtain(it) }
    }

    private fun matchesForeground(node: AccessibilityNodeInfo): Boolean {
        val expected = foregroundPackage ?: return true
        return node.packageName?.toString() == expected
    }

    private fun eventTrace(event: AccessibilityEvent): String = try {
        "type=${eventTypeLabel(event.eventType)},window=${event.windowId},pkg=${event.packageName?.toString().orEmpty()}," +
            "class=${event.className?.toString().orEmpty()},source=${nodeTrace(event.source)}"
    } catch (_: Throwable) {
        "type=${eventTypeLabel(event.eventType)},window=${event.windowId},source=unavailable"
    }

    private fun nodeTrace(node: AccessibilityNodeInfo?): String {
        if (node == null) return "none"
        return try {
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            "pkg=${node.packageName?.toString().orEmpty()},class=${node.className?.toString().orEmpty()},enabled=${node.isEnabled}," +
                "children=${node.childCount},bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
        } catch (_: Throwable) {
            "unavailable"
        }
    }

    private fun windowTypeLabel(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "magnification_overlay"
        else -> "type_$type"
    }

    private fun eventTypeLabel(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_state_changed"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "window_content_changed"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "view_focused"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "view_text_changed"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "view_selected"
        else -> "type_$type"
    }

    fun execute(
        actions: List<AgentAction>,
        confirm: (AgentAction) -> Boolean = { false },
        snapshot: UiSnapshot? = null,
        sessionId: String = "-",
    ): ActionExecutor.ExecutionSummary =
        ActionExecutor(this, confirm, snapshotNodes = snapshot?.visibleNodes, sessionId = sessionId).execute(actions)

    fun confirmAction(action: AgentAction, snapshot: UiSnapshot? = null): Boolean =
        actionConfirmationOverlay?.confirm(actionConfirmationDescription(action, snapshot?.visibleNodes)) == true

    private fun actionConfirmationDescription(action: AgentAction, visibleNodes: List<AccessibilityNodeInfo>?): String {
        val nodes = visibleNodes ?: collectVisibleNodes().map { it.first }
        val node = action.targetNodeId?.let { id -> nodes.take(60).getOrNull(id) }
        val nodeType = node?.let(::confirmationNodeType) ?: "控件"
        val nodeText = node?.let(::confirmationNodeText)?.ifBlank { "无文本" } ?: "无文本"
        return when (action.type) {
            "click" -> "TARS即将操作：点击${nodeType}[$nodeText]。"
            "type" -> "TARS即将操作：向${nodeType}[$nodeText]输入[${action.text.orEmpty()}]。"
            "swipe" -> "TARS即将操作：滑动当前界面。"
            "launch" -> "TARS即将操作：启动${action.packageName.orEmpty()}。"
            "back" -> "TARS即将操作：返回上一页。"
            "home" -> "TARS即将操作：返回桌面。"
            else -> "TARS即将操作：${action.type}当前界面。"
        }
    }

    private fun confirmationNodeType(node: AccessibilityNodeInfo): String {
        val className = node.className?.toString().orEmpty().lowercase()
        return when {
            className.contains("edittext") -> "输入框"
            className.contains("imagebutton") -> "图片按钮"
            className.contains("button") || node.isClickable -> "按钮"
            className.contains("textview") -> "文本"
            else -> "控件"
        }
    }

    private fun confirmationNodeText(node: AccessibilityNodeInfo): String {
        val labels = linkedSetOf<String>()
        fun visit(current: AccessibilityNodeInfo?) {
            if (current == null || labels.size >= 3) return
            listOf(current.text?.toString(), current.contentDescription?.toString())
                .map { it.orEmpty().replace("\n", " ").trim() }
                .filter { it.isNotBlank() }
                .forEach { labels += it }
            for (index in 0 until current.childCount) visit(current.getChild(index))
        }
        visit(node)
        return labels.joinToString(" / ").take(80)
    }

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

data class UiSnapshot(
    val xml: String,
    val visibleNodes: List<AccessibilityNodeInfo>,
    val packageName: String?,
    val activity: String?,
)

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
