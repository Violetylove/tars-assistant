package org.atovio.tars

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android 侧 UI 摘要器：无障碍树 → 紧凑交互节点列表。
 *
 * 采集即摘要：摘要节点与执行侧（ActionExecutor / 确认浮层）共用同一条路径，动作 ID 天然一致，
 * 不再需要跨端对齐。语义与原 agent/ui_summarizer.py 保持对齐（该模块保留仅作测试/旧客户端回退）：
 * - 树序（每窗口先序，窗口按 z 序）过滤可交互节点；
 * - 跨层遮挡剔除（仅顶层非全屏节点占据覆盖区域）；
 * - 复合控件的语义文本（自身 + 可见后代）、类型分类、深度与父容器标签。
 */
object UiSummarizer {
    const val MAX_NODES = 60
    const val MAX_TEXT_LEN = 40

    data class SummarizedNode(
        val id: Int,
        val node: AccessibilityNodeInfo,
        val type: String,
        val text: String,
        val bounds: List<Int>,
        val clickable: Boolean,
        val focusable: Boolean,
        val focused: Boolean,
        val layer: Int,
        val depth: Int,
        val container: String,
        val resourceId: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            // 内部 diff 键（资源 ID），仅用于 Agent 侧 UI 变化匹配，绝不写入 LLM 提示。
            put("_resource_id", resourceId)
            put("type", type)
            put("text", text)
            put("bounds", JSONArray(bounds))
            put("clickable", clickable)
            put("focusable", focusable)
            put("focused", focused)
            put("layer", layer)
            put("depth", depth)
            put("container", container)
        }
    }

    data class Summary(
        val nodes: List<SummarizedNode>,
        val windowLayers: String,
    ) {
        val visibleNodes: List<AccessibilityNodeInfo> get() = nodes.map { it.node }
    }

    private class Candidate(
        val node: AccessibilityNodeInfo,
        val layer: Int,
        val depth: Int,
        val container: String,
    )

    private val IMPORTANT_CLASS_TOKENS =
        listOf("button", "edittext", "checkbox", "radiobutton", "switch", "imagebutton")

    fun summarize(
        roots: List<AccessibilityNodeInfo>,
        windowFacts: List<WindowFact>,
        screenW: Int,
        screenH: Int,
    ): Summary {
        // 1. 树序（每窗口先序，窗口按 z 序）收集可交互节点。
        val candidates = mutableListOf<Candidate>()
        roots.forEachIndexed { layer, root -> walk(root, layer, 0, "", candidates) }
        // 2. 跨层遮挡剔除：仅 layer 0 非全屏节点占据覆盖区域；零尺寸节点保持原样。
        val covered = mutableListOf<Rect>()
        val kept = mutableListOf<Candidate>()
        for (candidate in candidates) {
            val bounds = Rect().also { candidate.node.getBoundsInScreen(it) }
            val zeroSize = bounds.isEmpty
            if (isFullscreen(bounds, screenW, screenH)) continue
            if (candidate.layer > 0 && !zeroSize && covered.any { it.contains(bounds) }) continue
            kept += candidate
            if (candidate.layer == 0 && !zeroSize) covered += bounds
        }
        // 3. 树序截断并重赋稳定 id（0..n-1），与执行侧 findNode 同序。
        val nodes = kept.take(MAX_NODES).mapIndexed { index, candidate ->
            SummarizedNode(
                id = index,
                node = candidate.node,
                type = classify(candidate.node),
                text = semanticText(candidate.node),
                bounds = boundsOf(candidate.node),
                clickable = candidate.node.isClickable,
                focusable = candidate.node.isFocusable,
                focused = candidate.node.isFocused,
                layer = candidate.layer,
                depth = candidate.depth,
                container = candidate.container,
                resourceId = candidate.node.viewIdResourceName.orEmpty(),
            )
        }
        return Summary(nodes, renderWindowLayers(windowFacts))
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        layer: Int,
        depth: Int,
        parentLabel: String,
        out: MutableList<Candidate>,
    ) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (isInteractive(node, bounds)) out += Candidate(node, layer, depth, parentLabel)
        val childLabel = shortLabel(node).ifEmpty { parentLabel }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { walk(it, layer, depth + 1, childLabel, out) }
        }
    }

    private fun isInteractive(node: AccessibilityNodeInfo, bounds: Rect): Boolean {
        if (bounds.left < 0 || bounds.top < 0) return false
        if (node.isClickable || node.isFocusable) return true
        val className = node.className?.toString().orEmpty()
        return importantClass(className) && node.isEnabled
    }

    private fun importantClass(className: String): Boolean =
        IMPORTANT_CLASS_TOKENS.any { className.contains(it, ignoreCase = true) }

    private fun isFullscreen(bounds: Rect, screenW: Int, screenH: Int): Boolean {
        val displayW = if (screenW > 0) screenW else 1080
        val displayH = if (screenH > 0) screenH else 2400
        return bounds.width() >= displayW - 1 && bounds.height() >= displayH - 1
    }

    private fun boundsOf(node: AccessibilityNodeInfo): List<Int> {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    private fun classify(node: AccessibilityNodeInfo): String {
        val className = node.className?.toString().orEmpty().lowercase()
        return when {
            "edit" in className -> "input"
            "checkbox" in className || "radio" in className || "switch" in className -> "checkbox"
            "button" in className || "imag" in className -> "button"
            "list" in className -> "list_item"
            node.text?.toString()?.isNotBlank() == true -> "text"
            else -> "button"
        }
    }

    /** 复合控件的语义标签：自身及可见后代的 text/content-desc 去重、按树序拼接。 */
    private fun semanticText(node: AccessibilityNodeInfo): String {
        val labels = linkedSetOf<String>()
        fun visit(current: AccessibilityNodeInfo) {
            if (current === node || isVisible(current)) {
                for (value in listOf(current.text?.toString(), current.contentDescription?.toString())) {
                    val label = value.orEmpty().replace("\n", " ").trim()
                    if (label.isNotEmpty()) labels += label
                }
            }
            for (index in 0 until current.childCount) {
                current.getChild(index)?.let { visit(it) }
            }
        }
        visit(node)
        return cleanText(labels.joinToString(" / "))
    }

    private fun isVisible(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return bounds.left >= 0 && bounds.top >= 0
    }

    private fun cleanText(raw: String): String {
        var text = raw.replace("\n", " ").trim()
        if (text.length > MAX_TEXT_LEN) text = text.take(MAX_TEXT_LEN - 1) + "…"
        return text
    }

    private fun shortLabel(node: AccessibilityNodeInfo): String {
        val raw = node.text?.toString()?.takeIf { it.isNotEmpty() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }
            ?: ""
        val value = raw.replace("\n", " ").trim()
        return if (value.isEmpty()) "" else value.take(16)
    }

    private fun renderWindowLayers(windowFacts: List<WindowFact>): String {
        if (windowFacts.isEmpty()) return ""
        return windowFacts.joinToString("\n") { fact ->
            "- ${fact.typeLabel}@层${fact.layer} bounds=[${fact.bounds[0]},${fact.bounds[1]}][${fact.bounds[2]},${fact.bounds[3]}]"
        }
    }
}

/** 窗口图层/区域事实（与 agent/ui_summarizer.to_window_layers 输出同格式）。 */
data class WindowFact(val typeLabel: String, val layer: Int, val bounds: List<Int>)
