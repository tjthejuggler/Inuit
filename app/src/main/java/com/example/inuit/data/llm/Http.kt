package com.example.inuit.data.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpException(val code: Int, message: String) : IOException(message) {
    override fun toString(): String = "HTTP $code: ${message?.take(400)}"
}

data class HttpResponse(val code: Int, val body: String, val headers: Map<String, List<String>>)

/** Minimal HttpURLConnection wrapper — no external dependencies. */
object Http {

    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 300_000
    ): HttpResponse = withContext(Dispatchers.IO) {
        request("POST", url, headers, body, connectTimeoutMs, readTimeoutMs)
    }

    suspend fun get(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 60_000
    ): HttpResponse = withContext(Dispatchers.IO) {
        request("GET", url, headers, null, connectTimeoutMs, readTimeoutMs)
    }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
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
            conn.disconnect()
        }
    }
}
