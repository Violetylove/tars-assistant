package org.atovio.tars

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL

class AgentClient(context: android.content.Context) {
    private val settings = RuntimeSettings.read(context.applicationContext)
    private val baseUrl = "http://${settings.agentHost.let { if (it == "::1") "[$it]" else it }}:${settings.agentPort}"

    init {
        val endpoint = URI(baseUrl)
        require(endpoint.scheme == "http" && endpoint.host in LOOPBACK_HOSTS && endpoint.port in 1..65_535) {
            "Agent endpoint must be the local HTTP loopback service"
        }
    }

    fun health(): Boolean = request("GET", "/health", null).first == 200

    fun run(request: TaskRequest): AgentResponse {
        val (status, body) = request("POST", "/agent/run", request.toJson())
        if (status !in 200..299) throw IllegalStateException("Agent HTTP $status: $body")
        return AgentResponse.fromJson(JSONObject(body)).also {
            check(it.sessionId == request.sessionId) { "Agent returned a mismatched session_id" }
        }
    }

    private fun request(method: String, path: String, payload: JSONObject?): Pair<Int, String> {
        // The Android-wide proxy may be needed by Gmail or the cloud model, but the
        // App-to-Agent contract is always same-device loopback and must stay direct.
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = settings.modelRequestTimeoutMs
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doInput = true
            if (payload != null) {
                doOutput = true
                outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { conn.disconnect() }
    }

    companion object {
        private val LOOPBACK_HOSTS = RuntimeSettings.LOOPBACK_HOSTS
    }
}
