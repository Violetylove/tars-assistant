package org.atovio.tars

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class ActionExecutor(
    private val service: AccessibilityService,
    private val confirm: (AgentAction) -> Boolean,
    private val shizuku: ShizukuGateway = ShizukuGateway(),
) {
    data class ExecutionSummary(val messages: List<String>, val completed: Boolean)

    fun execute(actions: List<AgentAction>): ExecutionSummary {
        val results = mutableListOf<String>()
        for (action in actions) {
            if (action.type !in ALLOWED) {
                results += "拒绝未知动作: ${action.type}"
                return ExecutionSummary(results, completed = false)
            }
            if (!wellFormed(action)) {
                results += "拒绝参数不完整: ${action.type}"
                return ExecutionSummary(results, completed = false)
            }
            if (requiresConfirmation(action) && !confirm(action)) {
                results += "已取消: ${action.type}"
                return ExecutionSummary(results, completed = false)
            }
            if (!executeOne(action)) {
                results += "执行失败: ${action.type}"
                return ExecutionSummary(results, completed = false)
            }
            results += "已执行: ${actionTrace(action)}"
        }
        return ExecutionSummary(results, completed = true)
    }

    private fun wellFormed(action: AgentAction): Boolean = when (action.type) {
        "click" -> action.targetNodeId != null && action.targetNodeId >= 0
        "type" -> action.targetNodeId != null && action.targetNodeId >= 0 && action.text != null
        "wait" -> action.ms != null && action.ms >= 0
        "swipe" -> listOf(action.x1, action.y1, action.x2, action.y2).all { it != null } &&
            action.durationMs != null && action.durationMs >= 0
        "launch" -> action.packageName in LAUNCHABLE_PACKAGES
        else -> true
    }

    private fun requiresConfirmation(action: AgentAction): Boolean {
        if (action.requiresConfirmation) return true
        if (action.type != "click") return false
        val label = findNode(action.targetNodeId)?.let { node ->
            "${node.text?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()}".lowercase()
        }.orEmpty()
        return SENSITIVE_LABELS.any { label.contains(it) }
    }

    private fun executeOne(action: AgentAction): Boolean {
        return when (action.type) {
            "click" -> findNode(action.targetNodeId)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            "type" -> findNode(action.targetNodeId)?.let { node ->
                val focused = node.isFocused || node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val setText = focused && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text.orEmpty())
                })
                Log.i(TAG, "type target=${action.targetNodeId} class=${node.className} focused=$focused setText=$setText")
                setText || shizuku.typeText(action.text.orEmpty())
            }?.also { Log.i(TAG, "type target=${action.targetNodeId} nodeFound=true result=$it")
            } ?: run {
                Log.w(TAG, "type target=${action.targetNodeId} nodeFound=false")
                false
            }
            "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "launch" -> action.packageName?.let { packageName ->
                service.packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
                    launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    service.startActivity(launchIntent)
                    true
                }
            } == true
            "wait" -> { Thread.sleep((action.ms ?: 0).coerceAtMost(10_000).toLong()); true }
            "reply", "done" -> true
            "swipe" -> shizuku.swipe(action)
            else -> false
        }
    }

    /** Keeps task traces useful without echoing user-entered text or swipe coordinates. */
    private fun actionTrace(action: AgentAction): String = when (action.type) {
        "click", "type" -> "${action.type} (节点 #${action.targetNodeId})"
        "launch" -> "launch (${action.packageName})"
        else -> action.type
    }

    private fun findNode(id: Int?): AccessibilityNodeInfo? {
        if (id == null) return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collect(service.rootInActiveWindow, nodes)
        nodes.sortWith(compareBy<AccessibilityNodeInfo> {
            Rect().also { rect -> it.getBoundsInScreen(rect) }.top
        }.thenBy {
            Rect().also { rect -> it.getBoundsInScreen(rect) }.left
        })
        // Must mirror agent.ui_summarizer.MAX_NODES, because IDs are post-truncation summary IDs.
        return nodes.take(60).getOrNull(id)
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val className = node.className?.toString().orEmpty()
        val importantClass = className.contains("Button", ignoreCase = true) ||
            className.contains("EditText", ignoreCase = true) ||
            className.contains("CheckBox", ignoreCase = true) ||
            className.contains("RadioButton", ignoreCase = true) ||
            className.contains("Switch", ignoreCase = true) ||
            className.contains("ImageButton", ignoreCase = true)
        // Keep this predicate aligned with agent/ui_summarizer.py; action IDs are summary IDs.
        if (bounds.left >= 0 && bounds.top >= 0 &&
            (node.isClickable || node.isFocusable || (importantClass && node.isEnabled))) out += node
        for (i in 0 until node.childCount) collect(node.getChild(i), out)
    }

    companion object {
        private const val TAG = "TarsAction"
        private val ALLOWED = setOf("click", "type", "swipe", "back", "home", "launch", "wait", "reply", "done")
        private val LAUNCHABLE_PACKAGES = setOf(
            "com.android.settings",
            "org.atovio.tars",
            "com.google.android.gm",
            "com.tencent.mm",
        )
        private val SENSITIVE_LABELS = setOf("发送", "删除", "清除", "支付", "付款", "转账", "send", "delete", "pay")
    }
}
