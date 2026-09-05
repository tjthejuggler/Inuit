package com.example.inuit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.inuit.AppGraph
import com.example.inuit.data.AppSettings
import com.example.inuit.data.DebugLog
import com.example.inuit.data.Grader
import com.example.inuit.data.HabitEntry
import com.example.inuit.data.Net
import com.example.inuit.data.PodcastAppInfo
import com.example.inuit.data.PodcastApps
import com.example.inuit.data.PodcastDirectory
import com.example.inuit.data.PodcastRec
import com.example.inuit.data.Question
import com.example.inuit.data.QuestionSelector
import com.example.inuit.data.QuestionType
import com.example.inuit.data.StatsCalculator
import com.example.inuit.data.llm.LlmConfig
import com.example.inuit.data.llm.McpClient
import com.example.inuit.data.llm.McpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class MainViewModel(private val graph: AppGraph) : ViewModel() {

    private val store = graph.store
    private val rng = Random.Default

    // ── session boundary ─────────────────────────────────────────────────
    // Answers are graded, persisted, revealed to the user immediately, and
    // folded into the on-screen stats in real time. The boundary timestamp
    // still exists: QuestionSelector uses it so Socratic follow-up threads
    // only trigger from misses in PRIOR sessions, not the current one.

    private var sessionBoundaryMs: Long = System.currentTimeMillis()

    /** Ticks on answer / session start / app resume — drives stats recomputation. */
    private val statsEpoch = MutableStateFlow(0L)

    // ── answer flash (revealed correctness) ──────────────────────────────

    /** What the user sees right after submitting: "Correct!" or the answer. */
    data class AnswerFlash(
        val correct: Boolean,
        /** Canonical correct answer text — shown when the answer was wrong. */
        val correctAnswer: String
    )

    private val _lastFlash = MutableStateFlow<AnswerFlash?>(null)

    /** The most recent answer's result; replaced on each submit, cleared on skip. */
    val lastFlash: StateFlow<AnswerFlash?> = _lastFlash.asStateFlow()

    // ── current question ─────────────────────────────────────────────────

    private val _currentQuestion = MutableStateFlow<Question?>(null)
    val currentQuestion: StateFlow<Question?> = _currentQuestion.asStateFlow()

    init {
        // Restore the persisted on-screen question first: a question shown
        // but never answered must NEVER be wasted by closing the app.
        _currentQuestion.value = store.pendingQuestion() ?: selectNext()
        // If the queue is empty but generation just finished, pick a question.
        viewModelScope.launch {
            graph.generator.state.collect { st ->
                if (_currentQuestion.value == null && store.queueSize() > 0) pickNext()
            }
        }
        // Keep a podcast recommendation ready at the bottom of stats.
        graph.podcasts.ensureRec()
        // React to net switches: resurface the new net's pending question,
        // unfreeze+recompute stats for it, swap the podcast card, and kick
        // generation when its queue is empty.
        viewModelScope.launch {
            var handled = graph.netStore.active().id
            graph.netStore.activeNet.collect { net ->
                if (net.id != handled) {
                    handled = net.id
                    onActiveNetChanged()
                }
            }
        }
    }

    /** Everything that must re-derive when the user switches nets. */
    private fun onActiveNetChanged() {
        _currentQuestion.value = store.pendingQuestion() ?: selectNext()
        sessionBoundaryMs = System.currentTimeMillis()
        statsEpoch.value = statsEpoch.value + 1L
        graph.podcasts.onNetChanged()
        graph.generator.maybeGenerate()
    }

    // ── nets ─────────────────────────────────────────────────────────────

    /** All nets; the All net is always first. */
    val nets: StateFlow<List<Net>> = graph.netStore.nets

    /** The net the user is currently training in. */
    val activeNet: StateFlow<Net> = graph.netStore.activeNet

    /** Top-bar dropdown: switch the whole app to another net. */
    fun selectNet(id: String) {
        graph.netStore.setActive(id)
    }

    /** Settings: create a net from the edit dialog's draft (name, scope,
     *  podcast toggle + the occasional-accent options). */
    fun createNet(draft: Net) {
        graph.netStore.createNet(draft)
            ?: return // blank name or cap reached — UI validates beforehand
    }

    /** Settings: rename / re-scope / re-toggle an existing net. */
    fun updateNet(net: Net) {
        graph.netStore.updateNet(net)
        if (net.id == graph.netStore.active().id) graph.podcasts.onNetChanged()
    }

    /** Settings: delete a user net (the All net is immortal). */
    fun deleteNet(id: String) {
        graph.netStore.deleteNet(id)
    }

    /**
     * Called from the Activity on ON_RESUME: the user came back to the app,
     * so the frozen session stats may now absorb everything answered so far
     * and a new session boundary begins.
     */
    fun onSessionResume() {
        sessionBoundaryMs = System.currentTimeMillis()
        statsEpoch.value = statsEpoch.value + 1L
        // Refresh a stale podcast recommendation (kept until tapped otherwise).
        graph.podcasts.ensureRec()
    }

    /**
     * Selection strategy lives in [QuestionSelector]: Socratic threads from
     * prior-session misses, spaced wrong-weighted revisits of old questions,
     * then fresh realm-diverse picks. The verdict of the just-recorded
     * answer can never influence this pick (blind-training invariant).
     */
    fun pickNext() {
        _currentQuestion.value = selectNext()
    }

    /** Selects the next question AND persists it as the pending one. */
    private fun selectNext(): Question? {
        val q = QuestionSelector.select(
            store.snapshotQuestions(),
            store.snapshotAnswers(),
            _currentQuestion.value?.id,
            sessionBoundaryMs,
            rng
        )
        store.setPendingQuestion(q?.id)
        return q
    }

    /**
     * Grades locally, records, refills the queue, flashes the result
     * ("Correct!" or the correct answer), refreshes stats in real time and
     * IMMEDIATELY advances to the next question — no extra tap. The
     * [questionId] guard makes the instant advance safe: a stale double-tap
     * on the old answer button is ignored instead of grading the
     * already-replaced current question.
     */
    fun submitAnswer(questionId: String, raw: String, elapsedMs: Long) {
        val q = _currentQuestion.value ?: return
        if (q.id != questionId) return
        val correct = Grader.grade(q, raw)
        _lastFlash.value = AnswerFlash(correct, q.correctAnswerDisplay)
        statsEpoch.value = statsEpoch.value + 1L
        val record = store.recordAnswer(q.id, correct, raw, elapsedMs)
        // Every persisted answer ticks the connected Tail habit by +1, stamped
        // at the exact answer time so Tail's schedule timeline is accurate.
        if (record != null) graph.tail.sendQuestionsIncrement(record.timestamp)
        graph.generator.maybeGenerate()
        pickNext()
    }

    /** Skip = REJECT: the question is permanently retired from the queue and
     *  pushed onto the net's rejection pile so the generator learns what not
     *  to make (and, once the pile is full, re-distills its rejection notes). */
    fun skip() {
        _lastFlash.value = null
        _currentQuestion.value?.let {
            if (store.rejectQuestion(it.id)) graph.generator.maybeRefreshRejectionNotes()
        }
        pickNext()
    }

    // ── stats (real time: recomputed on every submitted answer) ─────────

    // ── question history (per-domain leaf drill-down) ────────────────────

    /** One row of the per-domain Question History list. */
    data class QuestionHistoryItem(
        val questionId: String,
        val prompt: String,
        val correctAnswer: String,
        val attempts: Int,
        val correctCount: Int,
        val lastUserAnswer: String,
        /** Human-readable version of what the user chose/typed (MC choice text, not index). */
        val lastUserAnswerDisplay: String,
        val lastCorrect: Boolean,
        val lastAnsweredAt: Long
    )

    /**
     * Every answered question whose domain matches the tree-leaf path
     * (already net-stripped, as [StatsCalculator.DomainNode.path] is).
     * Newest answer first.
     */
    fun questionHistory(domainPath: String): List<QuestionHistoryItem> {
        val netName = graph.netStore.active().takeIf { !it.isAll }?.name
        val questions = store.snapshotQuestions()
        val answers = store.snapshotAnswers()
        val byQuestion = answers.groupBy { it.questionId }
        return questions.mapNotNull { q ->
            if (q.domains.none { strippedPath(it, netName).equals(domainPath, ignoreCase = true) }) return@mapNotNull null
            val recs = byQuestion[q.id] ?: return@mapNotNull null
            val last = recs.maxBy { it.timestamp }
            QuestionHistoryItem(
                questionId = q.id,
                prompt = q.prompt,
                correctAnswer = q.correctAnswerDisplay,
                attempts = recs.size,
                correctCount = recs.count { it.correct },
                lastUserAnswer = last.userAnswer,
                lastUserAnswerDisplay = userAnswerDisplay(q, last.userAnswer),
                lastCorrect = last.correct,
                lastAnsweredAt = last.timestamp
            )
        }.sortedByDescending { it.lastAnsweredAt }
    }

    /** Raw stored answers → readable text: MC stores the choice INDEX. */
    private fun userAnswerDisplay(q: Question, raw: String): String = when (q.type) {
        QuestionType.MULTIPLE_CHOICE ->
            raw.trim().toIntOrNull()?.let { q.choices.getOrNull(it) } ?: raw
        QuestionType.TRUE_FALSE -> raw.trim().replaceFirstChar { it.uppercase() }
        else -> raw
    }

    /** Net-stripped display path for a stored domain tag (see StatsCalculator.topKey). */
    private fun strippedPath(path: String, netName: String?): String {
        val segs = path.split(" > ").map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return path
        if (netName != null && segs.size >= 2 && segs[0].equals(netName.trim(), ignoreCase = true)) {
            return segs.drop(1).joinToString(" > ")
        }
        return segs.joinToString(" > ")
    }

    val stats: StateFlow<StatsCalculator.Snapshot> =
        statsEpoch
            .map { withContext(Dispatchers.Default) { computeSnapshot() } }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                computeSnapshot()
            )

    // NOTE: the LLM-written knowledge-state summaries (store.snapshotSummaries)
    // are generation context ONLY — they can reveal which questions the user
    // got right or wrong, so they must never be exposed to the UI.

    /** Live queue depth — operational info only, safe to update in real time. */
    val queueSize: StateFlow<Int> = store.dataVersion
        .map { store.queueSize() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.queueSize())

    private fun computeSnapshot(): StatsCalculator.Snapshot =
        StatsCalculator.compute(
            store.snapshotQuestions(),
            store.snapshotAnswers(),
            store.snapshotDomainStats(),
            store.queueSize(),
            // Custom nets: aggregate the stats knowledge map at the subtopic
            // level — the net name alone would collapse every row into one.
            graph.netStore.active().takeIf { !it.isAll }?.name
        )

    // ── settings ─────────────────────────────────────────────────────────

    val settings: StateFlow<AppSettings> = graph.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val genState = graph.generator.state

    fun saveLlmSettings(baseUrl: String, apiKey: String, model: String, temperature: Float) {
        viewModelScope.launch { graph.settingsStore.saveLlm(baseUrl, apiKey, model, temperature) }
    }

    fun setDisableThinking(disable: Boolean) {
        viewModelScope.launch { graph.settingsStore.setDisableThinking(disable) }
    }

    fun saveGenerationSettings(
        batchSize: Int, queueThreshold: Int, verifyEnabled: Boolean,
        minConfidence: Float, mcpBudget: Int, harvestEnabled: Boolean
    ) {
        viewModelScope.launch {
            graph.settingsStore.saveGeneration(batchSize, queueThreshold, verifyEnabled, minConfidence, mcpBudget, harvestEnabled)
        }
    }

    fun saveMcpJson(json: String) {
        viewModelScope.launch { graph.settingsStore.saveMcpJson(json) }
    }

    // ── podcast recommendations ──────────────────────────────────────────

    /** The episode currently shown at the bottom of the stats panel. */
    val podcast: StateFlow<PodcastRec?> = graph.podcasts.rec

    val podcastLoading: StateFlow<Boolean> = graph.podcasts.loading

    /** Previously clicked episodes, newest first. */
    val podcastHistory: StateFlow<List<PodcastRec>> = graph.podcasts.history

    private val _podcastApps = MutableStateFlow<List<PodcastAppInfo>>(emptyList())
    val podcastApps: StateFlow<List<PodcastAppInfo>> = _podcastApps.asStateFlow()

    /** The shown episode was tapped: retire it, generate the next one, open it. */
    fun onPodcastOpened(rec: PodcastRec) {
        graph.podcasts.onClicked(rec)
        openInPodcastApp(rec)
    }

    /** A history episode was tapped: open it without retiring anything. */
    fun onHistoryPodcastOpened(rec: PodcastRec) {
        openInPodcastApp(rec)
    }

    /**
     * Opens the episode in the user's chosen podcast app. The show's RSS
     * feed is resolved on demand (iTunes directory) so apps with documented
     * feed-subscribe schemes — Pocket Casts, AntennaPod — land on the exact
     * show instead of an error; already-grounded recs open instantly.
     */
    private fun openInPodcastApp(rec: PodcastRec) {
        viewModelScope.launch {
            val pkg = graph.settingsStore.current().podcastAppPackage.ifBlank { null }
            val resolved = PodcastDirectory.resolve(rec)
            PodcastApps.open(graph.appContext, resolved, pkg)
        }
    }

    /** Discovers installed podcast apps (Settings picker); cached after first load. */
    fun loadPodcastApps() {
        if (_podcastApps.value.isNotEmpty()) return
        viewModelScope.launch {
            _podcastApps.value = withContext(Dispatchers.Default) {
                PodcastApps.find(graph.appContext)
            }
        }
    }

    fun savePodcastApp(pkg: String) {
        viewModelScope.launch { graph.settingsStore.setPodcastApp(pkg) }
    }

    // ── Tail habit integration ───────────────────────────────────────────

    /** The Tail habit connected to the questions-answered slot. */
    data class HabitSelection(
        val habitId: String = "",
        val habitName: String = ""
    ) {
        val isSet: Boolean get() = habitId.isNotBlank()
        val displayName: String get() = habitName.ifBlank { habitId }
    }

    /** UI state for the Settings → Tail app section. */
    data class TailUiState(
        val habitList: List<HabitEntry> = emptyList(),
        val isLoading: Boolean = false,
        val appUnavailable: Boolean = false,
        val selected: HabitSelection = HabitSelection(),
        val isBackfilling: Boolean = false,
        val backfillMessage: String? = null,
        val backfillError: String? = null
    )

    private val _tailState = MutableStateFlow(
        TailUiState(
            selected = HabitSelection(
                habitId = graph.tail.getHabitId(),
                habitName = graph.tail.getHabitName()
            )
        )
    )
    val tailState: StateFlow<TailUiState> = _tailState.asStateFlow()

    /** Loads the habit list from Tail's Content Provider. */
    fun loadTailHabits() {
        viewModelScope.launch {
            _tailState.update { it.copy(isLoading = true, appUnavailable = false) }
            val habits = graph.tail.fetchHabits()
            _tailState.update {
                it.copy(habitList = habits, isLoading = false, appUnavailable = habits.isEmpty())
            }
        }
    }

    /** Text habits Tail currently shares for life-log accents (habit names). */
    private val _sharedTextHabits = MutableStateFlow<List<String>>(emptyList())
    val sharedTextHabits: StateFlow<List<String>> = _sharedTextHabits.asStateFlow()

    /** Loads the text habits the user shared in Tail (Tail: Settings → Integrations → Inuit). */
    fun loadSharedTextHabits() {
        viewModelScope.launch {
            _sharedTextHabits.value = graph.tail.fetchSharedTextHabits().map { it.habitName }
        }
    }

    /** Connects a habit and immediately backfills it with the full answer history. */
    fun selectTailHabit(entry: HabitEntry) {
        graph.tail.setHabit(entry)
        _tailState.update { it.copy(selected = HabitSelection(entry.habitId, entry.habitName)) }
        backfillTailHistory(auto = true)
    }

    fun clearTailHabit() {
        graph.tail.clearHabit()
        _tailState.update { it.copy(selected = HabitSelection()) }
    }

    /** Manual "Backfill" action from Settings. */
    fun backfillTail() = backfillTailHistory(auto = false)

    /**
     * Pushes the per-date answer history (everything from the past and today
     * so far) to the connected Tail habit. Idempotent — Tail SETS each date.
     */
    private fun backfillTailHistory(auto: Boolean) {
        viewModelScope.launch {
            if (!auto) {
                _tailState.update { it.copy(isBackfilling = true, backfillMessage = null, backfillError = null) }
            }
            try {
                val result = withContext(Dispatchers.Default) {
                    graph.tail.backfillAnswers(store.snapshotAnswers())
                }
                _tailState.update {
                    when {
                        result.dates > 0 -> it.copy(
                            isBackfilling = false,
                            backfillMessage = "Sent ${result.dates} dates (${result.answers} answers) to Tail."
                        )
                        !auto -> it.copy(
                            isBackfilling = false,
                            backfillMessage = "No answers to backfill yet."
                        )
                        else -> it.copy(isBackfilling = false)
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("Tail", "Backfill failed", e)
                _tailState.update {
                    it.copy(isBackfilling = false, backfillError = "Backfill failed: ${e.message}")
                }
            }
        }
    }

    fun clearBackfillMessage() {
        _tailState.update { it.copy(backfillMessage = null, backfillError = null) }
    }

    // ── connectivity tests (Settings screen) ─────────────────────────────

    sealed interface TestResult {
        data object Running : TestResult
        data class Ok(val message: String) : TestResult
        data class Fail(val message: String) : TestResult
    }

    private val _llmTest = MutableStateFlow<TestResult?>(null)
    val llmTest: StateFlow<TestResult?> = _llmTest.asStateFlow()

    private val _mcpTest = MutableStateFlow<TestResult?>(null)
    val mcpTest: StateFlow<TestResult?> = _mcpTest.asStateFlow()

    fun testLlm(baseUrl: String, apiKey: String, model: String) {
        viewModelScope.launch {
            _llmTest.value = TestResult.Running
            _llmTest.value = try {
                val cfg = LlmConfig(baseUrl, apiKey, model)
                if (!cfg.configured) {
                    TestResult.Fail("Base URL and model are required")
                } else {
                    val models = graph.llm.listModels(cfg)
                    val found = models.any { it == model.trim() }
                    if (models.isEmpty()) {
                        TestResult.Ok("Endpoint reachable (no model list)")
                    } else if (found || model.isBlank()) {
                        TestResult.Ok("OK — ${models.size} models visible")
                    } else {
                        TestResult.Fail("Endpoint works but '$model' not in list (it may still work)")
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("Test", "LLM test failed", e)
                TestResult.Fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun testMcp(json: String) {
        viewModelScope.launch {
            _mcpTest.value = TestResult.Running
            _mcpTest.value = try {
                val parsed = McpConfig.parse(json)
                if (parsed.error != null) TestResult.Fail("Invalid JSON: ${parsed.error}")
                else if (parsed.servers.isEmpty()) {
                    TestResult.Fail("No HTTP servers found${if (parsed.skipped.isNotEmpty()) " (skipped stdio: ${parsed.skipped.joinToString()})" else ""}")
                } else {
                    val sb = StringBuilder()
                    for (server in parsed.servers.take(3)) {
                        try {
                            val client = McpClient(server)
                            client.initialize()
                            val tools = client.listTools()
                            sb.append("${server.name}: ${tools.size} tools (${tools.joinToString { it.name }})\n")
                        } catch (e: Exception) {
                            sb.append("${server.name}: FAILED — ${e.message}\n")
                        }
                    }
                    if (sb.contains("FAILED")) TestResult.Fail(sb.toString().trim())
                    else TestResult.Ok(sb.toString().trim())
                }
            } catch (e: Exception) {
                TestResult.Fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun generateNow() {
        graph.generator.generateNow()
    }

    companion object {
        fun factory(graph: AppGraph): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(graph) as T
        }
    }
}
