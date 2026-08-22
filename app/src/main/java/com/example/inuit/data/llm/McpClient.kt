package com.example.inuit.data.llm

import com.example.inuit.data.DebugLog
import org.json.JSONArray
import org.json.JSONObject

data class McpServerConfig(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

data class McpTool(
    val server: String,
    val name: String,
    val description: String,
    val parametersJson: String
)

data class McpParseResult(
    val servers: List<McpServerConfig>,
    /** Names of stdio/unsupported servers that were skipped. */
    val skipped: List<String>,
    val error: String? = null
)

/**
 * Parses an MCP servers JSON in the common desktop-client shape:
 * `{"mcpServers": { name: { "type": "streamable-http", "url": ..., "headers": {...} } }}`
 * Only remote HTTP servers are usable on Android; stdio entries are skipped.
 */
object McpConfig {
    fun parse(json: String): McpParseResult {
        try {
            val root = JSONObject(json.trim())
            val serversObj = root.optJSONObject("mcpServers") ?: root.optJSONObject("servers") ?: root
            val servers = ArrayList<McpServerConfig>()
            val skipped = ArrayList<String>()
            for (name in serversObj.keys()) {
                val o = serversObj.optJSONObject(name) ?: continue
                val url = o.optString("url").ifBlank { null }
                if (url == null) {
                    skipped.add(name)
                    continue
                }
                val headers = HashMap<String, String>()
                val h = o.optJSONObject("headers")
                if (h != null) for (k in h.keys()) headers[k] = h.optString(k)
                servers.add(McpServerConfig(name, url, headers))
            }
            return McpParseResult(servers, skipped)
        } catch (e: Exception) {
            return McpParseResult(emptyList(), emptyList(), e.message ?: "invalid JSON")
        }
    }
}

/**
 * Minimal MCP client for the Streamable HTTP transport (JSON-RPC 2.0 over
 * POST; responses may be plain JSON or SSE-framed). Handles initialize
 * handshake, tools/list and tools/call.
 */
class McpClient(private val cfg: McpServerConfig) {

    private var sessionId: String? = null
    private var nextId = 1

    suspend fun initialize() {
        val params = JSONObject().apply {
            put("protocolVersion", "2025-03-26")
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().apply {
                put("name", "inuit")
                put("version", "1.0")
            })
        }
        val resp = rpc("initialize", params)
        val header = resp.headers.entries.firstOrNull {
            it.key.equals("mcp-session-id", ignoreCase = true) && it.value.isNotEmpty()
        }
        sessionId = header?.value?.first()
        DebugLog.i("MCP", "initialized '${cfg.name}' (session=${sessionId?.take(8) ?: "none"})")
        // Fire-and-forget initialized notification (202, empty body is fine).
        try {
            Http.post(
                cfg.url,
                baseHeaders(),
                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", "notifications/initialized")
                }.toString()
            )
        } catch (_: Exception) {
            // Some servers don't require it; ignore.
        }
    }

    suspend fun listTools(): List<McpTool> {
        val resp = rpc("tools/list", JSONObject())
        val tools = ArrayList<McpTool>()
        val result = resp.json?.optJSONObject("result")
        if (result == null) {
            DebugLog.w("MCP", "'${cfg.name}' tools/list returned no result")
            return tools
        }
        val arr = result.optJSONArray("tools") ?: return tools
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val name = t.optString("name")
            if (name.isBlank()) continue
            tools.add(
                McpTool(
                    server = cfg.name,
                    name = name,
                    description = t.optString("description", ""),
                    parametersJson = t.optJSONObject("inputSchema")?.toString()
                        ?: """{"type":"object","properties":{}}"""
                )
            )
        }
        return tools
    }

    /** Calls a tool; returns concatenated text content (truncated for context safety). */
    suspend fun callTool(name: String, argsJson: String): String {
        val params = JSONObject().apply {
            put("name", name)
            put("arguments", JSONObject(argsJson.ifBlank { "{}" }))
        }
        val resp = rpc("tools/call", params)
        val result = resp.json?.optJSONObject("result")
            ?: return (resp.json?.optJSONObject("error")?.optString("message") ?: "tool error").also {
                DebugLog.w("MCP", "'${cfg.name}' call '$name' error: $it")
            }
        if (result.optBoolean("isError", false)) {
            val contentText = contentToText(result.optJSONArray("content"))
            return "tool error: ${contentText.ifBlank { "unknown" }}"
        }
        return contentToText(result.optJSONArray("content")).ifBlank { "(empty result)" }
    }

    private fun contentToText(content: JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            when (c.optString("type")) {
                "text" -> sb.append(c.optString("text")).append('\n')
                "image" -> sb.append("[image omitted]").append('\n')
                "resource" -> {
                    val r = c.optJSONObject("resource")
                    if (r != null) sb.append(r.optString("text", "[resource]")).append('\n')
                }
            }
        }
        val text = sb.toString().trim()
        return if (text.length > 8000) text.take(8000) + "\n…[truncated]" else text
    }

    // ── JSON-RPC plumbing ────────────────────────────────────────────────

    private class RpcResult(val json: JSONObject?, val headers: Map<String, List<String>>)

    private fun baseHeaders(): MutableMap<String, String> {
        val h = HashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = "application/json, text/event-stream"
        for ((k, v) in cfg.headers) h[k] = v
        sessionId?.let { h["Mcp-Session-Id"] = it }
        return h
    }

    private suspend fun rpc(method: String, params: JSONObject): RpcResult {
        val id = nextId++
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val resp = Http.post(cfg.url, baseHeaders(), body.toString())
        if (resp.code !in 200..299) {
            DebugLog.e("MCP", "'${cfg.name}' $method HTTP ${resp.code}: ${resp.body.take(200)}")
            throw LlmException("MCP ${cfg.name} $method HTTP ${resp.code}: ${resp.body.take(200)}")
        }
        val json = parseMaybeSse(resp.body)
        if (json?.opt("error") != null) {
            val e = json.optJSONObject("error")
            throw LlmException("MCP ${cfg.name} $method: ${e?.optString("message") ?: e.toString()}")
        }
        return RpcResult(json, resp.headers)
    }

    /** Handles both plain JSON and SSE-framed responses. */
    private fun parseMaybeSse(body: String): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{")) {
            // SSE: collect all data: lines, return the last valid JSON object.
            var last: JSONObject? = null
            for (line in trimmed.lineSequence()) {
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") continue
                try {
                    last = JSONObject(data)
                } catch (_: Exception) {
                    // partial frame — ignore
                }
            }
            return last
        }
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
    }
}
