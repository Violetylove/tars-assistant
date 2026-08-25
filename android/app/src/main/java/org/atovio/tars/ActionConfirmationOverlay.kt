package org.atovio.tars

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch

/** Confirmation UI hosted by the accessibility layer, above the current foreground app. */
class ActionConfirmationOverlay(private val service: AccessibilityService) {
    private val display = requireNotNull(service.getSystemService(DisplayManager::class.java).getDisplay(0))
    private val overlayContext = service.createDisplayContext(display).let { displayContext ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        } else {
            service
        }
    }
    private val windowManager = overlayContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var pending: Pending? = null

    fun confirm(message: String): Boolean {
        val request = Pending(CountDownLatch(1))
        synchronized(this) {
            if (pending != null) return false
            pending = request
        }
        mainHandler.post { show(request, message) }
        return try {
            request.latch.await()
            request.approved
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            mainHandler.post { complete(request, approved = false) }
            false
        } finally {
            mainHandler.post { complete(request, approved = request.approved) }
        }
    }

    fun dismiss() {
        pending?.let { request -> mainHandler.post { complete(request, approved = false) } }
    }

    private fun show(request: Pending, message: String) {
        if (pending !== request) return
        val root = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
            background = roundedBackground(Color.WHITE, Color.rgb(203, 213, 225), dp(16).toFloat())
            elevation = dp(8).toFloat()
        }
        root.addView(TextView(overlayContext).apply {
            text = "TARS 操作确认"
            textSize = 19f
            setTextColor(Color.rgb(15, 23, 42))
        })
        root.addView(TextView(overlayContext).apply {
            text = message
            textSize = 16f
            setTextColor(Color.rgb(51, 65, 85))
            setPadding(0, dp(12), 0, dp(12))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val buttons = LinearLayout(overlayContext).apply {
            gravity = Gravity.END
        }
        buttons.addView(Button(overlayContext).apply {
            text = "取消"
            isAllCaps = false
            setOnClickListener { complete(request, approved = false) }
        })
        buttons.addView(Button(overlayContext).apply {
            text = "确认"
            isAllCaps = false
            setOnClickListener { complete(request, approved = true) }
        })
        root.addView(buttons)
        request.view = root
        try {
            windowManager.addView(root, WindowManager.LayoutParams(
                dp(360),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
                dimAmount = 0.45f
            })
        } catch (_: RuntimeException) {
            complete(request, approved = false)
        }
    }

    private fun complete(request: Pending, approved: Boolean) {
        if (pending !== request) return
        request.approved = approved
        request.view?.let { view ->
            try { windowManager.removeView(view) } catch (_: IllegalArgumentException) { }
        }
        request.view = null
        pending = null
        request.latch.countDown()
    }

    private fun roundedBackground(fill: Int, stroke: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = radius
        }

    private fun dp(value: Int): Int = (value * overlayContext.resources.displayMetrics.density).toInt()

    private data class Pending(
        val latch: CountDownLatch,
        @Volatile var approved: Boolean = false,
        @Volatile var view: View? = null,
    )
}
