package org.atovio.tars

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var connectionStatus: TextView
    private lateinit var intentInput: EditText
    private lateinit var send: Button
    private lateinit var timeline: LinearLayout
    private lateinit var conversationScroll: ScrollView
    private lateinit var voice: VoiceIntentCapture
    private val timelineEntries = mutableListOf<TimelineEntry>()
    @Volatile private var requestInFlight = false
    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TarsAccessibilityService.ACTION_CONNECTED) {
                setConnectionStatus("无障碍服务已连接")
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
            appendLog("准备就绪。发送任务后，我会在这里记录前台状态与执行过程。")
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
        if (TarsAccessibilityService.instance != null && !requestInFlight) setConnectionStatus("无障碍服务已连接")
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
        addView(TextView(this@MainActivity).apply {
            contentDescription = "打开设置"
            text = "⚙"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(71, 85, 105))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                startActivityForResult(Intent(this@MainActivity, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_DRAFT_INTENT, intentInput.text.toString().trim()), SETTINGS_REQUEST_CODE)
            }
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
        appendUserMessage(taskIntent)
        intentInput.setText("")
        send.isEnabled = false
        requestInFlight = true
        setConnectionStatus("任务进行中")
        appendLog("正在采集当前界面并请求 Agent 决策。")
        val runtime = RuntimeSettings.read(this)
        executor.execute {
            try {
                val client = AgentClient(this)
                val service = TarsAccessibilityService.instance
                val history = JSONArray()
                val sessionId = java.util.UUID.randomUUID().toString()
                var reachedRoundLimit = true
                var consecutiveNoChange = 0
                var noteForNextRound = ""
                for (round in 0 until runtime.maxObservationRounds) {
                    val uiXml = service?.currentUiXml().orEmpty()
                    val observationVersion = service?.currentObservationVersion() ?: 0L
                    val foreground = service?.currentAppPackage() ?: UNKNOWN_FOREGROUND
                    appendLog("第 ${round + 1} 轮，前台应用：$foreground")
                    val response = client.run(TaskRequest(
                        intent = taskIntent, app = service?.currentAppPackage(), activity = service?.currentActivity(), uiXml = uiXml,
                        sessionId = sessionId, history = history, observationNote = noteForNextRound.takeIf { it.isNotBlank() },
                        launchableApps = LaunchableApps.selectedInstalled(this),
                    ))
                    noteForNextRound = ""
                    if (response.reply.isNotBlank()) appendLog(response.reply)
                    val execution = service?.execute(response.actions, ::confirmAction)
                        ?: ActionExecutor.ExecutionSummary(listOf("无障碍服务未连接"), completed = false)
                    execution.messages.forEach(::appendLog)
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
                    history.put(JSONObject().put("actions", response.actions.toJsonArray()))
                    if (response.done || !response.needObservation || response.actions.isEmpty()) {
                        reachedRoundLimit = false
                        break
                    }
                    val pkgChanged = service != null && service.currentAppPackage() != null && service.currentAppPackage() != foreground
                    if (service == null || (!service.awaitFreshUiAfter(uiXml, foreground, runtime.observationTimeoutMs, observationVersion) && !pkgChanged)) {
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
                runOnUiThread { setConnectionStatus("任务已结束") }
            } catch (e: Exception) {
                appendLog("请求失败：${e.message}")
                runOnUiThread { setConnectionStatus("任务失败") }
            } finally {
                requestInFlight = false
                runOnUiThread { send.isEnabled = true }
            }
        }
    }

    private fun appendUserMessage(message: String) = appendMessage(message, true)
    private fun appendLog(message: String) = appendMessage(message, false)
    private fun appendMessage(message: String, fromUser: Boolean) {
        runOnUiThread {
            timelineEntries += TimelineEntry(message, fromUser)
            val row = LinearLayout(this).apply { gravity = if (fromUser) Gravity.END else Gravity.START }
            val bubble = TextView(this).apply {
                text = message
                textSize = 15f
                setTextColor(if (fromUser) Color.WHITE else Color.rgb(30, 41, 59))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
                background = roundedBackground(
                    if (fromUser) Color.rgb(37, 99, 235) else Color.WHITE,
                    if (fromUser) Color.rgb(37, 99, 235) else Color.rgb(226, 232, 240), 14,
                )
            }
            row.addView(bubble)
            timeline.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setConnectionStatus(message: String) { if (::connectionStatus.isInitialized) connectionStatus.text = message }

    private fun confirmAction(action: AgentAction): Boolean {
        val approved = BooleanArray(1)
        val completion = CountDownLatch(1)
        runOnUiThread {
            AlertDialog.Builder(this).setTitle("确认 TARS 操作").setMessage(actionDescription(action))
                .setNegativeButton("取消") { _, _ -> completion.countDown() }
                .setPositiveButton("确认") { _, _ -> approved[0] = true; completion.countDown() }
                .setOnCancelListener { completion.countDown() }.show()
        }
        completion.await()
        return approved[0]
    }

    private fun actionDescription(action: AgentAction): String = when (action.type) {
        "click" -> "点击屏幕节点 #${action.targetNodeId}"
        "type" -> "向节点 #${action.targetNodeId} 输入：${action.text.orEmpty()}"
        "swipe" -> "在屏幕上滑动"
        "back" -> "返回上一页"
        "home" -> "返回桌面"
        "launch" -> "启动应用：${action.packageName.orEmpty()}"
        else -> "执行操作：${action.type}"
    }

    private fun hasMicrophonePermission(): Boolean = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply { setColor(fill); setStroke(dp(1), stroke); cornerRadius = dp(radiusDp).toFloat() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_NO_CHANGE_ROUNDS = 2
        private const val UNKNOWN_FOREGROUND = "未知"
        private const val NO_CHANGE_NOTE = "上一轮动作未使界面发生变化（目标可能被遮挡、已出视口或不可达）。当前界面见本次新采集；请重新观察，选择其他能推进目标的动作。"
        private const val MICROPHONE_PERMISSION_REQUEST_CODE = 1003
        private const val SETTINGS_REQUEST_CODE = 2001
        private const val STATE_TIMELINE = "timeline"
    }

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
