package org.atovio.tars

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LaunchableApp(
    val label: String,
    val packageName: String,
)

data class TaskRequest(
    val intent: String,
    val app: String? = null,
    val activity: String? = null,
    val nodes: List<org.json.JSONObject> = emptyList(),
    val windowLayers: String? = null,
    val sessionId: String = UUID.randomUUID().toString(),
    val history: JSONArray = JSONArray(),
    val observationNote: String? = null,
    val launchableApps: List<LaunchableApp> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol_version", "1.0")
        put("session_id", sessionId)
        put("intent", intent)
        app?.let { put("app", it) }
        activity?.let { put("activity", it) }
        if (nodes.isNotEmpty()) put("nodes", JSONArray(nodes))
        windowLayers?.takeIf { it.isNotBlank() }?.let { put("window_layers", it) }
        observationNote?.takeIf { it.isNotBlank() }?.let { put("observation_note", it) }
        put("history", history)
        put("launchable_apps", JSONArray().apply {
            launchableApps.forEach { app ->
                put(JSONObject().put("label", app.label).put("package_name", app.packageName))
            }
        })
    }
}

data class AgentAction(
    val type: String,
    val targetNodeId: Int? = null,
    val text: String? = null,
    val x1: Float? = null,
    val y1: Float? = null,
    val x2: Float? = null,
    val y2: Float? = null,
    val durationMs: Int? = null,
    val ms: Int? = null,
    val packageName: String? = null,
    val requiresConfirmation: Boolean = false,
)

data class AgentResponse(
    val protocolVersion: String,
    val sessionId: String,
    val done: Boolean,
    val reply: String,
    val actions: List<AgentAction>,
    val needObservation: Boolean,
) {
    companion object {
        fun fromJson(json: JSONObject): AgentResponse {
            val protocolVersion = json.getString("protocol_version")
            require(protocolVersion == PROTOCOL_VERSION) { "Unsupported protocol version: $protocolVersion" }
            val actions = mutableListOf<AgentAction>()
            val array = json.optJSONArray("actions") ?: JSONArray()
            for (i in 0 until array.length()) {
                val a = array.getJSONObject(i)
                actions += AgentAction(
                    type = a.getString("type"),
                    targetNodeId = if (a.has("target_node_id")) a.getInt("target_node_id") else null,
                    text = a.optString("text").takeIf { a.has("text") },
                    x1 = a.optDouble("x1").toFloatOrNull(a.has("x1")),
                    y1 = a.optDouble("y1").toFloatOrNull(a.has("y1")),
                    x2 = a.optDouble("x2").toFloatOrNull(a.has("x2")),
                    y2 = a.optDouble("y2").toFloatOrNull(a.has("y2")),
                    durationMs = if (a.has("duration_ms")) a.getInt("duration_ms") else null,
                    ms = if (a.has("ms")) a.getInt("ms") else null,
                    packageName = a.optString("package_name").takeIf { a.has("package_name") },
                    requiresConfirmation = a.optBoolean("requires_confirmation", false),
                )
            }
            return AgentResponse(protocolVersion, json.getString("session_id"), json.getBoolean("done"),
                json.optString("reply"), actions, json.optBoolean("need_observation"))
        }
    }
}

const val PROTOCOL_VERSION = "1.0"

private fun Double.toFloatOrNull(present: Boolean): Float? = if (present) toFloat() else null
