package com.tars.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Captures a notification as a pending, user-reviewed task; it never calls the Agent itself. */
class NotificationTriggerService : NotificationListenerService() {
    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (notification.packageName == packageName) return
        val extras = notification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val summary = listOf(title, text).filter { it.isNotBlank() }.joinToString("：")
        val taskIntent = "处理来自 ${notification.packageName} 的通知：$summary"
        PendingTriggerStore.save(this, taskIntent)
        TriggerNotifier.show(this, "通知任务待处理", taskIntent)
        sendBroadcast(android.content.Intent(ACTION_PENDING_TRIGGER))
    }

    companion object { const val ACTION_PENDING_TRIGGER = "com.tars.assistant.PENDING_TRIGGER" }
}
