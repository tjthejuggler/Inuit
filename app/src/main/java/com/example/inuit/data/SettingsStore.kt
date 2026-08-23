package com.example.inuit.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "inuit_settings")

/**
 * Seed MCP configuration: z.ai internet tools (streamable-http).
 * The user pastes their own API key in Settings. stdio servers cannot run
 * on Android and are ignored by the MCP client.
 */
const val DEFAULT_MCP_JSON = """{
  "mcpServers": {
    "web-search-prime": {
      "type": "streamable-http",
      "url": "https://api.z.ai/api/mcp/web_search_prime/mcp",
      "headers": { "Authorization": "Bearer PASTE_ZAI_API_KEY_HERE" }
    },
    "web-reader": {
      "type": "streamable-http",
      "url": "https://api.z.ai/api/mcp/web_reader/mcp",
      "headers": { "Authorization": "Bearer PASTE_ZAI_API_KEY_HERE" }
    }
  }
}"""

data class AppSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.7f,
    /** Send thinking:disabled — GLM reasoning models skip internal chains (faster/cheaper). */
    val disableThinking: Boolean = false,
    val batchSize: Int = 30,
    /** Stockpile target — kept large so the user never runs out of questions. */
    val queueThreshold: Int = 150,
    val verifyEnabled: Boolean = true,
    val minConfidence: Float = 0.8f,
    val mcpBudget: Int = 3,
    /** Top the stockpile up with bulk web-harvested trivia when low. */
    val harvestEnabled: Boolean = true,
    val mcpJson: String = DEFAULT_MCP_JSON,
    /** Total answer count already covered by rolling summaries. */
    val summarizedAnswers: Int = 0
) {
    val llmConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

class SettingsStore(private val context: Context) {

    private object K {
        val BASE_URL = stringPreferencesKey("llm_base_url")
        val API_KEY = stringPreferencesKey("llm_api_key")
        val MODEL = stringPreferencesKey("llm_model")
        val TEMPERATURE = floatPreferencesKey("llm_temperature")
        val DISABLE_THINKING = booleanPreferencesKey("llm_disable_thinking")
        val BATCH_SIZE = intPreferencesKey("gen_batch_size")
        val QUEUE_THRESHOLD = intPreferencesKey("gen_queue_threshold")
        val VERIFY = booleanPreferencesKey("gen_verify")
        val MIN_CONFIDENCE = floatPreferencesKey("gen_min_confidence")
        val MCP_BUDGET = intPreferencesKey("gen_mcp_budget")
        val HARVEST = booleanPreferencesKey("gen_harvest")
        val MCP_JSON = stringPreferencesKey("mcp_json")
        val SUMMARIZED = intPreferencesKey("summarized_answers")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            baseUrl = p[K.BASE_URL] ?: "",
            apiKey = p[K.API_KEY] ?: "",
            model = p[K.MODEL] ?: "",
            temperature = p[K.TEMPERATURE] ?: 0.7f,
            disableThinking = p[K.DISABLE_THINKING] ?: false,
            batchSize = (p[K.BATCH_SIZE] ?: 30).coerceIn(5, 60),
            queueThreshold = (p[K.QUEUE_THRESHOLD] ?: 150).coerceIn(5, 500),
            verifyEnabled = p[K.VERIFY] ?: true,
            minConfidence = (p[K.MIN_CONFIDENCE] ?: 0.8f).coerceIn(0.5f, 1f),
            mcpBudget = (p[K.MCP_BUDGET] ?: 3).coerceIn(0, 20),
            harvestEnabled = p[K.HARVEST] ?: true,
            mcpJson = p[K.MCP_JSON] ?: DEFAULT_MCP_JSON,
            summarizedAnswers = p[K.SUMMARIZED] ?: 0
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun saveLlm(baseUrl: String, apiKey: String, model: String, temperature: Float) {
        context.dataStore.edit {
            it[K.BASE_URL] = baseUrl.trim()
            it[K.API_KEY] = apiKey.trim()
            it[K.MODEL] = model.trim()
            it[K.TEMPERATURE] = temperature
        }
    }

    suspend fun saveGeneration(
        batchSize: Int,
        queueThreshold: Int,
        verifyEnabled: Boolean,
        minConfidence: Float,
        mcpBudget: Int,
        harvestEnabled: Boolean
    ) {
        context.dataStore.edit {
            it[K.BATCH_SIZE] = batchSize.coerceIn(5, 60)
            it[K.QUEUE_THRESHOLD] = queueThreshold.coerceIn(5, 500)
            it[K.VERIFY] = verifyEnabled
            it[K.MIN_CONFIDENCE] = minConfidence.coerceIn(0.5f, 1f)
            it[K.MCP_BUDGET] = mcpBudget.coerceIn(0, 20)
            it[K.HARVEST] = harvestEnabled
        }
    }

    suspend fun setDisableThinking(disable: Boolean) {
        context.dataStore.edit { it[K.DISABLE_THINKING] = disable }
    }

    suspend fun saveMcpJson(json: String) {
        context.dataStore.edit { it[K.MCP_JSON] = json }
    }

    suspend fun setSummarizedAnswers(count: Int) {
        context.dataStore.edit { it[K.SUMMARIZED] = count }
    }
}
