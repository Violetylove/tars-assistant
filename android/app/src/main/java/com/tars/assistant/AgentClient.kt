package com.tars.assistant

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class AgentClient(private val baseUrl: String = "http://127.0.0.1:8080") {
    init {
        val endpoint = URI(baseUrl)
        require(endpoint.scheme == "http" && endpoint.host in LOOPBACK_HOSTS && endpoint.port == AGENT_PORT) {
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
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 120_000
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
        private const val AGENT_PORT = 8080
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "[::1]")
    }
}
