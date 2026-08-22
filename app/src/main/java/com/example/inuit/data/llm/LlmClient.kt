package com.example.inuit.data.llm

import com.example.inuit.data.DebugLog
import org.json.JSONArray
import org.json.JSONObject

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

data class LlmToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema (as a JSON string) for the tool arguments. */
    val parametersJson: String
)

data class LlmToolCall(
    val id: String,
    val name: String,
    /** Raw JSON string of the arguments object. */
    val argumentsJson: String
)

data class LlmMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null
) {
    companion object {
        fun system(text: String) = LlmMessage("system", text)
        fun user(text: String) = LlmMessage("user", text)
        fun assistant(text: String?, toolCalls: List<LlmToolCall> = emptyList()) =
            LlmMessage("assistant", text, toolCalls)
        fun tool(toolCallId: String, name: String, content: String) =
            LlmMessage("tool", content, toolCallId = toolCallId, name = name)
    }
}

class LlmException(message: String) : Exception(message)

/**
 * OpenAI-compatible chat completions client (works with OpenAI, OpenRouter,
 * z.ai, local llama.cpp/ollama gateways, etc.).
 *
 * Hardened for reasoning/thinking models (GLM-4.5+, GLM-5, DeepSeek-R1, …):
 * those spend tokens on internal reasoning BEFORE writing content, so a small
 * max_tokens can exhaust the budget on thinking alone and return an EMPTY
 * content with finish_reason "length". The client detects this and retries
 * with a doubled budget; every request/response is logged to [DebugLog].
 */
class LlmClient {

    /** One assistant turn; may carry tool_calls instead of final content. */
    suspend fun chat(
        cfg: LlmConfig,
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec> = emptyList(),
        temperature: Float = 0.7f,
        maxTokens: Int = 16_000,
        allowToolRetry: Boolean = true,
        disableThinking: Boolean = false,
        allowEmptyRetry: Boolean = true
    ): LlmMessage {
        val started = System.currentTimeMillis()
        val url = normalizeChatUrl(cfg.baseUrl)
        DebugLog.i(TAG, "→ POST ${url.substringAfter("//")} model=${cfg.model} msgs=${messages.size} " +
            "tools=${tools.size} maxTokens=$maxTokens temp=$temperature" +
            if (disableThinking) " thinking=off" else "")

        val body = buildBody(cfg, messages, tools, temperature, maxTokens, disableThinking)
        val headers = mutableMapOf<String, String>()
        if (cfg.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${cfg.apiKey}"
        val resp = try {
            Http.post(url, headers, body)
        } catch (e: Exception) {
            DebugLog.e(TAG, "network failure after ${System.currentTimeMillis() - started}ms", e)
            throw e
        }
        val latency = System.currentTimeMillis() - started

        if (resp.code == 400 && allowToolRetry && tools.isNotEmpty() &&
            resp.body.contains("tool", ignoreCase = true)
        ) {
            // Provider rejected the tools array — retry once without tools.
            DebugLog.w(TAG, "HTTP 400 mentions tools — retrying without tools: ${resp.body.take(200)}")
            return chat(cfg, messages, emptyList(), temperature, maxTokens, allowToolRetry = false)
        }
        if (resp.code !in 200..299) {
            DebugLog.e(TAG, "HTTP ${resp.code} in ${latency}ms: ${resp.body.take(400)}")
            throw LlmException("LLM error ${resp.code}: ${resp.body.take(300)}")
        }

        val root = try {
            JSONObject(resp.body)
        } catch (e: Exception) {
            DebugLog.e(TAG, "non-JSON response in ${latency}ms: ${resp.body.take(300)}")
            throw LlmException("LLM returned non-JSON (HTTP ${resp.code}): ${resp.body.take(200)}")
        }
        val err = root.optJSONObject("error")
        if (err != null) {
            val m = err.optString("message", err.toString()).take(300)
            DebugLog.e(TAG, "API error object in ${latency}ms: $m")
            throw LlmException("LLM error: $m")
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val msg = choice?.optJSONObject("message")
            ?: throw LlmException("No message in response: ${resp.body.take(200)}")
        val finish = choice.optString("finish_reason", "?")
        val content = msg.optString("content").ifBlank { null }
        val reasoningLen = msg.optString("reasoning_content").length
        val usage = root.optJSONObject("usage")
        val usageStr = if (usage != null) {
            "completion=${usage.optInt("completion_tokens")} prompt=${usage.optInt("prompt_tokens")}" +
                " reasoning=${usage.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens") ?: 0}"
        } else "usage=?"

        val calls = ArrayList<LlmToolCall>()
        val rawCalls = msg.optJSONArray("tool_calls")
        if (rawCalls != null) {
            for (i in 0 until rawCalls.length()) {
                val c = rawCalls.optJSONObject(i) ?: continue
                val fn = c.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                if (name.isNotBlank()) {
                    calls.add(LlmToolCall(c.optString("id", "call_$i"), name, fn.optString("arguments", "{}")))
                }
            }
        }

        DebugLog.i(TAG, "← ${latency}ms finish=$finish content=${content?.length ?: 0}ch " +
            "reasoning=${reasoningLen}ch toolCalls=${calls.size} $usageStr")

        if (content == null && calls.isEmpty()) {
            // Signature of a thinking model that exhausted max_tokens on reasoning.
            if (finish == "length" && maxTokens < MAX_TOKENS_CEILING && allowEmptyRetry) {
                val bumped = minOf(maxTokens * 2, MAX_TOKENS_CEILING)
                DebugLog.w(TAG, "empty content with finish=length (reasoning burned the budget) — " +
                    "retrying with maxTokens=$bumped")
                return chat(
                    cfg, messages, tools, temperature, bumped,
                    allowToolRetry, disableThinking, allowEmptyRetry = false
                )
            }
            val hint = if (reasoningLen > 0 || finish == "length")
                " The model spent its whole token budget on internal reasoning. " +
                    "Try enabling 'Disable deep thinking' in Settings (GLM models) or a higher limit."
            else ""
            val m = "Model returned no content (finish_reason=$finish, reasoning=${reasoningLen}ch).$hint"
            DebugLog.e(TAG, m)
            throw LlmException(m)
        }
        return LlmMessage("assistant", content, calls)
    }

    /** GET /models — used by the Settings "test connection" button. */
    suspend fun listModels(cfg: LlmConfig): List<String> {
        val headers = mutableMapOf<String, String>()
        if (cfg.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${cfg.apiKey}"
        val resp = Http.get(normalizeModelsUrl(cfg.baseUrl), headers)
        if (resp.code !in 200..299) {
            DebugLog.e(TAG, "listModels HTTP ${resp.code}: ${resp.body.take(300)}")
            throw LlmException("HTTP ${resp.code}: ${resp.body.take(200)}")
        }
        val arr = JSONObject(resp.body).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id") ?: continue
            if (id.isNotBlank()) out.add(id)
        }
        DebugLog.i(TAG, "listModels OK — ${out.size} models")
        return out
    }

    private fun buildBody(
        cfg: LlmConfig,
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        temperature: Float,
        maxTokens: Int,
        disableThinking: Boolean = false
    ): String {
        val msgs = JSONArray()
        for (m in messages) {
            val o = JSONObject()
            o.put("role", m.role)
            if (m.content != null) o.put("content", m.content) else o.put("content", JSONObject.NULL)
            if (m.name != null) o.put("name", m.name)
            if (m.toolCallId != null) o.put("tool_call_id", m.toolCallId)
            if (m.toolCalls.isNotEmpty()) {
                val calls = JSONArray()
                for (c in m.toolCalls) {
                    calls.put(JSONObject().apply {
                        put("id", c.id)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", c.name)
                            put("arguments", c.argumentsJson)
                        })
                    })
                }
                o.put("tool_calls", calls)
            }
            msgs.put(o)
        }
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("messages", msgs)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)
        }
        if (disableThinking) {
            // z.ai / GLM extension: skip internal reasoning entirely.
            body.put("thinking", JSONObject().put("type", "disabled"))
        }
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            for (t in tools) {
                arr.put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", JSONObject(t.parametersJson))
                    })
                })
            }
            body.put("tools", arr)
            body.put("tool_choice", "auto")
        }
        return body.toString()
    }

    companion object {
        private const val TAG = "LLM"
        private const val MAX_TOKENS_CEILING = 32_000

        /** tail-style base URL normalization. */
        fun normalizeChatUrl(base: String): String {
            val t = base.trim().trimEnd('/')
            return when {
                t.isEmpty() -> t
                t.endsWith("/chat/completions") -> t
                Regex("/v\\d+[a-z]*$").containsMatchIn(t) -> "$t/chat/completions"
                else -> "$t/v1/chat/completions"
            }
        }

        fun normalizeModelsUrl(base: String): String {
            val t = base.trim().trimEnd('/')
            return when {
                t.isEmpty() -> t
                t.endsWith("/models") -> t
                Regex("/v\\d+[a-z]*$").containsMatchIn(t) -> "$t/models"
                else -> "$t/v1/models"
            }
        }
    }
}
