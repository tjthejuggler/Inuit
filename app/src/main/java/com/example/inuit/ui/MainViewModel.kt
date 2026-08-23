package com.example.inuit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.inuit.AppGraph
import com.example.inuit.data.AppSettings
import com.example.inuit.data.DebugLog
import com.example.inuit.data.Grader
import com.example.inuit.data.Question
import com.example.inuit.data.QuestionSelector
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class MainViewModel(private val graph: AppGraph) : ViewModel() {

    private val store = graph.store
    private val rng = Random.Default

    // ── session boundary (blind-training invariant) ──────────────────────
    // The user must NEVER learn whether specific answers were right or
    // wrong. Answers are graded and persisted, but everything that could
    // reflect correctness — stats, knowledge summaries, Socratic follow-up
    // threads — is frozen at the moment the session started and only
    // refreshes when the user comes back to the app (onResume).

    private var sessionBoundaryMs: Long = System.currentTimeMillis()

    /** Ticks only on session start / app resume — drives stats recomputation. */
    private val statsEpoch = MutableStateFlow(0L)

    // ── current question ─────────────────────────────────────────────────

    private val _currentQuestion = MutableStateFlow<Question?>(null)
    val currentQuestion: StateFlow<Question?> = _currentQuestion.asStateFlow()

    /** Shown when the question card is collapsed. */
    private val _questionCollapsed = MutableStateFlow(false)
    val questionCollapsed: StateFlow<Boolean> = _questionCollapsed.asStateFlow()

    fun setCollapsed(collapsed: Boolean) {
        _questionCollapsed.value = collapsed
    }

    init {
        pickNext()
        // If the queue is empty but generation just finished, pick a question.
        viewModelScope.launch {
            graph.generator.state.collect { st ->
                if (_currentQuestion.value == null && store.queueSize() > 0) pickNext()
            }
        }
    }

    /**
     * Called from the Activity on ON_RESUME: the user came back to the app,
     * so the frozen session stats may now absorb everything answered so far
     * and a new session boundary begins.
     */
    fun onSessionResume() {
        sessionBoundaryMs = System.currentTimeMillis()
        statsEpoch.value = statsEpoch.value + 1L
    }

    /**
     * Selection strategy lives in [QuestionSelector]: Socratic threads from
     * prior-session misses, spaced wrong-weighted revisits of old questions,
     * then fresh realm-diverse picks. The verdict of the just-recorded
     * answer can never influence this pick (blind-training invariant).
     */
    fun pickNext() {
        _currentQuestion.value = QuestionSelector.select(
            store.snapshotQuestions(),
            store.snapshotAnswers(),
            _currentQuestion.value?.id,
            sessionBoundaryMs,
            rng
        )
    }

    /**
     * Grades locally (verdict never reaches the UI), records, refills the
     * queue and IMMEDIATELY advances to the next question — no acknowledgment
     * screen, no extra tap. The [questionId] guard makes the instant advance
     * safe: a stale double-tap on the old answer button is ignored instead of
     * grading the already-replaced current question.
     */
    fun submitAnswer(questionId: String, raw: String, elapsedMs: Long) {
        val q = _currentQuestion.value ?: return
        if (q.id != questionId) return
        val correct = Grader.grade(q, raw)
        store.recordAnswer(q.id, correct, raw, elapsedMs)
        graph.generator.maybeGenerate()
        pickNext()
    }

    fun skip() {
        _currentQuestion.value?.let { store.markSkipped(it.id) }
        pickNext()
    }

    // ── stats (frozen per session; refresh only on app resume) ───────────

    val stats: StateFlow<StatsCalculator.Snapshot> =
        statsEpoch
            .map { withContext(Dispatchers.Default) { computeSnapshot() } }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                computeSnapshot()
            )

    val knowledgeSummaries = statsEpoch
        .map { store.snapshotSummaries() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.snapshotSummaries())

    /** Live queue depth — operational info only, safe to update in real time. */
    val queueSize: StateFlow<Int> = store.dataVersion
        .map { store.queueSize() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.queueSize())

    private fun computeSnapshot(): StatsCalculator.Snapshot =
        StatsCalculator.compute(
            store.snapshotQuestions(),
            store.snapshotAnswers(),
            store.snapshotDomainStats(),
            store.queueSize()
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
        minConfidence: Float, mcpBudget: Int
    ) {
        viewModelScope.launch {
            graph.settingsStore.saveGeneration(batchSize, queueThreshold, verifyEnabled, minConfidence, mcpBudget)
        }
    }

    fun saveMcpJson(json: String) {
        viewModelScope.launch { graph.settingsStore.saveMcpJson(json) }
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
