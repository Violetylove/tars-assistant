package com.tars.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object TaskScheduler {
    private const val ACTION_SCHEDULED_TASK = "com.tars.assistant.SCHEDULED_TASK"
    private const val EXTRA_INTENT = "task_intent"

    fun scheduleIn(context: Context, taskIntent: String, delayMs: Long) {
        require(taskIntent.isNotBlank()) { "任务意图不能为空" }
        val pending = PendingIntent.getBroadcast(
            context,
            taskIntent.hashCode(),
            Intent(context, ScheduledTaskReceiver::class.java).setAction(ACTION_SCHEDULED_TASK).putExtra(EXTRA_INTENT, taskIntent),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pending)
    }

    internal fun readIntent(intent: Intent): String? =
        intent.takeIf { it.action == ACTION_SCHEDULED_TASK }?.getStringExtra(EXTRA_INTENT)?.trim()?.takeIf { it.isNotEmpty() }
}

class ScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TaskScheduler.readIntent(intent)?.let { taskIntent ->
            PendingTriggerStore.save(context, taskIntent)
            TriggerNotifier.show(context, "定时任务待处理", taskIntent)
            context.sendBroadcast(Intent(NotificationTriggerService.ACTION_PENDING_TRIGGER))
        }
    }
}
