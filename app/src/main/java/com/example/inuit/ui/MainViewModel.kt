package com.example.inuit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.inuit.AppGraph
import com.example.inuit.data.AppSettings
import com.example.inuit.data.DebugLog
import com.example.inuit.data.Grader
import com.example.inuit.data.Question
import com.example.inuit.data.StatsCalculator
import com.example.inuit.data.llm.LlmConfig
import com.example.inuit.data.llm.McpClient
import com.example.inuit.data.llm.McpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class MainViewModel(private val graph: AppGraph) : ViewModel() {

    private val store = graph.store
    private val rng = Random.Default

    // ── current question & feedback ──────────────────────────────────────

    private val _currentQuestion = MutableStateFlow<Question?>(null)
    val currentQuestion: StateFlow<Question?> = _currentQuestion.asStateFlow()

    sealed interface Feedback {
        data class Result(val correct: Boolean, val streak: Int) : Feedback
    }

    private val _feedback = MutableStateFlow<Feedback.Result?>(null)
    val feedback: StateFlow<Feedback.Result?> = _feedback.asStateFlow()

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
     * Selection strategy: sometimes surface a sub-question of a recently
     * missed lineage (Socratic thread); otherwise a random fresh question
     * with domain diversity and a skip penalty.
     */
    fun pickNext() {
        val queue = store.queue()
        if (queue.isEmpty()) {
            _currentQuestion.value = null
            _feedback.value = null
            return
        }
        val current = _currentQuestion.value
        val candidates = queue.filter { it.id != current?.id }.ifEmpty { queue }

        val recentWrongRoots = store.snapshotAnswers()
            .takeLast(12)
            .filter { !it.correct }
            .mapNotNull { a -> store.questionById(a.questionId)?.let { q -> q.rootId ?: q.id } }
            .toSet()
        val subQuestions = candidates.filter {
            it.rootId != null && it.id != it.rootId && it.rootId in recentWrongRoots
        }

        val lastDomain = current?.domains?.firstOrNull()
        val pick = if (subQuestions.isNotEmpty() && rng.nextInt(100) < 35) {
            subQuestions.random(rng)
        } else {
            val diverse = candidates.filter { q -> q.domains.firstOrNull() != lastDomain }
                .ifEmpty { candidates }
            val fresh = diverse.filter { it.skipCount == 0 }.ifEmpty { diverse }
            fresh.random(rng)
        }
        _currentQuestion.value = pick
        _feedback.value = null
    }

    /** Grades locally (answer never leaves the grader), records, refills queue. */
    fun submitAnswer(raw: String, elapsedMs: Long) {
        val q = _currentQuestion.value ?: return
        if (_feedback.value != null) return
        val correct = Grader.grade(q, raw)
        store.recordAnswer(q.id, correct, raw, elapsedMs)
        var streak = 0
        for (a in store.snapshotAnswers().asReversed()) {
            if (a.correct) streak++ else break
        }
        _feedback.value = Feedback.Result(correct, streak)
        graph.generator.maybeGenerate()
    }

    fun skip() {
        _currentQuestion.value?.let { store.markSkipped(it.id) }
        pickNext()
    }

    // ── stats ────────────────────────────────────────────────────────────

    val stats: StateFlow<StatsCalculator.Snapshot> =
        combine(store.dataVersion, graph.generator.state) { v, _ -> v }
            .map { withContext(Dispatchers.Default) { computeSnapshot() } }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                run { StatsCalculator.compute(emptyList(), emptyList(), emptyList(), 0) }
            )

    val knowledgeSummaries = combine(store.dataVersion, graph.generator.state) { v, _ -> v }
        .map { store.snapshotSummaries() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
