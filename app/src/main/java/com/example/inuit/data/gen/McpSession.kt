package com.example.inuit.data.gen

import com.example.inuit.data.AppSettings
import com.example.inuit.data.DebugLog
import com.example.inuit.data.llm.LlmToolSpec
import com.example.inuit.data.llm.McpClient
import com.example.inuit.data.llm.McpConfig
import com.example.inuit.data.llm.McpTool

/**
 * One MCP toolbox per generation/harvest run: parses the configured servers,
 * connects to up to [MAX_SERVERS] of them and exposes their tools under
 * collision-free names. Shared by the personalized generator and the bulk
 * harvester so both speak to the web the same way.
 */
class McpSession(private val s: AppSettings) {

    private val clients = HashMap<String, McpClient>()
    private val toolServer = HashMap<String, String>() // exposed tool name → server

    var toolSpecs: List<LlmToolSpec> = emptyList()
        private set

    val hasTools: Boolean get() = toolSpecs.isNotEmpty()

    /** Connects to the configured HTTP MCP servers; returns true when at
     *  least one tool is available. Failures are logged and skipped. */
    suspend fun connect(maxServers: Int = MAX_SERVERS): Boolean {
        val parsed = McpConfig.parse(s.mcpJson)
        if (parsed.error != null) DebugLog.w(TAG, "MCP JSON invalid: ${parsed.error}")
        if (parsed.skipped.isNotEmpty())
            DebugLog.w(TAG, "MCP skipped (stdio unsupported): ${parsed.skipped.joinToString()}")
        val specs = ArrayList<LlmToolSpec>()
        for (server in parsed.servers.take(maxServers)) {
            try {
                val client = McpClient(server)
                client.initialize()
                val tools: List<McpTool> = client.listTools()
                clients[server.name] = client
                DebugLog.i(TAG, "MCP '${server.name}' ready — tools: ${tools.joinToString { it.name }}")
                for (t in tools) {
                    val uniqueName = if (toolServer.containsKey(t.name)) "${server.name}__${t.name}" else t.name
                    specs.add(LlmToolSpec(uniqueName, t.description, t.parametersJson))
                    toolServer[uniqueName] = server.name
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "MCP server '${server.name}' unavailable (continuing without it)", e)
            }
        }
        toolSpecs = specs
        if (specs.isEmpty()) DebugLog.w(TAG, "no MCP tools available")
        return specs.isNotEmpty()
    }

    /** Executes one tool call by its exposed name; never throws. */
    suspend fun call(name: String, argsJson: String): String {
        val serverName = toolServer[name]
        val client = clients[serverName]
        if (client == null) {
            return "Unknown tool '$name'. Available: ${toolServer.keys.joinToString()}"
        }
        return try {
            val rawName = if (name.contains("__")) name.substringAfter("__") else name
            client.callTool(rawName, argsJson).also {
                DebugLog.i(TAG, "tool '$name' returned ${it.length} chars")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "tool '$name' failed", e)
            "tool error: ${e.message}"
        }
    }

    companion object {
        private const val TAG = "MCP"
        private const val MAX_SERVERS = 3
    }
}
