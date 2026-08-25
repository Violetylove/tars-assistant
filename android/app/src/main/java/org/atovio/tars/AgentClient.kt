package org.atovio.tars

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL

class AgentClient(context: android.content.Context) {
    private val appContext = context.applicationContext
    private val settings = RuntimeSettings.read(context.applicationContext)
    private val baseUrl = "http://${RuntimeSettings.toUrlAuthority(settings.agentHost)}:${settings.agentPort}"
    private val endpoint = URI(baseUrl)
    private val isLoopback = RuntimeSettings.isLoopbackHost(settings.agentHost)
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var activeSessionId: String? = null

    init {
        require(endpoint.scheme == "http" && endpoint.host != null && endpoint.port in 1..65_535) {
            "Agent endpoint must be a valid HTTP host and port"
        }
    }

    fun health(): Boolean = request("GET", "/health", null, HEALTH_TIMEOUT_MS).first == 200

    fun cancel() {
        diagnostic(activeSessionId, "cancel_requested active_connection=${activeConnection != null}")
        activeConnection?.disconnect()
    }

    fun run(request: TaskRequest): AgentResponse {
        val (status, body) = request("POST", "/agent/run", request.toJson(), sessionId = request.sessionId)
        diagnostic(request.sessionId, "response_received status=$status body_bytes=${body.toByteArray(Charsets.UTF_8).size}")
        if (status !in 200..299) throw IllegalStateException("Agent HTTP $status: $body")
        diagnostic(request.sessionId, "response_parse_start")
        return try {
            AgentResponse.fromJson(JSONObject(body)).also {
                check(it.sessionId == request.sessionId) { "Agent returned a mismatched session_id" }
                diagnostic(request.sessionId, "response_parse_complete response_session=${it.sessionId} actions=${it.actions.size}")
            }
        } catch (error: Exception) {
            diagnostic(request.sessionId, "response_parse_failed error=${error.javaClass.simpleName}:${error.message.orEmpty().take(160)}")
            throw error
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
        sessionId: String? = null,
    ): Pair<Int, String> {
        val url = URL(baseUrl.trimEnd('/') + path)
        val payloadBytes = payload?.toString()?.toByteArray(Charsets.UTF_8)
        diagnostic(sessionId, "request_start method=$method path=$path payload_bytes=${payloadBytes?.size ?: 0} timeout_ms=$timeoutMs")
        // Loopback must stay direct; a configured remote Agent follows the device network path.
        val conn = ((if (isLoopback) url.openConnection(Proxy.NO_PROXY) else url.openConnection()) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doInput = true
        }
        activeConnection = conn
        activeSessionId = sessionId
        return try {
            if (payloadBytes != null) {
                conn.doOutput = true
                diagnostic(sessionId, "request_body_write_start")
                conn.outputStream.use { it.write(payloadBytes) }
                diagnostic(sessionId, "request_body_write_complete")
            }
            diagnostic(sessionId, "response_code_wait_start")
            val status = conn.responseCode
            diagnostic(sessionId, "response_code_received status=$status")
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            diagnostic(sessionId, "response_body_read_complete body_bytes=${body.toByteArray(Charsets.UTF_8).size}")
            status to body
        } catch (error: Exception) {
            diagnostic(sessionId, "request_failed stage=transport error=${error.javaClass.simpleName}:${error.message.orEmpty().take(160)}")
            throw error
        } finally {
            if (activeConnection === conn) activeConnection = null
            if (activeSessionId == sessionId) activeSessionId = null
            diagnostic(sessionId, "request_cleanup")
            conn.disconnect()
        }
    }

    private fun requestBytes(method: String, path: String, payload: ByteArray, filename: String): Pair<Int, String> {
        val url = URL(baseUrl.trimEnd('/') + path)
        diagnostic(null, "request_start method=$method path=$path payload_bytes=${payload.size} timeout_ms=${settings.modelRequestTimeoutMs}")
        val conn = ((if (isLoopback) url.openConnection(Proxy.NO_PROXY) else url.openConnection()) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = settings.modelRequestTimeoutMs
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setRequestProperty("X-TARS-Log-Filename", filename)
            doInput = true
            doOutput = true
        }
        activeConnection = conn
        return try {
            diagnostic(null, "request_body_write_start")
            conn.outputStream.use { it.write(payload) }
            diagnostic(null, "request_body_write_complete")
            diagnostic(null, "response_code_wait_start")
            val status = conn.responseCode
            diagnostic(null, "response_code_received status=$status")
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            diagnostic(null, "response_body_read_complete body_bytes=${body.toByteArray(Charsets.UTF_8).size}")
            status to body
        } catch (error: Exception) {
            diagnostic(null, "request_failed stage=transport error=${error.javaClass.simpleName}:${error.message.orEmpty().take(160)}")
            throw error
        } finally {
            if (activeConnection === conn) activeConnection = null
            diagnostic(null, "request_cleanup")
            conn.disconnect()
        }
    }

    private fun diagnostic(sessionId: String?, message: String) {
        AndroidLogStore.append(appContext, "session=${sessionId ?: "-"} http $message")
    }

    private companion object {
        const val HEALTH_TIMEOUT_MS = 5_000
    }
}
