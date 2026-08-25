package org.atovio.tars

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL

class AgentClient(context: android.content.Context) {
    private val settings = RuntimeSettings.read(context.applicationContext)
    private val baseUrl = "http://${RuntimeSettings.toUrlAuthority(settings.agentHost)}:${settings.agentPort}"
    private val endpoint = URI(baseUrl)
    private val isLoopback = RuntimeSettings.isLoopbackHost(settings.agentHost)
    @Volatile private var activeConnection: HttpURLConnection? = null

    init {
        require(endpoint.scheme == "http" && endpoint.host != null && endpoint.port in 1..65_535) {
            "Agent endpoint must be a valid HTTP host and port"
        }
    }

    fun health(): Boolean = request("GET", "/health", null, HEALTH_TIMEOUT_MS).first == 200

    fun cancel() {
        activeConnection?.disconnect()
    }

    fun run(request: TaskRequest): AgentResponse {
        val (status, body) = request("POST", "/agent/run", request.toJson())
        if (status !in 200..299) throw IllegalStateException("Agent HTTP $status: $body")
        return AgentResponse.fromJson(JSONObject(body)).also {
            check(it.sessionId == request.sessionId) { "Agent returned a mismatched session_id" }
        }
    }

    fun uploadAndroidLog(file: java.io.File): String {
        require(file.isFile) { "Android 日志文件不存在" }
        val (status, body) = requestBytes("POST", "/logs/android", file.readBytes(), file.name)
        if (status !in 200..299) throw IllegalStateException("Agent HTTP $status: $body")
        return body
    }

    private fun request(
        method: String,
        path: String,
        payload: JSONObject?,
        timeoutMs: Int = settings.modelRequestTimeoutMs,
    ): Pair<Int, String> {
        val url = URL(baseUrl.trimEnd('/') + path)
        // Loopback must stay direct; a configured remote Agent follows the device network path.
        val conn = ((if (isLoopback) url.openConnection(Proxy.NO_PROXY) else url.openConnection()) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doInput = true
            if (payload != null) {
                doOutput = true
                outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        activeConnection = conn
        return try {
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            if (activeConnection === conn) activeConnection = null
            conn.disconnect()
        }
    }

    private fun requestBytes(method: String, path: String, payload: ByteArray, filename: String): Pair<Int, String> {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = ((if (isLoopback) url.openConnection(Proxy.NO_PROXY) else url.openConnection()) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = settings.modelRequestTimeoutMs
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setRequestProperty("X-TARS-Log-Filename", filename)
            doInput = true
            doOutput = true
            outputStream.use { it.write(payload) }
        }
        activeConnection = conn
        return try {
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            if (activeConnection === conn) activeConnection = null
            conn.disconnect()
        }
    }

    private companion object {
        const val HEALTH_TIMEOUT_MS = 5_000
    }
}
