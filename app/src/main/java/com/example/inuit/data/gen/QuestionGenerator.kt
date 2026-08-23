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
import com.example.inuit.data.llm.LlmToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background question engine:
 *  1. assembles a bounded stratified context,
 *  2. runs a tool-loop chat with the LLM (web tools allowed within a strict budget),
 *  3. validates + dedups the batch,
 *  4. optional second-pass verifier drops suspicious questions,
 *  5. inserts into the queue, refreshes frontiers and rolling summaries.
 *
 * Refill policy: a self-draining loop keeps EVERY net's queue at the
 * configured threshold — the ACTIVE net first (the user is watching it),
 * then the neediest other net in the background. Batches are filed into
 * the net they were generated FOR, so switching nets mid-batch never
 * discards completed work; when a personalized batch isn't enough to reach
 * the threshold, the [Harvester] tops the stockpile up with bulk
 * web-sourced trivia through the same validation pipeline.
 *
 * Every stage is logged to [DebugLog] so failures are always diagnosable
 * (Settings → Diagnostics, logcat tag "Inuit", or files/inuit_debug.log).
 */
class QuestionGenerator(
    private val store: QuestionStore,
    private val settingsStore: SettingsStore,
    private val netStore: NetStore,
    private val llm: LlmClient,
    private val harvester: Harvester,
    private val accents: AccentsBuilder,
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

        /** A single LLM call slower than this flips the run into
         *  thinking-off mode (hidden reasoning tokens dominate latency). */
        private const val SLOW_CALL_MS = 90_000L

        /** Hard wall-clock ceiling for one full batch (tool loop + verifier).
         *  Generation can never keep the UI in "Generating…" longer. */
        private const val BATCH_DEADLINE_MS = 20 * 60_000L

        /** Auto-retry cadence after a failed refill: 1 min, then ×5 up to the
         *  cap, reset on the first success. Prevents an infinite
         *  1-minute relaunch loop when the failure is deterministic, but
         *  guarantees a failed batch ALWAYS eventually retries — an empty
         *  queue has no other trigger (no answers → no maybeGenerate). */
        private const val AUTO_RETRY_BASE_MS = 60_000L
        private const val AUTO_RETRY_MAX_MS = 15 * 60_000L

        fun nextAutoRetryDelay(current: Long): Long = minOf(current * 5, AUTO_RETRY_MAX_MS)

        /**
         * Pure scheduling decision: which net (if any) needs generation.
         *
         * The ACTIVE net always wins while below the threshold (the user is
         * looking at it — an empty screen is the worst outcome); otherwise
         * the neediest OTHER net (ascending queue) is picked so background
         * stock-keeping quietly refills nets the user has drained. Null when
         * every net is at or above the threshold.
         */
        fun pickNeedyNet(
            activeId: String,
            nets: List<Net>,
            threshold: Int,
            queueOf: (String) -> Int
        ): Net? {
            val activeNet = nets.firstOrNull { it.id == activeId }
            if (activeNet != null && queueOf(activeId) < threshold) return activeNet
            return nets.asSequence()
                .filter { it.id != activeId }
                .filter { queueOf(it.id) < threshold }
                .minByOrNull { queueOf(it.id) }
        }
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

    /** Set once this RUN suffered a slow/timed-out call; every later call
     *  of the run is sent with thinking disabled (see [chatAdaptive]).
     *  Sticky for the whole refill run (not per batch): a slow-reasoning
     *  model re-proving its slowness on every chained batch of a multi-
     *  batch refill wastes minutes per batch. A fresh run gets one fresh
     *  chance at deep thinking. */
    private var fastMode = false

    private var autoRetryDelayMs = AUTO_RETRY_BASE_MS
    private var autoRetryJob: Job? = null

    // ── foreground-service lifetime ──────────────────────────────────────
    // True while any refill work OR a scheduled auto-retry is in flight.
    // InuitApp keeps BatchGenService alive for exactly this long, so
    // closing the app or turning the screen off never kills an in-flight
    // batch — and never kills a pending comeback either (the old design
    // stopped the service during retry back-off, letting Android reap the
    // process and silently cancelling the retry).

    private val activeJobs = AtomicInteger(0)
    private val _serviceNeeded = MutableStateFlow(false)
    val serviceNeeded: StateFlow<Boolean> = _serviceNeeded

    /** Must run SYNCHRONOUSLY in the caller (before the launched coroutine
     *  may be suspended) so nested begin/end pairs never produce a gap. */
    private fun beginWork() {
        activeJobs.incrementAndGet()
        _serviceNeeded.value = true
    }

    private fun endWork() {
        if (activeJobs.decrementAndGet() <= 0) _serviceNeeded.value = false
    }

    /** Called after answers, on app start and on net switches; no-op when
     *  every net's queue is healthy. */
    fun maybeGenerate() {
        if (genJob?.isActive == true) return
        beginWork()
        genJob = scope.launch {
            try {
                generateLoop(forced = false)
            } finally {
                endWork()
            }
        }
    }

    /** Forced generation (Settings / empty-state button): one full episode
     *  for the ACTIVE net, regardless of its queue depth. */
    fun generateNow() {
        if (genJob?.isActive == true) return
        beginWork()
        genJob = scope.launch {
            try {
                generateLoop(forced = true)
            } finally {
                endWork()
            }
        }
    }

    /**
     * Self-draining refill loop. Each iteration picks the net that most
     * needs questions — the active net first, then the emptiest other net
     * (background stock-keeping) — and runs one refill episode for it.
     * Exits when every net is healthy; exits with a scheduled comeback on
     * any failure.
     */
    private suspend fun generateLoop(forced: Boolean) {
        fastMode = false // one fresh deep-thinking chance per run
        var totalAdded = 0
        while (true) {
            val s = settingsStore.current()
            if (!s.llmConfigured) {
                _state.value = GenState.Error("LLM not configured — set base URL and model in Settings")
                return
            }
            val activeId = netStore.active().id
            val target = if (forced) {
                netStore.nets.value.firstOrNull { it.id == activeId }
            } else {
                pickNeedyNet(activeId, netStore.nets.value, s.queueThreshold) { store.queueSizeFor(it) }
            }
            if (target == null) {
                autoRetryDelayMs = AUTO_RETRY_BASE_MS // everyone healthy — no retry pending
                if (totalAdded > 0 || forced) _state.value = GenState.Completed(totalAdded)
                return
            }
            if (target.id != activeId) {
                DebugLog.i(TAG, "background top-up — net='${target.name}' " +
                    "queue=${store.queueSizeFor(target.id)} < ${s.queueThreshold}")
            }
            totalAdded += refillNet(s, target)
            if (forced) {
                if (_state.value !is GenState.Error) _state.value = GenState.Completed(totalAdded)
                return
            }
            if (_state.value is GenState.Error) {
                scheduleComeback() // ANY failure retries — never stall on an empty queue
                return
            }
            // Stay Running across episodes (holds the foreground service);
            // the terminal Completed lands when every net is healthy.
            _state.value = GenState.Running("+$totalAdded questions — checking queues…")
        }
    }

    /**
     * One refill episode for [net]: a personalized batch, then a web-harvest
     * top-up. The episode shares ONE MCP session (batch + harvest) instead
     * of re-handshaking every server per call. Returns questions added.
     */
    private suspend fun refillNet(s: AppSettings, net: Net): Int {
        val session = if (s.mcpBudget > 0 || s.harvestEnabled) {
            _state.value = GenState.Running(note(net, "Connecting web tools…"))
            McpSession(s).also { it.connect() }
        } else null
        try {
            val fromBatch = runGeneration(s, net, session)
            if (_state.value is GenState.Error) return fromBatch
            // Yield: if the user switched to a net that now needs questions,
            // skip the (slower, bulk) harvest — the loop services the active
            // net first and this net can be harvested on a later pass.
            val active = netStore.active()
            if (active.id != net.id && store.queueSizeFor(active.id) < s.queueThreshold) {
                DebugLog.i(TAG, "active net '${active.name}' needs generation — deferring harvest for '${net.name}'")
                return fromBatch
            }
            return fromBatch + topUpStockpile(s, net, session)
        } catch (e: Exception) {
            // runGeneration/topUpStockpile handle their own errors; this is
            // a safety net so the loop can never die mid-episode.
            DebugLog.e(TAG, "refill episode failed", e)
            _state.value = GenState.Error(e.message ?: e.javaClass.simpleName)
            return 0
        }
    }

    /** Retry wrapper: transient mobile-network failures (DNS, timeouts) get
     *  backoff retries; configuration errors fail fast. Returns questions
     *  added (0 on failure — the caller checks the Error state). */
    private suspend fun runGeneration(s: AppSettings, net: Net, session: McpSession?): Int {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return attemptGeneration(s, net, session)
            } catch (e: java.io.IOException) {
                DebugLog.e(TAG, "network error on attempt $attempt", e)
                if (attempt >= MAX_NETWORK_ATTEMPTS) {
                    _state.value = GenState.Error(
                        "Network problem after $attempt tries: ${e.message}. " +
                            "Check your connection — will retry automatically."
                    )
                    return 0
                }
                val backoff = RETRY_BACKOFF_MS[attempt - 1]
                DebugLog.w(TAG, "retrying in ${backoff / 1000}s ($attempt/$MAX_NETWORK_ATTEMPTS)")
                _state.value = GenState.Running(note(net, "Network hiccup — retrying in ${backoff / 1000}s…"))
                kotlinx.coroutines.delay(backoff)
            }
        }
    }

    private suspend fun attemptGeneration(s: AppSettings, net: Net, session: McpSession?): Int {
        val startedAt = System.currentTimeMillis()
        try {
            return attemptGenerationInner(s, net, session)
        } catch (e: java.io.IOException) {
            // transient network problem — let the retry wrapper handle it
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "generation failed after ${System.currentTimeMillis() - startedAt}ms", e)
            _state.value = GenState.Error(e.message ?: e.javaClass.simpleName)
            return 0
        }
    }

    /** LLM call with adaptive speed. Deep thinking is by far the biggest
     *  latency driver on reasoning models (10k+ hidden tokens per call — the
     *  device log showed healthy single calls outliving a 300 s timeout). If
     *  the user allows thinking but a call is slow or dies on a network
     *  timeout, the rest of the run is sent with thinking disabled
     *  automatically; the user's global setting is never modified. */
    private suspend fun chatAdaptive(
        cfg: LlmConfig,
        messages: List<LlmMessage>,
        tools: List<LlmToolSpec>,
        temperature: Float,
        maxTokens: Int,
        s: AppSettings
    ): LlmMessage {
        val disable = s.disableThinking || fastMode
        val t0 = System.currentTimeMillis()
        try {
            val reply = llm.chat(cfg, messages, tools, temperature, maxTokens, disableThinking = disable)
            if (!disable && System.currentTimeMillis() - t0 > SLOW_CALL_MS) {
                fastMode = true
                DebugLog.w(TAG, "LLM call took ${(System.currentTimeMillis() - t0) / 1000}s — " +
                    "deep thinking off for the rest of this run")
            }
            return reply
        } catch (e: java.io.IOException) {
            if (disable) throw e // already fast — genuine network trouble
            fastMode = true
            DebugLog.w(TAG, "LLM call failed after ${(System.currentTimeMillis() - t0) / 1000}s " +
                    "(${e.message}) — retrying with deep thinking off")
            _state.value = GenState.Running("Model too slow — continuing without deep thinking…")
            return llm.chat(cfg, messages, tools, temperature, maxTokens, disableThinking = true)
        }
    }

    /** Progress note tagged with the net's name when the refill runs in the
     *  background (target ≠ active) — the user always sees WHICH net is
     *  being filled. */
    private fun note(net: Net, text: String): String =
        if (net.id == netStore.active().id) text else "(${net.name}) $text"

    private suspend fun attemptGenerationInner(s: AppSettings, net: Net, session: McpSession?): Int {
        // Hard ceiling: whatever happens inside (slow reasoning, tool loops,
        // retries), one batch can never keep the UI in "Generating…" for
        // longer than BATCH_DEADLINE_MS.
        val outcome = withTimeoutOrNull(BATCH_DEADLINE_MS) { generateBatch(s, net, session) }
        if (outcome == null) {
            _state.value = GenState.Error(
                "Batch gave up after ${BATCH_DEADLINE_MS / 60_000} minutes — " +
                    "will retry automatically with faster settings."
            )
            return 0
        }
        return outcome
    }

    private suspend fun generateBatch(s: AppSettings, net: Net, session: McpSession?): Int {
        val startedAt = System.currentTimeMillis()
            _state.value = GenState.Running(note(net, "Assembling context…"))
            val ctx = ContextBuilder(store).build(net, net.id)
            // Occasional accents (location / date / other nets) — off by default,
            // strictly dosed; every failure inside degrades to "no accent".
            val netAccents = try {
                accents.build(net)
            } catch (e: Exception) {
                DebugLog.w(TAG, "accent build failed (continuing without): ${e.message}")
                NetAccents()
            }
            DebugLog.i(TAG, "net='${net.name}' context built: recent=${ctx.recentLines.size} unknownGroups=${ctx.unknownGroups.size} " +
                "known=${ctx.knownLines.size} digest=${ctx.domainDigest.size} " +
                "distant=${ctx.distantFrontiers.size} revisits=${ctx.revisitFrontiers.size} " +
                "accents(loc=${netAccents.locationLine != null} date=${netAccents.dateLines.size} " +
                "crossNet=${netAccents.crossNetLines.size})")

            // ── MCP tools (budgeted; session shared with the harvest) ─────
            var budget = s.mcpBudget
            if (budget > 0 && session?.hasTools != true)
                DebugLog.w(TAG, "no MCP tools available — generating from certain knowledge only")

            // ── tool loop ─────────────────────────────────────────────────
            val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
            val messages = ArrayList<LlmMessage>()
            messages.add(LlmMessage.system(Prompts.systemPrompt(s.mcpBudget, net)))
            messages.add(LlmMessage.user(Prompts.userRequest(ctx, s.batchSize, net, netAccents)))

            var finalContent: String? = null
            var round = 0
            while (round < MAX_ROUNDS) {
                round++
                val useTools = budget > 0 && session?.hasTools == true
                _state.value = GenState.Running(note(net,
                    if (useTools) "Generating batch (web tools available: $budget)…"
                    else "Generating batch…"
                ))
                val assistant = chatAdaptive(
                    cfg, messages,
                    tools = if (useTools) session?.toolSpecs ?: emptyList() else emptyList(),
                    temperature = s.temperature,
                    maxTokens = GEN_MAX_TOKENS,
                    s = s
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
                            _state.value = GenState.Running(note(net, "Web lookup ($budget left)…"))
                            DebugLog.i(TAG, "tool call '${call.name}' args=${call.argumentsJson.take(120)}")
                            session?.call(call.name, call.argumentsJson)
                                ?: "No web tools connected."
                        }
                    }
                    messages.add(LlmMessage.tool(call.id, call.name, result))
                }
            }
            if (finalContent == null) {
                _state.value = GenState.Error("Model kept calling tools without finishing after $MAX_ROUNDS rounds")
                return 0
            }
            DebugLog.i(TAG, "rounds=$round raw model output: ${finalContent.length} chars, " +
                "head=\"${finalContent.take(150)}…\"")

            // ── validate (dedup against the TARGET net, not the active one:
            //    the user may have switched nets during the tool loop) ─────
            _state.value = GenState.Running(note(net, "Validating…"))
            val existing = store.snapshotQuestionsFor(net.id)
            val parsed = Validator.parseAndValidate(finalContent, existing, s, ctx.markerToQuestion, net)
            var accepted = parsed.questions.toMutableList()
            if (parsed.dropped > 0 || accepted.isEmpty()) {
                DebugLog.w(TAG, "validation: accepted=${accepted.size} dropped=${parsed.dropped}")
                parsed.dropReasons.take(12).forEach { DebugLog.w(TAG, "  drop: $it") }
            } else {
                DebugLog.i(TAG, "validation: accepted=${accepted.size} dropped=0")
            }

            // ── verifier pass ─────────────────────────────────────────────
            if (s.verifyEnabled && accepted.isNotEmpty()) {
                _state.value = GenState.Running(note(net, "Fact-checking ${accepted.size} questions…"))
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
                return 0
            }

            // The net may have been deleted mid-generation; never (re)create
            // a deleted net's store from a stale batch. A net SWITCH is fine
            // though — the batch files into the net it was generated for.
            if (netStore.nets.value.none { it.id == net.id }) {
                DebugLog.w(TAG, "net '${net.name}' deleted during generation — discarding ${accepted.size} questions")
                return 0
            }

            store.insertQuestionsFor(net.id, accepted)
            autoRetryDelayMs = AUTO_RETRY_BASE_MS // success — calm the retry cadence
            if (parsed.newFrontiers.isNotEmpty()) {
                store.replaceFrontiersFor(net.id, store.snapshotFrontiersFor(net.id) + parsed.newFrontiers)
            }
            // The batch must survive process death — write it now, not after
            // the 1.2s debounce.
            withContext(Dispatchers.IO) { store.persistNow() }
            DebugLog.i(TAG, "batch done in ${System.currentTimeMillis() - startedAt}ms: " +
                "+${accepted.size} questions into '${net.name}' (dropped ${parsed.dropped})")

            // ── rolling summaries ─────────────────────────────────────────
            try {
                refreshSummaries(cfg, s, net.id)
            } catch (e: Exception) {
                DebugLog.e(TAG, "summary refresh failed (non-fatal)", e)
            }

            return accepted.size
    }

    /**
     * Stockpile top-up: if the personalized batch left the net below the
     * threshold, harvest bulk web trivia until it's reached (bounded
     * rounds). The user should never run out of questions.
     */
    private suspend fun topUpStockpile(s: AppSettings, net: Net, session: McpSession?): Int {
        if (!s.harvestEnabled) return 0
        if (store.queueSizeFor(net.id) >= s.queueThreshold) return 0
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        var rounds = 0
        var total = 0
        while (store.queueSizeFor(net.id) < s.queueThreshold && rounds < MAX_HARVEST_ROUNDS) {
            // Yield between rounds: the user may have switched to a net that
            // now needs questions — let the loop service it first.
            val active = netStore.active()
            if (active.id != net.id && store.queueSizeFor(active.id) < s.queueThreshold) {
                DebugLog.i(TAG, "active net '${active.name}' needs generation — pausing harvest for '${net.name}'")
                break
            }
            rounds++
            _state.value = GenState.Running(note(net, "Stockpiling trivia from the web (round $rounds)…"))
            val target = minOf(HARVEST_TARGET_CAP, (s.queueThreshold - store.queueSizeFor(net.id)).coerceAtLeast(20))
            val added = try {
                // If the personalized pass already hit fast mode, harvest fast too.
                val eff = if (fastMode && !s.disableThinking) s.copy(disableThinking = true) else s
                harvester.harvestRound(cfg, eff, target, net, net.id, session) { n ->
                    _state.value = GenState.Running(note(net, n))
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
            DebugLog.i(TAG, "stockpile top-up done: +$total web questions into '${net.name}', " +
                "queue=${store.queueSizeFor(net.id)}")
        }
        return total
    }

    /**
     * After any failed refill: schedule one comeback with escalating
     * back-off (1 min → ×5 → 15 min cap; reset on success). The job holds
     * [serviceNeeded], so the foreground service — and with it the process —
     * survives the wait even when the user has left the app. Without this,
     * a failed batch on an empty queue could stall forever: no questions →
     * no answers → no maybeGenerate trigger.
     */
    private fun scheduleComeback() {
        val wait = autoRetryDelayMs
        autoRetryDelayMs = nextAutoRetryDelay(autoRetryDelayMs)
        DebugLog.w(TAG, "auto-retry scheduled in ${wait / 1000}s (next would be ${autoRetryDelayMs / 1000}s)")
        autoRetryJob?.cancel()
        beginWork()
        autoRetryJob = scope.launch {
            try {
                kotlinx.coroutines.delay(wait)
                maybeGenerate()
            } finally {
                endWork()
            }
        }
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
        val reply = chatAdaptive(
            cfg,
            listOf(LlmMessage.user(Prompts.verifierPrompt(arr))),
            emptyList(),
            0.0f,
            VERIFY_MAX_TOKENS,
            s
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

    /** Refreshes a net's rolling summaries when enough new answers exist. */
    private suspend fun refreshSummaries(cfg: LlmConfig, s: AppSettings, netId: String) {
        val answers = store.snapshotAnswersFor(netId)
        if (answers.size < SUMMARY_MIN_ANSWERS) return
        if (answers.size - store.summarizedAnswersFor(netId) < SUMMARY_INTERVAL) return

        _state.value = GenState.Running("Updating knowledge summaries…")
        val questions = store.snapshotQuestionsFor(netId).associateBy { it.id }
        val prev = store.snapshotSummariesFor(netId).associateBy { it.domain }

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

        val reply = chatAdaptive(
            cfg,
            listOf(LlmMessage.user(Prompts.summaryPrompt(inputs))),
            emptyList(),
            0.3f,
            SUMMARY_MAX_TOKENS,
            s
        )
        val parsed = Prompts.parseSummaries(reply.content ?: return)
        if (parsed.isEmpty()) return
        val now = System.currentTimeMillis()
        store.replaceSummariesFor(
            netId,
            parsed.map { (domain, text) ->
                KnowledgeSummary(domain, text, now, answers.size)
            }
        )
        store.setSummarizedAnswersFor(netId, answers.size)
        DebugLog.i(TAG, "knowledge summaries refreshed for ${parsed.size} domains of net '$netId'")
    }
}
