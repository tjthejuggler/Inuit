package com.example.inuit.data.gen

import com.example.inuit.data.AppSettings
import com.example.inuit.data.DebugLog
import com.example.inuit.data.KnowledgeSummary
import com.example.inuit.data.Net
import com.example.inuit.data.NetStore
import com.example.inuit.data.QuestionStore
import com.example.inuit.data.SettingsStore
import com.example.inuit.data.llm.LlmClient
import com.example.inuit.data.llm.LlmConfig
import com.example.inuit.data.llm.LlmMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Background question engine:
 *  1. assembles a bounded stratified context,
 *  2. runs a tool-loop chat with the LLM (web tools allowed within a strict budget),
 *  3. validates + dedups the batch,
 *  4. optional second-pass verifier drops suspicious questions,
 *  5. inserts into the queue, refreshes frontiers and rolling summaries.
 *
 * Triggered whenever the queue drops below the configured threshold; when a
 * personalized batch isn't enough to reach the threshold, the [Harvester]
 * tops the stockpile up with bulk web-sourced trivia through the same
 * validation pipeline.
 * Every stage is logged to [DebugLog] so failures are always diagnosable
 * (Settings → Diagnostics, logcat tag "Inuit", or files/inuit_debug.log).
 */
class QuestionGenerator(
    private val store: QuestionStore,
    private val settingsStore: SettingsStore,
    private val netStore: NetStore,
    private val llm: LlmClient,
    private val harvester: Harvester,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "Gen"
        private const val MAX_ROUNDS = 6
        private const val MAX_HARVEST_ROUNDS = 3
        private const val HARVEST_TARGET_CAP = 80
        private const val SUMMARY_INTERVAL = 60
        private const val SUMMARY_MIN_ANSWERS = 30

        /** Reasoning models burn many tokens on internal thinking before the
         *  JSON body; 16k leaves ample room for both. */
        private const val GEN_MAX_TOKENS = 16_000
        private const val VERIFY_MAX_TOKENS = 4_000
        private const val SUMMARY_MAX_TOKENS = 4_000
        private const val MAX_NETWORK_ATTEMPTS = 3
        private val RETRY_BACKOFF_MS = longArrayOf(5_000, 15_000)
    }

    sealed interface GenState {
        data object Idle : GenState
        data class Running(val note: String) : GenState
        data class Error(val message: String, val at: Long = System.currentTimeMillis()) : GenState
        data class Completed(val added: Int, val at: Long = System.currentTimeMillis()) : GenState
    }

    private val _state = MutableStateFlow<GenState>(GenState.Idle)
    val state: StateFlow<GenState> = _state
    private var genJob: Job? = null

    /** Called after answers and on app start; no-op when the queue is healthy. */
    fun maybeGenerate() {
        if (genJob?.isActive == true) return
        genJob = scope.launch {
            val s = settingsStore.current()
            if (!s.llmConfigured) return@launch
            if (store.queueSize() >= s.queueThreshold) return@launch
            val net = netStore.active()
            DebugLog.i(TAG, "net='${net.name}' queue ${store.queueSize()} < threshold ${s.queueThreshold} — generating")
            runGeneration(s)
            topUpStockpile(s, net)
        }
    }

    /** Forced generation (Settings / empty-state button). */
    fun generateNow() {
        if (genJob?.isActive == true) return
        genJob = scope.launch {
            val s = settingsStore.current()
            if (!s.llmConfigured) {
                _state.value = GenState.Error("LLM not configured — set base URL and model in Settings")
                return@launch
            }
            val net = netStore.active()
            runGeneration(s)
            topUpStockpile(s, net)
        }
    }

    /**
     * Stockpile top-up: if the personalized batch left the queue below the
     * threshold, harvest bulk web trivia until it's reached (bounded rounds).
     * The user should never run out of questions.
     */
    private suspend fun topUpStockpile(s: AppSettings, net: Net) {
        if (!s.harvestEnabled) return
        if (store.queueSize() >= s.queueThreshold) return
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        var rounds = 0
        var total = 0
        while (store.queueSize() < s.queueThreshold && rounds < MAX_HARVEST_ROUNDS) {
            rounds++
            _state.value = GenState.Running("Stockpiling trivia from the web (round $rounds)…")
            val target = minOf(HARVEST_TARGET_CAP, (s.queueThreshold - store.queueSize()).coerceAtLeast(20))
            val added = try {
                harvester.harvestRound(cfg, s, target, net) { note ->
                    _state.value = GenState.Running(note)
                }
            } catch (e: java.io.IOException) {
                DebugLog.e(TAG, "harvest network error — stopping top-up", e)
                break
            } catch (e: Exception) {
                DebugLog.e(TAG, "harvest round failed — stopping top-up", e)
                break
            }
            total += added
            if (added == 0) break // nothing usable — don't hammer the web
        }
        if (total > 0) {
            DebugLog.i(TAG, "stockpile top-up done: +$total web questions, queue=${store.queueSize()}")
            _state.value = GenState.Completed(total)
        }
    }

    /** Retry wrapper: transient mobile-network failures (DNS, timeouts) get
     *  backoff retries; configuration errors fail fast. */
    private suspend fun runGeneration(s: AppSettings) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                attemptGeneration(s)
                return
            } catch (e: java.io.IOException) {
                DebugLog.e(TAG, "network error on attempt $attempt", e)
                if (attempt >= MAX_NETWORK_ATTEMPTS) {
                    val m = "Network problem after $attempt tries: ${e.message}. " +
                        "Check your connection — will retry automatically in a minute."
                    DebugLog.e(TAG, m)
                    _state.value = GenState.Error(m)
                    // schedule one more try so the queue eventually refills
                    scope.launch {
                        kotlinx.coroutines.delay(60_000)
                        maybeGenerate()
                    }
                    return
                }
                val backoff = RETRY_BACKOFF_MS[attempt - 1]
                DebugLog.w(TAG, "retrying in ${backoff / 1000}s ($attempt/$MAX_NETWORK_ATTEMPTS)")
                _state.value = GenState.Running("Network hiccup — retrying in ${backoff / 1000}s…")
                kotlinx.coroutines.delay(backoff)
            }
        }
    }

    private suspend fun attemptGeneration(s: AppSettings) {
        val startedAt = System.currentTimeMillis()
        try {
            attemptGenerationInner(s)
        } catch (e: java.io.IOException) {
            // transient network problem — let the retry wrapper handle it
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "generation failed after ${System.currentTimeMillis() - startedAt}ms", e)
            _state.value = GenState.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun attemptGenerationInner(s: AppSettings) {
        val startedAt = System.currentTimeMillis()
            val net = netStore.active()
            _state.value = GenState.Running("Assembling context…")
            val ctx = ContextBuilder(store).build(net)
            DebugLog.i(TAG, "context built: recent=${ctx.recentLines.size} unknownGroups=${ctx.unknownGroups.size} " +
                "known=${ctx.knownLines.size} digest=${ctx.domainDigest.size} " +
                "distant=${ctx.distantFrontiers.size} revisits=${ctx.revisitFrontiers.size}")

            // ── MCP tools (budgeted) ──────────────────────────────────────
            var budget = s.mcpBudget
            var session: McpSession? = null
            if (budget > 0) {
                _state.value = GenState.Running("Connecting MCP servers…")
                session = McpSession(s)
                session.connect()
                if (!session.hasTools)
                    DebugLog.w(TAG, "no MCP tools available — generating from certain knowledge only")
            }

            // ── tool loop ─────────────────────────────────────────────────
            val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
            val messages = ArrayList<LlmMessage>()
            messages.add(LlmMessage.system(Prompts.systemPrompt(s.mcpBudget, net)))
            messages.add(LlmMessage.user(Prompts.userRequest(ctx, s.batchSize, net)))

            var finalContent: String? = null
            var round = 0
            while (round < MAX_ROUNDS) {
                round++
                val useTools = budget > 0 && session?.hasTools == true
                _state.value = GenState.Running(
                    if (useTools) "Generating batch (web tools available: $budget)…"
                    else "Generating batch…"
                )
                val assistant = llm.chat(
                    cfg, messages,
                    tools = if (useTools) session?.toolSpecs ?: emptyList() else emptyList(),
                    temperature = s.temperature,
                    maxTokens = GEN_MAX_TOKENS,
                    disableThinking = s.disableThinking
                )
                messages.add(assistant)
                if (assistant.toolCalls.isEmpty()) {
                    finalContent = assistant.content
                    break
                }
                for (call in assistant.toolCalls) {
                    val result: String = when {
                        budget <= 0 ->
                            "Tool budget exhausted for this batch. Proceed with certain knowledge only."
                        else -> {
                            budget--
                            _state.value = GenState.Running("Web lookup ($budget left)…")
                            DebugLog.i(TAG, "tool call '${call.name}' args=${call.argumentsJson.take(120)}")
                            session?.call(call.name, call.argumentsJson)
                                ?: "No web tools connected."
                        }
                    }
                    messages.add(LlmMessage.tool(call.id, call.name, result))
                }
            }
            if (finalContent == null) {
                val m = "Model kept calling tools without finishing after $MAX_ROUNDS rounds"
                DebugLog.e(TAG, m)
                _state.value = GenState.Error(m)
                return
            }
            DebugLog.i(TAG, "rounds=$round raw model output: ${finalContent.length} chars, " +
                "head=\"${finalContent.take(150)}…\"")

            // ── validate ──────────────────────────────────────────────────
            _state.value = GenState.Running("Validating…")
            val existing = store.snapshotQuestions()
            val parsed = Validator.parseAndValidate(finalContent, existing, s, ctx.markerToQuestion)
            var accepted = parsed.questions.toMutableList()
            if (parsed.dropped > 0 || accepted.isEmpty()) {
                DebugLog.w(TAG, "validation: accepted=${accepted.size} dropped=${parsed.dropped}")
                parsed.dropReasons.take(12).forEach { DebugLog.w(TAG, "  drop: $it") }
            } else {
                DebugLog.i(TAG, "validation: accepted=${accepted.size} dropped=0")
            }

            // ── verifier pass ─────────────────────────────────────────────
            if (s.verifyEnabled && accepted.isNotEmpty()) {
                _state.value = GenState.Running("Fact-checking ${accepted.size} questions…")
                try {
                    val before = accepted.size
                    accepted = verifyBatch(cfg, s, accepted)
                    if (accepted.size < before)
                        DebugLog.w(TAG, "verifier dropped ${before - accepted.size} of $before questions")
                } catch (e: Exception) {
                    DebugLog.e(TAG, "verifier failed (keeping batch unverified)", e)
                }
            }

            if (accepted.isEmpty()) {
                val reasonSummary = parsed.dropReasons.groupingBy { it.substringAfter(": ") }
                    .eachCount().entries.sortedByDescending { it.value }
                    .take(4).joinToString { "${it.value}× ${it.key}" }
                val m = buildString {
                    append("All ${parsed.dropped} candidate questions were discarded")
                    if (reasonSummary.isNotEmpty()) append(": $reasonSummary")
                    append(". Raw output started with: \"")
                    append(finalContent.take(120).replace('\n', ' '))
                    append("…\" — see Diagnostics in Settings")
                }
                DebugLog.e(TAG, m)
                _state.value = GenState.Error(m)
                return
            }

            // The user may switch nets mid-generation; never file a batch
            // into the wrong net's store.
            if (store.activeNetId != net.id) {
                DebugLog.w(TAG, "net switched during generation — discarding ${accepted.size} questions")
                _state.value = GenState.Completed(0)
                return
            }

            store.insertQuestions(accepted)
            if (parsed.newFrontiers.isNotEmpty()) {
                store.replaceFrontiers(store.snapshotFrontiers() + parsed.newFrontiers)
            }
            // The batch must survive process death — write it now, not after
            // the 1.2s debounce.
            withContext(Dispatchers.IO) { store.persistNow() }
            DebugLog.i(TAG, "batch done in ${System.currentTimeMillis() - startedAt}ms: " +
                "+${accepted.size} questions (dropped ${parsed.dropped})")

            // ── rolling summaries ─────────────────────────────────────────
            try {
                refreshSummaries(cfg, s)
            } catch (e: Exception) {
                DebugLog.e(TAG, "summary refresh failed (non-fatal)", e)
            }

            _state.value = GenState.Completed(accepted.size)
    }

    /** Second-pass fact check; drops flagged questions. */
    private suspend fun verifyBatch(
        cfg: LlmConfig,
        s: AppSettings,
        questions: MutableList<com.example.inuit.data.Question>
    ): MutableList<com.example.inuit.data.Question> {
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
        DebugLog.w(TAG, "verifier flagged indices $flagged")
        return questions.filterIndexed { i, _ -> i !in flagged }.toMutableList()
    }

    private fun intendedAnswer(q: com.example.inuit.data.Question): Any? = when (q.type) {
        com.example.inuit.data.QuestionType.TRUE_FALSE -> q.answerBool
        com.example.inuit.data.QuestionType.MULTIPLE_CHOICE ->
            q.answerIndex?.let { q.choices.getOrNull(it) }
        com.example.inuit.data.QuestionType.NUMERIC -> q.answerNumber
        com.example.inuit.data.QuestionType.FILL_BLANK -> q.acceptedAnswers.firstOrNull()
    }

    /** Refreshes per-domain rolling summaries when enough new answers exist. */
    private suspend fun refreshSummaries(cfg: LlmConfig, s: AppSettings) {
        val answers = store.snapshotAnswers()
        if (answers.size < SUMMARY_MIN_ANSWERS) return
        if (answers.size - store.summarizedAnswers() < SUMMARY_INTERVAL) return

        _state.value = GenState.Running("Updating knowledge summaries…")
        val questions = store.snapshotQuestions().associateBy { it.id }
        val prev = store.snapshotSummaries().associateBy { it.domain }

        // group recent answer lines by top-level domain
        val byDomain = LinkedHashMap<String, MutableList<String>>()
        for (a in answers.takeLast(400)) {
            val q = questions[a.questionId] ?: continue
            val top = q.domains.firstOrNull()?.substringBefore(" > ") ?: continue
            val mark = if (a.correct) "✓" else "✗"
            byDomain.getOrPut(top) { mutableListOf() }
                .add("$mark (d${q.difficulty}) [${q.domains.first()}] ${q.prompt}")
        }
        val inputs = byDomain.entries
            .sortedByDescending { it.value.size }
            .take(8)
            .map { (domain, lines) ->
                Prompts.SummaryInput(domain, prev[domain]?.text, lines.takeLast(50))
            }
        if (inputs.isEmpty()) return

        val reply = llm.chat(
            cfg,
            listOf(LlmMessage.user(Prompts.summaryPrompt(inputs))),
            temperature = 0.3f,
            maxTokens = SUMMARY_MAX_TOKENS,
            disableThinking = s.disableThinking
        )
        val parsed = Prompts.parseSummaries(reply.content ?: return)
        if (parsed.isEmpty()) return
        val now = System.currentTimeMillis()
        store.replaceSummaries(
            parsed.map { (domain, text) ->
                KnowledgeSummary(domain, text, now, answers.size)
            }
        )
        store.setSummarizedAnswers(answers.size)
        DebugLog.i(TAG, "knowledge summaries refreshed for ${parsed.size} domains")
    }
}
