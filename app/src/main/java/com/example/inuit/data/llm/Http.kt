package com.example.inuit.data.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message) {
    override fun toString(): String = "HTTP $code: ${message?.take(400)}"
}

data class HttpResponse(val code: Int, val body: String, val headers: Map<String, List<String>>)

/** Minimal HttpURLConnection wrapper — no external dependencies. */
object Http {

    /** Shared daemon scheduler for connection deadlines (see [request]). */
    private val watchdog =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "inuit-http-watchdog").apply { isDaemon = true }
        }

    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 300_000,
        totalTimeoutMs: Long = 420_000
    ): HttpResponse = withContext(Dispatchers.IO) {
        request("POST", url, headers, body, connectTimeoutMs, readTimeoutMs, totalTimeoutMs)
    }

    suspend fun get(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 60_000,
        totalTimeoutMs: Long = 180_000
    ): HttpResponse = withContext(Dispatchers.IO) {
        request("GET", url, headers, null, connectTimeoutMs, readTimeoutMs, totalTimeoutMs)
    }

    /**
     * Streaming POST for SSE chat completions: every received line is handed
     * to [onLine] the moment it arrives. This is the fix for reasoning models
     * (GLM-4.5/5, R1, …) on non-streaming endpoints: the server sends ZERO
     * bytes until the whole completion is done, so a 300 s read timeout fires
     * on perfectly healthy calls that simply think for five minutes. With
     * streaming, tokens (including reasoning deltas) trickle out
     * continuously — [idleTimeoutMs] then only fires when the stream truly
     * stalls between chunks, and [totalTimeoutMs] stays the hard watchdog
     * deadline. The full body is still returned for error reporting.
     */
    suspend fun postStream(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int = 20_000,
        idleTimeoutMs: Int = 180_000,
        totalTimeoutMs: Long = 480_000,
        onLine: (String) -> Unit
    ): HttpResponse = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        val deadline = watchdog.schedule({ conn.disconnect() }, totalTimeoutMs, TimeUnit.MILLISECONDS)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = idleTimeoutMs // max gap BETWEEN lines, not total
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "text/event-stream")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val sb = StringBuilder()
            stream?.bufferedReader(Charsets.UTF_8)?.use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    sb.append(line).append('\n')
                    onLine(line)
                }
            }
            HttpResponse(code, sb.toString(), conn.headerFields ?: emptyMap())
        } finally {
            deadline.cancel(false)
            conn.disconnect()
        }
    }

    /**
     * [readTimeoutMs] only fires when NO bytes arrive at all; a drip-fed or
     * wedged response can outlive it indefinitely. [totalTimeoutMs] is a hard
     * deadline enforced by a watchdog that disconnects the connection — the
     * blocked read then throws immediately and the caller's retry machinery
     * takes over.
     */
    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        totalTimeoutMs: Long
    ): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        val deadline = watchdog.schedule({ conn.disconnect() }, totalTimeoutMs, TimeUnit.MILLISECONDS)
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResponse(code, text, conn.headerFields ?: emptyMap())
        } finally {
            deadline.cancel(false)
            conn.disconnect()
        }
    }
}
