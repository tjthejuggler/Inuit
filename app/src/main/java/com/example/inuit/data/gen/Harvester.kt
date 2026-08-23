package com.example.inuit.data.gen

import com.example.inuit.data.AppSettings
import com.example.inuit.data.DebugLog
import com.example.inuit.data.Net
import com.example.inuit.data.Question
import com.example.inuit.data.QuestionStore
import com.example.inuit.data.llm.LlmClient
import com.example.inuit.data.llm.LlmConfig
import com.example.inuit.data.llm.LlmMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Bulk stockpile harvester: uses the MCP web tools to fetch LARGE trivia
 * question lists from the internet (not personalized), then tags, validates,
 * fact-checks and stores them through the exact same pipeline as generated
 * questions. Runs when the personalized batch wasn't enough to reach the
 * queue threshold — the user should never run out of questions.
 */
class Harvester(
    private val store: QuestionStore,
    private val llm: LlmClient,
    private val rng: Random = Random.Default
) {

    /**
     * One harvest round. Returns how many questions were added (0 = nothing
     * usable — caller should stop). Throws IOException on network trouble.
     */
    suspend fun harvestRound(
        cfg: LlmConfig,
        s: AppSettings,
        target: Int,
        net: Net? = null,
        onNote: (String) -> Unit
    ): Int {
        onNote("Connecting web tools…")
        val session = McpSession(s)
        if (!session.connect()) {
            DebugLog.w(TAG, "harvest skipped — no MCP web tools configured/reachable")
            return 0
        }

        // ── tool loop: search + read, then convert ───────────────────────
        var budget = HARVEST_TOOL_BUDGET
        val messages = ArrayList<LlmMessage>()
        messages.add(LlmMessage.system(Prompts.harvestSystemPrompt(HARVEST_TOOL_BUDGET, net)))
        // Custom nets always search within their scope; the All net rotates
        // through general trivia themes for variety.
        val theme = if (net != null && !net.isAll) {
            ", ${net.name} trivia questions with answers"
        } else {
            THEMES[rng.nextInt(THEMES.size)]
        }
        messages.add(LlmMessage.user(Prompts.harvestUserPrompt(target, theme, store.queueSize())))

        var finalContent: String? = null
        var round = 0
        while (round < MAX_ROUNDS) {
            round++
            val useTools = budget > 0 && session.hasTools
            onNote(if (useTools) "Searching the web for trivia lists ($budget lookups left)…" else "Converting found trivia…")
            val assistant = llm.chat(
                cfg, messages,
                tools = if (useTools) session.toolSpecs else emptyList(),
                temperature = 0.4f,
                maxTokens = HARVEST_MAX_TOKENS,
                disableThinking = s.disableThinking
            )
            messages.add(assistant)
            if (assistant.toolCalls.isEmpty()) {
                finalContent = assistant.content
                break
            }
            for (call in assistant.toolCalls) {
                val result: String = when {
                    budget <= 0 -> "Tool budget exhausted for this harvest. Convert what you already have."
                    else -> {
                        budget--
                        DebugLog.i(TAG, "harvest tool call '${call.name}' args=${call.argumentsJson.take(120)}")
                        session.call(call.name, call.argumentsJson)
                    }
                }
                messages.add(LlmMessage.tool(call.id, call.name, result))
            }
        }
        if (finalContent == null) {
            DebugLog.e(TAG, "harvest: model kept calling tools without finishing after $MAX_ROUNDS rounds")
            return 0
        }
        DebugLog.i(TAG, "harvest raw output: ${finalContent.length} chars")

        // ── validate through the shared pipeline ─────────────────────────
        onNote("Validating harvested questions…")
        val parsed = Validator.parseAndValidate(finalContent, store.snapshotQuestions(), s, emptyMap())
        var accepted = parsed.questions.map { it.copy(source = SOURCE) }.toMutableList()
        if (parsed.dropped > 0)
            DebugLog.w(TAG, "harvest validation: accepted=${accepted.size} dropped=${parsed.dropped}")
        if (accepted.isEmpty()) return 0

        // ── verifier pass (same anti-hallucination standard) ─────────────
        if (s.verifyEnabled) {
            onNote("Fact-checking ${accepted.size} harvested questions…")
            try {
                val before = accepted.size
                accepted = verifyBatch(cfg, s, accepted)
                if (accepted.size < before)
                    DebugLog.w(TAG, "harvest verifier dropped ${before - accepted.size} of $before")
            } catch (e: Exception) {
                DebugLog.e(TAG, "harvest verifier failed (keeping batch unverified)", e)
            }
        }
        if (accepted.isEmpty()) return 0

        // The user may switch nets mid-harvest; never file a batch into the
        // wrong net's store.
        if (net != null && store.activeNetId != net.id) {
            DebugLog.w(TAG, "net switched during harvest — discarding ${accepted.size} questions")
            return 0
        }

        store.insertQuestions(accepted)
        if (parsed.newFrontiers.isNotEmpty()) {
            store.replaceFrontiers(store.snapshotFrontiers() + parsed.newFrontiers)
        }
        // Stockpile must survive process death — write now.
        withContext(Dispatchers.IO) { store.persistNow() }
        DebugLog.i(TAG, "harvest round done: +${accepted.size} questions (theme='$theme')")
        return accepted.size
    }

    /** Second-pass fact check; drops flagged questions (mirrors the generator). */
    private suspend fun verifyBatch(
        cfg: LlmConfig,
        s: AppSettings,
        questions: MutableList<Question>
    ): MutableList<Question> {
        val arr = JSONArray()
        questions.forEachIndexed { i, q ->
            arr.put(JSONObject().apply {
                put("index", i)
                put("type", q.type.name)
                put("prompt", q.prompt)
                put("choices", JSONArray(q.choices))
                put("intended_answer", intendedAnswer(q))
                put("tolerance", q.tolerance ?: JSONObject.NULL)
            })
        }
        val reply = llm.chat(
            cfg,
            listOf(LlmMessage.user(Prompts.verifierPrompt(arr))),
            temperature = 0.0f,
            maxTokens = VERIFY_MAX_TOKENS,
            disableThinking = s.disableThinking
        )
        val flags = JSONObject(Prompts.extractJson(reply.content ?: "{}"))
            .optJSONArray("flags") ?: return questions
        val flagged = HashSet<Int>()
        for (i in 0 until flags.length()) flagged.add(flags.optInt(i, -1))
        if (flagged.isEmpty()) return questions
        DebugLog.w(TAG, "harvest verifier flagged indices $flagged")
        return questions.filterIndexed { i, _ -> i !in flagged }.toMutableList()
    }

    private fun intendedAnswer(q: Question): Any? = when (q.type) {
        com.example.inuit.data.QuestionType.TRUE_FALSE -> q.answerBool
        com.example.inuit.data.QuestionType.MULTIPLE_CHOICE -> q.answerIndex?.let { q.choices.getOrNull(it) }
        com.example.inuit.data.QuestionType.NUMERIC -> q.answerNumber
        com.example.inuit.data.QuestionType.FILL_BLANK -> q.acceptedAnswers.firstOrNull()
    }

    companion object {
        private const val TAG = "Harvest"
        const val SOURCE = "web:harvest"
        private const val MAX_ROUNDS = 8
        private const val HARVEST_TOOL_BUDGET = 6
        private const val HARVEST_MAX_TOKENS = 16_000
        private const val VERIFY_MAX_TOKENS = 4_000

        /** Rotating search-theme spices so repeated harvests don't all fetch
         *  the same "100 trivia questions" page. */
        private val THEMES = listOf(
            "", "", // plain general trivia most of the time
            ", geography trivia questions with answers",
            ", science trivia questions with answers",
            ", movie trivia questions with answers",
            ", history trivia questions with answers",
            ", music trivia questions with answers",
            ", sports trivia questions with answers",
            ", animal trivia questions with answers",
            ", food trivia questions with answers",
            ", space trivia questions with answers",
            ", literature trivia questions with answers",
            ", 90s trivia questions with answers",
            ", bible trivia questions with answers",
            ", christmas trivia questions with answers",
            ", easy trivia questions for kids with answers",
            ", hard pub quiz questions with answers"
        )
    }
}
