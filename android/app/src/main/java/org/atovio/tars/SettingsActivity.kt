package org.atovio.tars

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var floatingVoiceButton: Button
    private lateinit var maxRounds: EditText
    private lateinit var observationTimeout: EditText
    private lateinit var agentHost: EditText
    private lateinit var agentPort: EditText
    private lateinit var modelTimeout: EditText
    private lateinit var reminderDelay: EditText
    private lateinit var newAppGrace: EditText
    private lateinit var launchAppsButton: Button
    private val draftIntent: String by lazy { intent.getStringExtra(EXTRA_DRAFT_INTENT).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "TARS 设置"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(Color.rgb(248, 250, 252))
        }
        content.addView(header())
        content.addView(sectionTitle("服务与授权"))
        content.addView(button("打开无障碍设置") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        content.addView(button("打开通知访问设置") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        content.addView(button("连接 Shizuku") { requestShizukuPermission() })
        floatingVoiceButton = button("启用悬浮语音") { toggleFloatingVoice() }
        content.addView(floatingVoiceButton)

        content.addView(sectionTitle("任务触发"))
        content.addView(button("载入待处理任务") { loadPendingTrigger() })
        content.addView(button("将当前草稿设为定时提醒") { scheduleDraft() })

        content.addView(sectionTitle("运行参数"))
        maxRounds = settingField(content, "最大观察轮数", InputType.TYPE_CLASS_NUMBER)
        observationTimeout = settingField(content, "界面观察超时（毫秒）", InputType.TYPE_CLASS_NUMBER)
        agentHost = settingField(content, "Agent 服务主机地址", InputType.TYPE_CLASS_TEXT)
        agentPort = settingField(content, "Agent 服务端口", InputType.TYPE_CLASS_NUMBER)
        modelTimeout = settingField(content, "模型请求超时（毫秒）", InputType.TYPE_CLASS_NUMBER)
        reminderDelay = settingField(content, "手动提醒延时（毫秒）", InputType.TYPE_CLASS_NUMBER)
        newAppGrace = settingField(content, "新应用渲染宽限（毫秒）", InputType.TYPE_CLASS_NUMBER)
        content.addView(button("保存运行参数") { saveSettings() })
        content.addView(button("恢复安全默认值") {
            RuntimeSettings.restoreDefaults(this)
            populateSettings()
            refreshLaunchAppsButton()
            showStatus("已恢复安全默认值")
        })

        status = TextView(this).apply {
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(status)

        content.addView(sectionTitle("应用启动"))
        launchAppsButton = button("允许启动的应用") {
            startActivity(Intent(this, LaunchableAppsActivity::class.java))
        }
        content.addView(launchAppsButton)
        val scrollView = ScrollView(this).apply {
            clipToPadding = false
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    systemBars.bottom,
                )
                insets
            }
            addView(content)
        }
        setContentView(scrollView)
        ViewCompat.requestApplyInsets(scrollView)
        populateSettings()
        refreshLaunchAppsButton()
        refreshServiceState()
    }

    override fun onResume() {
        super.onResume()
        if (::floatingVoiceButton.isInitialized) refreshServiceState()
        if (::launchAppsButton.isInitialized) refreshLaunchAppsButton()
    }

    private fun header(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val back = Button(this@SettingsActivity).apply { text = "返回"; setOnClickListener { finish() } }
        addView(back)
        addView(TextView(this@SettingsActivity).apply {
            text = "设置"
            setTextColor(Color.rgb(15, 23, 42))
            textSize = 22f
            setPadding(dp(8), 0, 0, 0)
        })
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.rgb(30, 64, 175))
        textSize = 15f
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    }

    private fun settingField(parent: LinearLayout, label: String, inputType: Int): EditText {
        parent.addView(TextView(this).apply {
            text = label
            setTextColor(Color.rgb(51, 65, 85))
            textSize = 14f
            setPadding(0, dp(14), 0, dp(7))
        })
        return EditText(this).apply {
            this.inputType = inputType
            setSingleLine(true)
            background = roundedBackground(Color.WHITE, Color.rgb(203, 213, 225))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
            parent.addView(this)
        }
    }

    private fun populateSettings() {
        val settings = RuntimeSettings.read(this)
        maxRounds.setText(settings.maxObservationRounds.toString())
        observationTimeout.setText(settings.observationTimeoutMs.toString())
        agentHost.setText(settings.agentHost)
        agentPort.setText(settings.agentPort.toString())
        modelTimeout.setText(settings.modelRequestTimeoutMs.toString())
        reminderDelay.setText(settings.manualReminderDelayMs.toString())
        newAppGrace.setText(settings.newAppGraceMs.toString())
    }

    private fun saveSettings() {
        val values = RuntimeSettings.Values(
            maxObservationRounds = maxRounds.text.toString().toIntOrNull() ?: -1,
            observationTimeoutMs = observationTimeout.text.toString().toLongOrNull() ?: -1L,
            agentHost = agentHost.text.toString().trim(),
            agentPort = agentPort.text.toString().toIntOrNull() ?: -1,
            modelRequestTimeoutMs = modelTimeout.text.toString().toIntOrNull() ?: -1,
            manualReminderDelayMs = reminderDelay.text.toString().toLongOrNull() ?: -1L,
            newAppGraceMs = newAppGrace.text.toString().toLongOrNull() ?: -1L,
        )
        RuntimeSettings.save(this, values)?.let { showStatus(it); return }
        showStatus("运行参数已保存，下次任务立即生效")
    }

    private fun refreshLaunchAppsButton() {
        val installed = LaunchableApps.installed(this).mapTo(mutableSetOf()) { it.packageName }
        val selected = RuntimeSettings.allowedLaunchPackages(this)
        val valid = selected intersect installed
        if (valid.size != selected.size) RuntimeSettings.saveAllowedLaunchPackages(this, valid)
        launchAppsButton.text = "允许启动的应用（已选 ${valid.size}）"
    }

    private fun requestShizukuPermission() {
        showStatus(when (ShizukuGateway().requestPermission(SHIZUKU_REQUEST_CODE)) {
            ShizukuGateway.PermissionRequestResult.GRANTED -> "Shizuku 已授权"
            ShizukuGateway.PermissionRequestResult.REQUESTED -> "已请求 Shizuku 授权，请在管理器中确认"
            ShizukuGateway.PermissionRequestResult.RATIONALE_REQUIRED -> "Shizuku 授权此前被拒绝，请在管理器中手动启用 TARS"
            ShizukuGateway.PermissionRequestResult.UNAVAILABLE -> "Shizuku 服务不可用，请先在管理器中启动"
        })
    }

    private fun toggleFloatingVoice() {
        val service = TarsAccessibilityService.instance
        when {
            service == null -> showStatus("请先启用 TARS 无障碍服务")
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION_REQUEST_CODE)
                showStatus("请授予麦克风权限后再次启用")
            }
            service.toggleFloatingVoice() -> showStatus("悬浮语音已启用，识别结果需手动载入后发送")
            else -> showStatus("悬浮语音已停用")
        }
        refreshServiceState()
    }

    private fun scheduleDraft() {
        if (draftIntent.isBlank()) { showStatus("请先在对话页输入任务草稿"); return }
        TaskScheduler.scheduleIn(this, draftIntent, RuntimeSettings.read(this).manualReminderDelayMs)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SCHEDULED, true))
        showStatus("已安排当前草稿的定时提醒")
    }

    private fun loadPendingTrigger() {
        PendingTriggerStore.take(this)?.let { task ->
            setResult(RESULT_OK, Intent().putExtra(EXTRA_LOADED_INTENT, task))
            finish()
        } ?: showStatus("没有待处理任务")
    }

    private fun refreshServiceState() {
        floatingVoiceButton.text = if (TarsAccessibilityService.instance?.isFloatingVoiceVisible == true) "停用悬浮语音" else "启用悬浮语音"
    }

    private fun showStatus(message: String) { status.text = message }
    private fun roundedBackground(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply { setColor(fill); setStroke(dp(1), stroke); cornerRadius = dp(8).toFloat() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_DRAFT_INTENT = "draft_intent"
        const val EXTRA_LOADED_INTENT = "loaded_intent"
        const val EXTRA_SCHEDULED = "scheduled"
        private const val SHIZUKU_REQUEST_CODE = 1001
        private const val MICROPHONE_PERMISSION_REQUEST_CODE = 1003
    }
}
