package com.tars.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.MotionEvent
import android.os.Bundle
import android.os.Build
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val client = AgentClient()
    private lateinit var status: TextView
    private lateinit var intentInput: EditText
    private lateinit var voice: VoiceIntentCapture
    @Volatile private var requestInFlight = false
    @Volatile private var taskResultVisible = false
    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TarsAccessibilityService.ACTION_CONNECTED) {
                if (!requestInFlight && !taskResultVisible) status.text = "无障碍服务已连接"
            } else {
                taskResultVisible = false
                status.text = "有待处理任务，请点击“载入待处理任务”后检查"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intentInput = EditText(this).apply { hint = "输入任务意图"; minLines = 2 }
        val run = Button(this).apply { text = "发送给 TARS" }
        val openAccessibilitySettings = Button(this).apply { text = "打开无障碍设置" }
        val openNotificationListenerSettings = Button(this).apply { text = "打开通知访问设置" }
        val holdToSpeak = Button(this).apply { text = "按住说话" }
        val toggleFloatingVoice = Button(this).apply { text = "启用悬浮语音" }
        val schedule = Button(this).apply { text = "15 分钟后提醒" }
        val useNotification = Button(this).apply { text = "载入待处理任务" }
        val authorizeShizuku = Button(this).apply { text = "授权 Shizuku" }
        status = TextView(this).apply { text = "等待无障碍服务连接" }
        voice = VoiceIntentCapture(this, onResult = { transcript ->
            runOnUiThread { intentInput.setText(transcript); status.text = "语音意图已填入，请检查后发送" }
        }, onStatus = { message -> runOnUiThread { status.text = message } })
        run.setOnClickListener {
            val intent = intentInput.text.toString().trim()
            if (intent.isEmpty()) { status.text = "请输入任务意图"; return@setOnClickListener }
            run.isEnabled = false
            requestInFlight = true
            taskResultVisible = false
            status.text = "云端模型推理中，请稍候"
            executor.execute {
                try {
                    val service = TarsAccessibilityService.instance
                    val history = JSONArray()
                    val sessionId = java.util.UUID.randomUUID().toString()
                    val output = mutableListOf<String>()
                    var reachedRoundLimit = true
                    for (round in 0 until MAX_OBSERVATION_ROUNDS) {
                        val uiXml = service?.currentUiXml().orEmpty()
                        val foreground = service?.currentAppPackage() ?: UNKNOWN_FOREGROUND
                        output += "第 ${round + 1} 轮前台：$foreground"
                        val response = client.run(TaskRequest(
                            intent = intent,
                            app = service?.currentAppPackage(),
                            activity = service?.currentActivity(),
                            uiXml = uiXml,
                            sessionId = sessionId,
                            history = history,
                        ))
                        if (response.reply.isNotBlank()) output += response.reply
                        val execution = service?.execute(response.actions, ::confirmAction)
                            ?: ActionExecutor.ExecutionSummary(listOf("无障碍服务未连接"), completed = false)
                        output += execution.messages
                        if (!execution.completed) {
                            reachedRoundLimit = false
                            break
                        }
                        history.put(JSONObject().put("actions", response.actions.toJsonArray()))
                        if (response.done || !response.needObservation || response.actions.isEmpty()) {
                            reachedRoundLimit = false
                            break
                        }
                        if (service == null || !service.awaitFreshUiAfter(
                                uiXml, OBSERVATION_TIMEOUT_MS,
                            )) {
                            output += "未观察到界面更新，已停止任务"
                            reachedRoundLimit = false
                            break
                        }
                        output += "界面已更新，进入下一轮（前台：${service.currentAppPackage() ?: UNKNOWN_FOREGROUND}）"
                    }
                    if (reachedRoundLimit) output += "已达到最大观察轮数，已停止任务"
                    runOnUiThread {
                        taskResultVisible = true
                        status.text = output.joinToString("\n")
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        taskResultVisible = true
                        status.text = "请求失败: ${e.message}"
                    }
                }
                finally {
                    requestInFlight = false
                    runOnUiThread { run.isEnabled = true }
                }
            }
        }
        authorizeShizuku.setOnClickListener {
            status.text = when (ShizukuGateway().requestPermission(SHIZUKU_REQUEST_CODE)) {
                ShizukuGateway.PermissionRequestResult.GRANTED -> "Shizuku 已授权"
                ShizukuGateway.PermissionRequestResult.REQUESTED -> "已请求 Shizuku 授权，请在管理器中确认"
                ShizukuGateway.PermissionRequestResult.RATIONALE_REQUIRED -> "Shizuku 授权此前被拒绝，请在管理器中手动启用 TARS"
                ShizukuGateway.PermissionRequestResult.UNAVAILABLE -> "Shizuku 服务不可用，请先在管理器中启动"
            }
        }
        openAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        openNotificationListenerSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        schedule.setOnClickListener {
            val task = intentInput.text.toString().trim()
            if (task.isEmpty()) { status.text = "请输入要定时处理的任务"; return@setOnClickListener }
            requestNotificationPermission()
            TaskScheduler.scheduleIn(this, task, FIFTEEN_MINUTES_MS)
            status.text = "已安排 15 分钟后的待处理提醒"
        }
        holdToSpeak.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (hasMicrophonePermission()) voice.start() else requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION_REQUEST_CODE)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { voice.stop(); true }
                else -> true
            }
        }
        toggleFloatingVoice.setOnClickListener {
            val service = TarsAccessibilityService.instance
            when {
                service == null -> status.text = "请先启用 TARS 无障碍服务"
                !hasMicrophonePermission() -> requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION_REQUEST_CODE)
                service.toggleFloatingVoice() -> {
                    toggleFloatingVoice.text = "停用悬浮语音"
                    status.text = "悬浮语音已启用，识别结果需手动载入后发送"
                }
                else -> {
                    toggleFloatingVoice.text = "启用悬浮语音"
                    status.text = "悬浮语音已停用"
                }
            }
        }
        useNotification.setOnClickListener { loadPendingTrigger() }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32); addView(intentInput); addView(run); addView(holdToSpeak); addView(toggleFloatingVoice); addView(schedule); addView(useNotification); addView(openAccessibilitySettings); addView(openNotificationListenerSettings); addView(authorizeShizuku); addView(status) })
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(NotificationTriggerService.ACTION_PENDING_TRIGGER)
            addAction(TarsAccessibilityService.ACTION_CONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(triggerReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(triggerReceiver, filter)
        }
    }

    override fun onStop() { unregisterReceiver(triggerReceiver); super.onStop() }

    override fun onResume() {
        super.onResume()
        if (TarsAccessibilityService.instance != null && !requestInFlight && !taskResultVisible) {
            status.text = "无障碍服务已连接"
        }
    }

    override fun onDestroy() { voice.destroy(); executor.shutdownNow(); super.onDestroy() }

    private fun confirmAction(action: AgentAction): Boolean {
        val approved = BooleanArray(1)
        val completion = CountDownLatch(1)
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("确认 TARS 操作")
                .setMessage(actionDescription(action))
                .setNegativeButton("取消") { _, _ -> completion.countDown() }
                .setPositiveButton("确认") { _, _ -> approved[0] = true; completion.countDown() }
                .setOnCancelListener { completion.countDown() }
                .show()
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

    private fun loadPendingTrigger() {
        PendingTriggerStore.take(this)?.let {
            intentInput.setText(it)
            taskResultVisible = false
            status.text = "已载入待处理任务，请检查后发送"
        } ?: run { status.text = "没有待处理任务" }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val MAX_OBSERVATION_ROUNDS = 8
        private const val OBSERVATION_TIMEOUT_MS = 2_000L
        private const val UNKNOWN_FOREGROUND = "未知"
        private const val FIFTEEN_MINUTES_MS = 15 * 60 * 1000L
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002
        private const val MICROPHONE_PERMISSION_REQUEST_CODE = 1003
        private const val SHIZUKU_REQUEST_CODE = 1001
    }
}

private fun List<AgentAction>.toJsonArray(): JSONArray = JSONArray().also { result ->
    forEach { action -> result.put(JSONObject().put("type", action.type).apply {
        action.targetNodeId?.let { put("target_node_id", it) }
        action.text?.let { put("text", it) }
        action.packageName?.let { put("package_name", it) }
        action.ms?.let { put("ms", it) }
        action.requiresConfirmation?.let { put("requires_confirmation", it) }
        action.x1?.let { put("x1", it) }
        action.y1?.let { put("y1", it) }
        action.x2?.let { put("x2", it) }
        action.y2?.let { put("y2", it) }
        action.durationMs?.let { put("duration_ms", it) }
    }) }
}
