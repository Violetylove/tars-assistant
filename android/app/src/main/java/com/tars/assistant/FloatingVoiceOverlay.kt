package com.tars.assistant

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** Accessibility-scoped voice entry. Results remain pending until the user loads and sends them. */
class FloatingVoiceOverlay(private val service: AccessibilityService) {
    private val display = requireNotNull(service.getSystemService(DisplayManager::class.java).getDisplay(0))
    private val overlayContext = service.createDisplayContext(display)
        .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
    private val windowManager = overlayContext.getSystemService(WindowManager::class.java)
    private var bubble: TextView? = null
    private var voice: VoiceIntentCapture? = null

    fun show(): Boolean {
        if (bubble != null) return true
        if (service.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false

        voice = VoiceIntentCapture(
            service,
            onResult = { transcript ->
                PendingTriggerStore.save(service, transcript)
                TriggerNotifier.show(service, "语音任务待处理", transcript)
                service.sendBroadcast(android.content.Intent(NotificationTriggerService.ACTION_PENDING_TRIGGER).setPackage(service.packageName))
            },
            onStatus = { message -> bubble?.text = message },
            deliverPartialResults = false,
        )
        bubble = TextView(overlayContext).apply {
            text = "按住说话"
            setTextColor(Color.WHITE)
            setPadding(28, 18, 28, 18)
            background = GradientDrawable().apply { setColor(Color.rgb(21, 101, 192)); cornerRadius = 48f }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> voice?.start() ?: false
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { voice?.stop(); true }
                    else -> true
                }
            }
        }
        windowManager.addView(bubble, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        return true
    }

    fun hide() {
        bubble?.let(windowManager::removeView)
        bubble = null
        voice?.destroy()
        voice = null
    }

    val isVisible: Boolean get() = bubble != null
}
