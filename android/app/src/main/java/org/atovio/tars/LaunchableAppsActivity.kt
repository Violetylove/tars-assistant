package org.atovio.tars

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class LaunchableAppsActivity : Activity() {
    private lateinit var appsContainer: LinearLayout
    private lateinit var status: TextView
    private val appChecks = linkedMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "允许启动的应用"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(Color.rgb(248, 250, 252))
        }
        content.addView(header())
        content.addView(sectionTitle("允许启动的应用"))
        content.addView(description())
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionRow.addView(button("刷新应用列表") { refreshApps() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(button("保存允许启动的应用") { saveApps() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        content.addView(actionRow)
        status = TextView(this).apply {
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(0, dp(8), 0, dp(4))
        }
        content.addView(status)
        appsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(appsContainer)
        val scrollView = ScrollView(this).apply {
            clipToPadding = false
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
            addView(content)
        }
        setContentView(scrollView)
        ViewCompat.requestApplyInsets(scrollView)
        refreshApps()
    }

    private fun header(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(Button(this@LaunchableAppsActivity).apply {
            text = "返回"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        addView(TextView(this@LaunchableAppsActivity).apply {
            text = "应用启动"
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

    private fun description(): TextView = TextView(this).apply {
        text = "仅允许 Agent 启动已勾选且仍安装的应用。最多可选择 ${RuntimeSettings.MAX_ALLOWED_LAUNCH_PACKAGES} 个。"
        setTextColor(Color.rgb(71, 85, 105))
        textSize = 14f
        setPadding(0, 0, 0, dp(8))
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        }
    }

    private fun refreshApps() {
        val apps = LaunchableApps.installed(this)
        val installedPackages = apps.mapTo(mutableSetOf()) { it.packageName }
        val saved = RuntimeSettings.allowedLaunchPackages(this)
        val missing = saved - installedPackages
        val selected = saved - missing
        if (missing.isNotEmpty()) RuntimeSettings.saveAllowedLaunchPackages(this, selected)

        appChecks.clear()
        appsContainer.removeAllViews()
        if (apps.isEmpty()) {
            appsContainer.addView(TextView(this).apply {
                text = "未找到可启动的应用"
                setTextColor(Color.rgb(71, 85, 105))
                setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            apps.forEach { app ->
                val check = CheckBox(this).apply {
                    text = "${app.label}\n${app.packageName}"
                    textSize = 15f
                    setPadding(0, dp(4), 0, dp(4))
                    isChecked = app.packageName in selected
                }
                appChecks[app.packageName] = check
                appsContainer.addView(check)
            }
        }
        status.text = if (missing.isEmpty()) {
            "已刷新应用列表，共 ${apps.size} 个可启动应用"
        } else {
            "已移除已卸载的应用：${missing.joinToString("、")}"
        }
    }

    private fun saveApps() {
        val selected = appChecks.filterValues { it.isChecked }.keys
        val installed = LaunchableApps.installed(this).mapTo(mutableSetOf()) { it.packageName }
        val missing = selected - installed
        val valid = selected - missing
        if (valid.size > RuntimeSettings.MAX_ALLOWED_LAUNCH_PACKAGES) {
            status.text = "最多可保存 ${RuntimeSettings.MAX_ALLOWED_LAUNCH_PACKAGES} 个允许启动的应用"
            return
        }
        RuntimeSettings.saveAllowedLaunchPackages(this, valid)
        refreshApps()
        status.text = if (missing.isEmpty()) {
            "已保存 ${valid.size} 个允许启动的应用"
        } else {
            "以下应用已卸载，未保存：${missing.joinToString("、")}"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
