package org.atovio.tars

import android.content.Context

/** User-adjustable runtime values. Security-sensitive allowlists remain hard-coded elsewhere. */
object RuntimeSettings {
    private const val PREFS = "runtime_settings"
    private const val KEY_MAX_ROUNDS = "max_observation_rounds"
    private const val KEY_OBSERVATION_TIMEOUT = "observation_timeout_ms"
    private const val KEY_HOST = "agent_loopback_host"
    private const val KEY_PORT = "agent_loopback_port"
    private const val KEY_MODEL_TIMEOUT = "model_request_timeout_ms"
    private const val KEY_REMINDER_DELAY = "manual_reminder_delay_ms"
    private const val KEY_NEW_APP_GRACE = "new_app_grace_ms"

    data class Values(
        val maxObservationRounds: Int = DEFAULT_MAX_OBSERVATION_ROUNDS,
        val observationTimeoutMs: Long = DEFAULT_OBSERVATION_TIMEOUT_MS,
        val agentHost: String = DEFAULT_AGENT_HOST,
        val agentPort: Int = DEFAULT_AGENT_PORT,
        val modelRequestTimeoutMs: Int = DEFAULT_MODEL_REQUEST_TIMEOUT_MS,
        val manualReminderDelayMs: Long = DEFAULT_MANUAL_REMINDER_DELAY_MS,
        val newAppGraceMs: Long = DEFAULT_NEW_APP_GRACE_MS,
    )

    fun read(context: Context): Values {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Values(
            maxObservationRounds = safeInt(prefs, KEY_MAX_ROUNDS, DEFAULT_MAX_OBSERVATION_ROUNDS, 1..20),
            observationTimeoutMs = safeLong(prefs, KEY_OBSERVATION_TIMEOUT, DEFAULT_OBSERVATION_TIMEOUT_MS, 2_000L..10_000L),
            agentHost = prefs.getString(KEY_HOST, DEFAULT_AGENT_HOST).takeIf { it in LOOPBACK_HOSTS } ?: DEFAULT_AGENT_HOST,
            agentPort = safeInt(prefs, KEY_PORT, DEFAULT_AGENT_PORT, 1..65_535),
            modelRequestTimeoutMs = safeInt(prefs, KEY_MODEL_TIMEOUT, DEFAULT_MODEL_REQUEST_TIMEOUT_MS, 60_000..600_000),
            manualReminderDelayMs = safeLong(prefs, KEY_REMINDER_DELAY, DEFAULT_MANUAL_REMINDER_DELAY_MS, 60_000L..604_800_000L),
            newAppGraceMs = safeLong(prefs, KEY_NEW_APP_GRACE, DEFAULT_NEW_APP_GRACE_MS, 0L..10_000L),
        )
    }

    /** Returns a user-facing validation message; values are only written when every field is safe. */
    fun save(context: Context, values: Values): String? {
        validate(values)?.let { return it }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_ROUNDS, values.maxObservationRounds)
            .putLong(KEY_OBSERVATION_TIMEOUT, values.observationTimeoutMs)
            .putString(KEY_HOST, values.agentHost)
            .putInt(KEY_PORT, values.agentPort)
            .putInt(KEY_MODEL_TIMEOUT, values.modelRequestTimeoutMs)
            .putLong(KEY_REMINDER_DELAY, values.manualReminderDelayMs)
            .putLong(KEY_NEW_APP_GRACE, values.newAppGraceMs)
            .apply()
        return null
    }

    fun restoreDefaults(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun validate(values: Values): String? = when {
        values.maxObservationRounds !in 1..20 -> "观察轮数必须在 1 到 20 之间"
        values.observationTimeoutMs !in 2_000L..10_000L -> "界面观察超时必须在 2000 到 10000 毫秒之间"
        values.agentHost !in LOOPBACK_HOSTS -> "Agent 地址只能是 127.0.0.1、::1 或 [::1]"
        values.agentPort !in 1..65_535 -> "Agent 端口必须在 1 到 65535 之间"
        values.modelRequestTimeoutMs !in 60_000..600_000 -> "模型请求超时必须在 60000 到 600000 毫秒之间"
        values.manualReminderDelayMs !in 60_000L..604_800_000L -> "提醒延时必须在 1 分钟到 7 天之间"
        values.newAppGraceMs !in 0L..10_000L -> "新应用宽限必须在 0 到 10000 毫秒之间"
        else -> null
    }

    private fun safeInt(prefs: android.content.SharedPreferences, key: String, fallback: Int, range: IntRange): Int =
        try { prefs.getInt(key, fallback).takeIf { it in range } ?: fallback } catch (_: ClassCastException) { fallback }

    private fun safeLong(prefs: android.content.SharedPreferences, key: String, fallback: Long, range: LongRange): Long =
        try { prefs.getLong(key, fallback).takeIf { it in range } ?: fallback } catch (_: ClassCastException) { fallback }

    const val DEFAULT_MAX_OBSERVATION_ROUNDS = 12
    const val DEFAULT_OBSERVATION_TIMEOUT_MS = 5_000L
    const val DEFAULT_AGENT_HOST = "127.0.0.1"
    const val DEFAULT_AGENT_PORT = 8080
    const val DEFAULT_MODEL_REQUEST_TIMEOUT_MS = 210_000
    const val DEFAULT_MANUAL_REMINDER_DELAY_MS = 15 * 60 * 1000L
    const val DEFAULT_NEW_APP_GRACE_MS = 4_000L
    val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "[::1]")
}
