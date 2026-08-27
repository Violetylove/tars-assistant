package org.atovio.tars

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var connectionStatus: TextView
    private lateinit var clearButton: Button
    private lateinit var terminateButton: Button
    private lateinit var intentInput: EditText
    private lateinit var send: Button
    private lateinit var timeline: LinearLayout
    private lateinit var conversationScroll: ScrollView
    private lateinit var voice: VoiceIntentCapture
    private val timelineEntries = mutableListOf<TimelineEntry>()
    @Volatile private var requestInFlight = false
    @Volatile private var cancelRequested = false
    @Volatile private var activeAgentClient: AgentClient? = null
    @Volatile private var taskThread: Thread? = null
    @Volatile private var readinessCheckInFlight = false
    @Volatile private var activeLogSessionId: String? = null
    private var lastReadinessSignature: String? = null
    private var blockedIntent: String? = null
    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TarsAccessibilityService.ACTION_CONNECTED) {
                refreshReadiness()
            } else {
                appendLog("收到待处理任务。可在设置页载入后检查并发送。")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildConversationUi()
        voice = VoiceIntentCapture(this,
            onResult = { transcript -> runOnUiThread { intentInput.setText(transcript); setConnectionStatus("语音意图已填入，请检查后发送") } },
            onStatus = { message -> runOnUiThread { setConnectionStatus(message) } },
        )
        send.setOnClickListener { submitIntent() }
        val restoredEntries = savedInstanceState?.getStringArrayList(STATE_TIMELINE)
        if (restoredEntries.isNullOrEmpty()) {
            refreshReadiness(forceAnnouncement = true)
        } else {
            restoredEntries.forEach { encoded ->
                appendMessage(encoded.drop(2), encoded.startsWith("U:"))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(NotificationTriggerService.ACTION_PENDING_TRIGGER)
            addAction(TarsAccessibilityService.ACTION_CONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(triggerReceiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(triggerReceiver, filter)
        }
    }

    override fun onStop() { unregisterReceiver(triggerReceiver); super.onStop() }

    override fun onResume() {
        super.onResume()
        if (!requestInFlight) refreshReadiness()
    }

    override fun onDestroy() { voice.destroy(); executor.shutdownNow(); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_TIMELINE, ArrayList(timelineEntries.map {
            (if (it.fromUser) "U:" else "L:") + it.message
        }))
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SETTINGS_REQUEST_CODE || resultCode != RESULT_OK) return
        data?.getStringExtra(SettingsActivity.EXTRA_LOADED_INTENT)?.let { task ->
            intentInput.setText(task)
            appendLog("已从待处理触发器载入任务，请检查后发送。")
        }
        if (data?.getBooleanExtra(SettingsActivity.EXTRA_SCHEDULED, false) == true) appendLog("当前草稿已设为定时提醒。")
    }

    private fun buildConversationUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 250, 252))
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val bottomInset = maxOf(
                    systemBars.bottom,
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                )
                view.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    bottomInset,
                )
                if (insets.isVisible(WindowInsetsCompat.Type.ime()) && ::conversationScroll.isInitialized) {
                    conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
                }
                insets
            }
        }
        root.addView(topBar())
        root.addView(View(this).apply { setBackgroundColor(Color.rgb(226, 232, 240)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)) })
        timeline = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(12)) }
        conversationScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(timeline)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(conversationScroll)
        root.addView(composer())
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun topBar(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(10), dp(12))
        val labels = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this@MainActivity).apply { text = "TARS"; textSize = 21f; setTextColor(Color.rgb(15, 23, 42)) })
        connectionStatus = TextView(this@MainActivity).apply { text = "等待无障碍服务连接"; textSize = 12f; setTextColor(Color.rgb(71, 85, 105)) }
        labels.addView(connectionStatus)
        addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        terminateButton = Button(this@MainActivity).apply {
            text = "终止任务"
            isAllCaps = false
            setTextColor(Color.rgb(185, 28, 28))
            setOnClickListener { terminateTask() }
        }
        clearButton = Button(this@MainActivity).apply {
            text = "清除记录"
            isAllCaps = false
            setOnClickListener { confirmClearRecords() }
        }
        addView(clearButton, LinearLayout.LayoutParams(dp(88), dp(48)))
        addView(terminateButton, LinearLayout.LayoutParams(dp(92), dp(48)))
        addView(TextView(this@MainActivity).apply {
            contentDescription = "打开设置"
            text = "⚙"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(71, 85, 105))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { openSettings() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun composer(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(14))
        setBackgroundColor(Color.WHITE)
        intentInput = EditText(this@MainActivity).apply {
            hint = "输入任务意图"
            minLines = 1
            maxLines = 4
            background = roundedBackground(Color.rgb(241, 245, 249), Color.rgb(203, 213, 225), 20)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        addView(intentInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageButton(this@MainActivity).apply {
            contentDescription = "按住说话"
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (hasMicrophonePermission()) voice.start()
                        else requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION_REQUEST_CODE)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { voice.stop(); true }
                    else -> true
                }
            }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        send = Button(this@MainActivity).apply { text = "发送"; isAllCaps = false }
        addView(send, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46)))
    }

    private fun submitIntent() {
        val taskIntent = intentInput.text.toString().trim()
        if (taskIntent.isEmpty()) { setConnectionStatus("请输入任务意图"); return }
        val issues = localReadinessIssues()
        if (issues.isNotEmpty()) {
            if (blockedIntent != taskIntent) appendUserMessage(taskIntent)
            blockedIntent = taskIntent
            reportReadinessIssues(issues, forceAnnouncement = true)
            return
        }
        send.isEnabled = false
        requestInFlight = true
        cancelRequested = false
        terminateButton.text = "终止任务"
        setConnectionStatus("正在检查 Agent 服务")
        executor.execute {
            taskThread = Thread.currentThread()
            val client = AgentClient(this)
            activeAgentClient = client
            val agentReady = try {
                !cancelRequested && client.health()
            } catch (_: Exception) {
                false
            }
            if (cancelRequested) {
                finishCancelledTask()
                return@execute
            }
            if (!agentReady) {
                requestInFlight = false
                activeAgentClient = null
                taskThread = null
                runOnUiThread {
                    send.isEnabled = true
                    reportReadinessIssues(listOf(agentUnavailableIssue()), forceAnnouncement = true)
                }
                return@execute
            }
            runOnUiThread {
                if (blockedIntent != taskIntent) appendUserMessage(taskIntent)
                blockedIntent = null
                intentInput.setText("")
                setConnectionStatus("任务进行中")
                appendLog("正在采集当前界面并请求 Agent 决策。")
            }
            runTask(taskIntent, client)
        }
    }

    private fun runTask(taskIntent: String, client: AgentClient) {
        val runtime = RuntimeSettings.read(this)
        var completedByAgent = false
        var reachedRoundLimit = true
        try {
            val service = TarsAccessibilityService.instance
                ?: throw IllegalStateException("无障碍服务已断开，请重新连接后发送")
            val history = JSONArray()
            val sessionId = newSessionId()
            activeLogSessionId = sessionId
            AndroidLogStore.append(this, "session=$sessionId task_started intent=${JSONObject.quote(taskIntent)}")
            var consecutiveNoChange = 0
            var noteForNextRound = ""
            // 轮数溢出检查（Android 侧）：请求只发到用户设置的 maxObservationRounds 为止，
            // 达到上限即本地结束，不再发送下一轮请求；协议侧已取消 history 轮数上限。
            for (round in 0 until runtime.maxObservationRounds) {
                if (cancelRequested) {
                    finishCancelledTask()
                    return
                }
                var snapshot = service.captureUiSnapshot()
                if (snapshot == null) {
                    val currentForeground = service.currentAppPackage() ?: UNKNOWN_FOREGROUND
                    appendLog("当前界面尚未提供有效无障碍树，正在本地等待加载。")
                    AndroidLogStore.append(
                        this,
                        "session=$sessionId round=${round + 1} capture_rejected app=${JSONObject.quote(currentForeground)} " +
                            "reason=empty_or_placeholder_root capture_sources=${service.captureSourceState()}",
                    )
                    snapshot = service.awaitStableUi(
                        runtime.observationTimeoutMs + runtime.newAppGraceMs,
                    ) { state ->
                        AndroidLogStore.append(
                            this,
                            "session=$sessionId round=${round + 1} capture_sources_changed $state",
                        )
                    }
                    if (snapshot == null) {
                        appendLog("未获取到有效无障碍界面，已安全停止任务。")
                        AndroidLogStore.append(
                            this,
                            "session=$sessionId round=${round + 1} agent_request_skipped reason=ui_tree_not_ready",
                        )
                        reachedRoundLimit = false
                        break
                    }
                }
                // xml 仅作本地新鲜度指纹，不发送给 Agent；摘要节点与执行节点同源。
                val uiXml = snapshot.xml
                val observationVersion = service.currentObservationVersion()
                val foreground = service.currentAppPackage() ?: UNKNOWN_FOREGROUND
                appendLog("第 ${round + 1} 轮，前台应用：$foreground")
                AndroidLogStore.append(
                    this,
                    "session=$sessionId round=${round + 1} capture app=${JSONObject.quote(foreground)} " +
                        "activity=${JSONObject.quote(service.currentActivity().orEmpty())} " +
                        "nodes=${snapshot.summaryNodes.size}",
                )
                val response = client.run(TaskRequest(
                    intent = taskIntent,
                    app = service.currentAppPackage(),
                    activity = service.currentActivity(),
                    nodes = snapshot.summaryNodes.map { it.toJson() },
                    windowLayers = snapshot.windowLayers,
                    sessionId = sessionId,
                    history = history,
                    observationNote = noteForNextRound.takeIf { it.isNotBlank() },
                    launchableApps = LaunchableApps.selectedInstalled(this),
                )).let { parsed ->
                    if (parsed.actions.size <= MAX_ACTIONS_PER_HISTORY_ENTRY) {
                        parsed
                    } else {
                        appendLog("Agent 返回动作过多，已分批执行。")
                        parsed.copy(
                            actions = parsed.actions.take(MAX_ACTIONS_PER_HISTORY_ENTRY),
                            done = false,
                            needObservation = true,
                        )
                    }
                }
                AndroidLogStore.append(
                    this,
                    "session=$sessionId round=${round + 1} agent_response done=${response.done} need_observation=${response.needObservation} reply=${JSONObject.quote(response.reply)} actions=${response.actions.toJsonArray()}",
                )
                noteForNextRound = ""
                if (response.reply.isNotBlank()) appendLog(response.reply)
                val execution = service.execute(
                    response.actions,
                    { action -> service.confirmAction(action, snapshot) },
                    snapshot,
                    sessionId,
                )
                execution.messages.forEach(::appendLog)
                if (cancelRequested) {
                    finishCancelledTask()
                    return
                }
                if (!execution.completed) {
                    consecutiveNoChange++
                    noteForNextRound = NO_CHANGE_NOTE
                    appendLog("动作未生效，正在重新采集当前界面（连续无变化第 $consecutiveNoChange 次）。")
                    if (consecutiveNoChange >= MAX_NO_CHANGE_ROUNDS) {
                        appendLog("连续多次无变化，已停止任务。")
                        reachedRoundLimit = false
                        break
                    }
                    continue
                }
                // Keep the next request valid even when an older/misconfigured Agent
                // returns more actions than the protocol permits in one history entry.
                history.put(JSONObject().put("actions", response.actions.take(MAX_ACTIONS_PER_HISTORY_ENTRY).toJsonArray()))
                if (response.done) {
                    completedByAgent = true
                    reachedRoundLimit = false
                    appendLog("任务完成。")
                    break
                }
                if (!response.needObservation || response.actions.isEmpty()) {
                    reachedRoundLimit = false
                    break
                }
                if (cancelRequested) {
                    finishCancelledTask()
                    return
                }
                val launchedPackage = response.actions.lastOrNull { it.type == "launch" }?.packageName
                if (!service.awaitFreshUiAfter(
                        previousUiXml = uiXml,
                        previousPackage = foreground,
                        timeoutMs = runtime.observationTimeoutMs,
                        previousObservationVersion = observationVersion,
                        expectedPackage = launchedPackage,
                        extraGraceMs = if (launchedPackage != null) runtime.newAppGraceMs else 0L,
                    )
                ) {
                    consecutiveNoChange++
                    noteForNextRound = NO_CHANGE_NOTE
                    appendLog("未观察到界面更新，正在重新采集当前界面（连续无变化第 $consecutiveNoChange 次）。")
                    if (consecutiveNoChange >= MAX_NO_CHANGE_ROUNDS) {
                        appendLog("连续多次无变化，已停止任务。")
                        reachedRoundLimit = false
                        break
                    }
                    continue
                }
                consecutiveNoChange = 0
                appendLog("界面已更新，进入下一轮（前台：${service.currentAppPackage() ?: UNKNOWN_FOREGROUND}）。")
            }
            if (reachedRoundLimit) appendLog("已达到最大观察轮数，已停止任务。")
            runOnUiThread { setConnectionStatus(if (completedByAgent) "任务完成" else "任务已结束") }
        } catch (e: Exception) {
            if (cancelRequested) {
                finishCancelledTask()
            } else {
                appendLog("请求失败：${e.message}")
                runOnUiThread { setConnectionStatus("任务失败") }
            }
        } finally {
            requestInFlight = false
            activeAgentClient = null
            taskThread = null
            cancelRequested = false
            activeLogSessionId = null
            runOnUiThread { send.isEnabled = true }
        }
    }

    private fun terminateTask() {
        if (!requestInFlight) {
            setConnectionStatus("当前没有运行中的任务")
            appendLog("当前没有运行中的任务。")
            return
        }
        cancelRequested = true
        activeAgentClient?.cancel()
        taskThread?.interrupt()
        setConnectionStatus("正在终止任务")
        appendLog("已请求终止任务，正在停止当前请求。")
    }

    private fun finishCancelledTask() {
        requestInFlight = false
        activeAgentClient = null
        taskThread = null
        runOnUiThread {
            setConnectionStatus("任务已终止")
            send.isEnabled = true
            terminateButton.text = "终止任务"
            appendLog("任务已终止。")
        }
    }

    private fun appendUserMessage(message: String) = appendMessage(message, true)
    private fun appendLog(message: String) = appendMessage(message, false)

    private fun appendLogWithLink(message: String, linkLabel: String, action: () -> Unit) {
        runOnUiThread {
            timelineEntries += TimelineEntry(message, false)
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val bubbleRow = LinearLayout(this).apply { gravity = Gravity.START }
            bubbleRow.addView(logBubble(message))
            container.addView(bubbleRow)
            container.addView(TextView(this).apply {
                text = linkLabel
                textSize = 14f
                setTextColor(Color.rgb(37, 99, 235))
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                contentDescription = linkLabel
                setPadding(dp(14), dp(8), dp(14), dp(2))
                setOnClickListener { action() }
            })
            timeline.addView(container, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
            conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun appendMessage(message: String, fromUser: Boolean) {
        runOnUiThread {
            timelineEntries += TimelineEntry(message, fromUser)
            val row = LinearLayout(this).apply { gravity = if (fromUser) Gravity.END else Gravity.START }
            val bubble = if (fromUser) userBubble(message) else logBubble(message)
            if (fromUser) {
                bubble.setOnLongClickListener {
                    showUserMessageActions(message)
                    true
                }
            }
            row.addView(bubble)
            timeline.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
            val sessionPrefix = activeLogSessionId?.let { "session=$it " }.orEmpty()
            AndroidLogStore.append(this, "$sessionPrefix${if (fromUser) "intent" else "timeline"}: $message")
        }
    }

    private fun showUserMessageActions(message: String) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("复制", "重新发送")) { _, which ->
                when (which) {
                    0 -> {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TARS 意图", message))
                        setConnectionStatus("已复制意图")
                    }
                    1 -> {
                        intentInput.setText(message)
                        intentInput.setSelection(intentInput.length())
                        submitIntent()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearRecords() {
        AlertDialog.Builder(this)
            .setTitle("清除记录")
            .setMessage("确定清除当前对话记录吗？正在运行的任务不会被终止。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ -> clearRecords() }
            .show()
    }

    private fun clearRecords() {
        timelineEntries.clear()
        timeline.removeAllViews()
        lastReadinessSignature = null
        setConnectionStatus(if (requestInFlight) "任务进行中" else "记录已清除")
        AndroidLogStore.append(this, "timeline cleared")
    }

    private fun userBubble(message: String): TextView = messageBubble(
        message,
        Color.WHITE,
        Color.rgb(37, 99, 235),
        Color.rgb(37, 99, 235),
    )

    private fun logBubble(message: String): TextView = messageBubble(
        message,
        Color.rgb(30, 41, 59),
        Color.WHITE,
        Color.rgb(226, 232, 240),
    )

    private fun messageBubble(message: String, textColor: Int, fill: Int, stroke: Int): TextView = TextView(this).apply {
        text = message
        textSize = 15f
        setTextColor(textColor)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        background = roundedBackground(fill, stroke, 14)
    }

    private fun refreshReadiness(forceAnnouncement: Boolean = false) {
        if (requestInFlight) return
        val localIssues = localReadinessIssues()
        if (localIssues.isNotEmpty()) {
            reportReadinessIssues(localIssues, forceAnnouncement)
            return
        }
        if (readinessCheckInFlight) return
        readinessCheckInFlight = true
        setConnectionStatus("正在检查 Agent 服务")
        executor.execute {
            val agentReady = try {
                AgentClient(this).health()
            } catch (_: Exception) {
                false
            }
            readinessCheckInFlight = false
            runOnUiThread {
                if (requestInFlight) return@runOnUiThread
                val changedIssues = localReadinessIssues()
                when {
                    changedIssues.isNotEmpty() -> reportReadinessIssues(changedIssues, forceAnnouncement)
                    agentReady -> reportReady(forceAnnouncement)
                    else -> reportReadinessIssues(listOf(agentUnavailableIssue()), forceAnnouncement)
                }
            }
        }
    }

    private fun localReadinessIssues(): List<ReadinessIssue> {
        val issues = mutableListOf<ReadinessIssue>()
        if (TarsAccessibilityService.instance == null) {
            issues += ReadinessIssue(
                key = "accessibility",
                message = "无障碍服务未开启，TARS 无法读取和操作当前界面。",
                linkLabel = "打开无障碍设置",
                action = { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            )
        }
        when (ShizukuGateway().connectionState()) {
            ShizukuGateway.ConnectionState.SERVICE_UNAVAILABLE -> issues += ReadinessIssue(
                key = "shizuku-service",
                message = "Shizuku 服务未启动或当前版本不可用。",
                linkLabel = "前往 Shizuku 设置",
                action = ::openSettings,
            )
            ShizukuGateway.ConnectionState.AUTHORIZATION_REQUIRED -> issues += ReadinessIssue(
                key = "shizuku-permission",
                message = "Shizuku 尚未授权给 TARS。",
                linkLabel = "连接 Shizuku",
                action = ::requestShizukuConnection,
            )
            ShizukuGateway.ConnectionState.READY -> Unit
        }
        return issues
    }

    private fun agentUnavailableIssue(): ReadinessIssue = ReadinessIssue(
        key = "agent",
        message = "Agent 服务不可连接，请检查主机地址、端口和服务运行状态。",
        linkLabel = "打开 Agent 设置",
        action = ::openSettings,
    )

    private fun reportReadinessIssues(issues: List<ReadinessIssue>, forceAnnouncement: Boolean) {
        val signature = issues.joinToString(separator = "|") { it.key }
        setConnectionStatus("需要完成 ${issues.size} 项连接")
        if (forceAnnouncement || signature != lastReadinessSignature) {
            issues.forEach { issue -> appendLogWithLink(issue.message, issue.linkLabel, issue.action) }
        }
        lastReadinessSignature = signature
    }

    private fun reportReady(forceAnnouncement: Boolean) {
        setConnectionStatus("准备就绪")
        if (forceAnnouncement || lastReadinessSignature != READY_SIGNATURE) {
            appendLog("准备就绪。发送任务后，我会在这里记录前台状态与执行过程。")
        }
        lastReadinessSignature = READY_SIGNATURE
    }

    private fun openSettings() {
        startActivityForResult(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_DRAFT_INTENT, intentInput.text.toString().trim()),
            SETTINGS_REQUEST_CODE,
        )
    }

    private fun requestShizukuConnection() {
        when (ShizukuGateway().requestPermission(SHIZUKU_REQUEST_CODE)) {
            ShizukuGateway.PermissionRequestResult.GRANTED -> refreshReadiness(forceAnnouncement = true)
            ShizukuGateway.PermissionRequestResult.REQUESTED -> setConnectionStatus("请在 Shizuku 中确认授权")
            ShizukuGateway.PermissionRequestResult.RATIONALE_REQUIRED -> appendLogWithLink(
                "Shizuku 授权此前被拒绝，请在设置页中重新授权。",
                "前往 Shizuku 设置",
                ::openSettings,
            )
            ShizukuGateway.PermissionRequestResult.UNAVAILABLE -> appendLogWithLink(
                "Shizuku 服务不可用，请先在管理器中启动。",
                "前往 Shizuku 设置",
                ::openSettings,
            )
        }
    }

    private fun setConnectionStatus(message: String) { if (::connectionStatus.isInitialized) connectionStatus.text = message }

    private fun newSessionId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    private fun hasMicrophonePermission(): Boolean = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply { setColor(fill); setStroke(dp(1), stroke); cornerRadius = dp(radiusDp).toFloat() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_NO_CHANGE_ROUNDS = 2
        private const val MAX_ACTIONS_PER_HISTORY_ENTRY = 8
        private const val UNKNOWN_FOREGROUND = "未知"
        private const val NO_CHANGE_NOTE = "上一轮动作未使界面发生变化（目标可能被遮挡、已出视口或不可达）。当前界面见本次新采集；请重新观察，选择其他能推进目标的动作。"
        private const val MICROPHONE_PERMISSION_REQUEST_CODE = 1003
        private const val SHIZUKU_REQUEST_CODE = 1001
        private const val SETTINGS_REQUEST_CODE = 2001
        private const val STATE_TIMELINE = "timeline"
        private const val READY_SIGNATURE = "ready"
    }

    private data class ReadinessIssue(
        val key: String,
        val message: String,
        val linkLabel: String,
        val action: () -> Unit,
    )

    private data class TimelineEntry(val message: String, val fromUser: Boolean)
}

private fun List<AgentAction>.toJsonArray(): JSONArray = JSONArray().also { result ->
    forEach { action -> result.put(JSONObject().put("type", action.type).apply {
        action.targetNodeId?.let { put("target_node_id", it) }
        action.text?.let { put("text", it) }
        action.packageName?.let { put("package_name", it) }
        action.ms?.let { put("ms", it) }
        put("requires_confirmation", action.requiresConfirmation)
        action.x1?.let { put("x1", it) }
        action.y1?.let { put("y1", it) }
        action.x2?.let { put("x2", it) }
        action.y2?.let { put("y2", it) }
        action.durationMs?.let { put("duration_ms", it) }
    }) }
}
