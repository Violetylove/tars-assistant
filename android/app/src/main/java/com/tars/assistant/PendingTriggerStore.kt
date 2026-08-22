package com.tars.assistant

import android.content.Context

object PendingTriggerStore {
    private const val PREFS = "pending_trigger"
    private const val KEY_INTENT = "intent"

    fun save(context: Context, intent: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_INTENT, intent).apply()
    }

    fun take(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val intent = prefs.getString(KEY_INTENT, null)
        prefs.edit().remove(KEY_INTENT).apply()
        return intent
    }
}
