package com.example.inuit.data.gen

import com.example.inuit.data.AppSettings
import com.example.inuit.data.DebugLog
import com.example.inuit.data.DomainStat
import com.example.inuit.data.Net
import com.example.inuit.data.NetStore
import com.example.inuit.data.PodcastDirectory
import com.example.inuit.data.PodcastRec
import com.example.inuit.data.QuestionStore
import com.example.inuit.data.SettingsStore
import com.example.inuit.data.llm.LlmClient
import com.example.inuit.data.llm.LlmConfig
import com.example.inuit.data.llm.LlmMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps exactly one LLM-chosen podcast episode ready at the bottom of the
 * stats panel. Episodes are picked from the user's weakest knowledge areas
 * (lowest-accuracy domains, recent misses, rolling summaries); tapping the
 * card retires the episode and the next one is generated immediately.
 *
 * A stable public episode URL is required — the app opens the episode FROM
 * that link. When MCP web tools are configured the model may search for the
 * episode's Apple Podcasts page (small dedicated budget); a missing URL
 * triggers one corrective retry before giving up on the link.
 */
class PodcastRecommender(
    private val store: QuestionStore,
    private val settingsStore: SettingsStore,
    private val netStore: NetStore,
    private val llm: LlmClient,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "Podcast"
        private const val MAX_TOKENS = 3_000
        /** A rec older than this is regenerated on session resume. */
        private const val STALE_MS = 24 * 60 * 60 * 1000L
        /** Linkless recs regenerate sooner — the link is the whole point. */
        private const val LINKLESS_STALE_MS = 10 * 60 * 1000L
        /** Resolved episodes kept ready behind the current one, so a click
         *  swaps in the next recommendation instantly. */
        private const val STOCKPILE_TARGET = 3
        private const val MAX_TOOL_ROUNDS = 3
        /** Web calls dedicated to finding/verifying one episode URL. */
        private const val MAX_TOOL_CALLS = 2
    }

    private val _rec = MutableStateFlow(store.currentPodcast())
    val rec: StateFlow<PodcastRec?> = _rec.asStateFlow()

    private val _history = MutableStateFlow(store.podcastSeen().asReversed())
    /** Previously clicked episodes, newest first. */
    val history: StateFlow<List<PodcastRec>> = _history.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var job: Job? = null

    /**
     * Ensures a fresh recommendation exists AND that the stockpile behind it
     * is topped up, so tapping the card swaps in the next episode instantly.
     * No-op when both are ready unless [force].
     */
    fun ensureRec(force: Boolean = false) {
        val net = netStore.active()
        if (!net.podcastEnabled) return // this net has podcasts turned off
        if (job?.isActive == true) return
        val needCurrent = force || !isFresh(store.currentPodcast())
        if (!needCurrent && store.podcastQueue().size >= STOCKPILE_TARGET) return
        job = scope.launch {
            val s = settingsStore.current()
            if (!s.llmConfigured) return@launch
            if (needCurrent) {
                _loading.value = true
                try {
                    val rec = generate(s, net)
                    if (rec != null) {
                        if (store.activeNetId != net.id) {
                            DebugLog.w(TAG, "net switched during recommendation — dropping it")
                        } else {
                            store.setPodcast(rec)
                            _rec.value = rec
                            DebugLog.i(TAG, "recommended: ${rec.show} — ${rec.title} url=${rec.url != null}")
                        }
                    } else {
                        DebugLog.w(TAG, "model returned no usable recommendation")
                    }
                } catch (e: Exception) {
                    DebugLog.e(TAG, "recommendation failed", e)
                } finally {
                    _loading.value = false
                }
            }
            if (netStore.active().id == net.id && netStore.active().podcastEnabled) {
                topUpStockpile(s, net)
            }
        }
    }

    /**
     * The active net changed (switch, create, delete, podcast toggle):
     * re-read the new net's podcast state; a net with podcasts disabled
     * shows nothing and keeps its store clean.
     */
    fun onNetChanged() {
        val net = netStore.active()
        if (!net.podcastEnabled) {
            if (store.currentPodcast() != null) store.clearPodcast()
            _rec.value = null
            _history.value = emptyList()
            return
        }
        _rec.value = store.currentPodcast()
        _history.value = store.podcastSeen().asReversed()
        ensureRec()
    }

    /** The shown episode was tapped: retire it, promote the next stockpiled
     *  episode instantly, and top the stockpile back up in the background. */
    fun onClicked(current: PodcastRec) {
        store.markPodcastClicked(current)
        while (true) {
            val next = store.dequeuePodcast() ?: break
            if (isFresh(next)) {
                store.setPodcast(next)
                break
            }
            DebugLog.w(TAG, "dropped stale stockpiled rec: ${next.show} — ${next.title}")
        }
        _rec.value = store.currentPodcast()
        _history.value = store.podcastSeen().asReversed()
        ensureRec()
    }

    /** Generates resolved spares until the stockpile reaches its target. */
    private suspend fun topUpStockpile(s: AppSettings, net: Net) {
        var misses = 0
        while (store.podcastQueue().size < STOCKPILE_TARGET && misses < 2) {
            if (netStore.active().id != net.id || !netStore.active().podcastEnabled) return
            val rec = try {
                generate(s, net)
            } catch (e: Exception) {
                DebugLog.e(TAG, "stockpile generation failed", e)
                null
            }
            if (rec == null) {
                misses++
                continue
            }
            if (store.activeNetId != net.id) return // net switched — stop stocking
            val before = store.podcastQueue().size
            store.enqueuePodcast(rec)
            if (store.podcastQueue().size == before) misses++ // dup dropped
            else DebugLog.i(
                TAG,
                "stockpiled: ${rec.show} — ${rec.title} (${store.podcastQueue().size}/$STOCKPILE_TARGET)"
            )
        }
    }

    private fun isFresh(rec: PodcastRec?): Boolean {
        if (rec == null) return false
        val age = System.currentTimeMillis() - rec.createdAt
        return if (rec.url != null || rec.feedUrl != null) age < STALE_MS
        else age < LINKLESS_STALE_MS
    }

    private suspend fun generate(s: AppSettings, net: Net): PodcastRec? {
        val stats = store.snapshotDomainStats()
        val answers = store.snapshotAnswers()
        val questions = store.snapshotQuestions().associateBy { it.id }

        // Weakest areas: lowest accuracy among domains with enough attempts.
        val weak = stats.filter { it.attempts >= 3 }
            .sortedWith(compareBy<DomainStat> { it.accuracy }.thenByDescending { it.attempts })
            .take(10)
        val weakLines = weak.map {
            "${it.path} — ${it.correct}/${it.attempts} (${(it.accuracy * 100).toInt()}%)"
        }

        // Recent misses (newest first) — concrete gaps the episode can fill.
        val wrongLines = answers.asReversed()
            .filter { !it.correct }
            .take(12)
            .mapNotNull { a ->
                val q = questions[a.questionId] ?: return@mapNotNull null
                "[${q.domains.firstOrNull() ?: "untagged"}] ${q.prompt}"
            }

        val summaryLines = store.snapshotSummaries().map { "${it.domain}: ${it.text}" }
        // Distinct shows of everything lined up or already suggested — the
        // prompt prefers variety across shows.
        val avoidLines = (
            listOfNotNull(store.currentPodcast()) + store.podcastQueue() + store.podcastSeen().asReversed()
            )
            .map { it.show }
            .distinct()
            .take(12)

        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)

        // Web tools (optional): let the model find/verify the episode URL.
        var session: McpSession? = null
        var budget = 0
        if (s.mcpBudget > 0) {
            val mcp = McpSession(s)
            if (mcp.connect()) {
                session = mcp
                budget = minOf(MAX_TOOL_CALLS, s.mcpBudget)
            }
        }

        val messages = ArrayList<LlmMessage>()
        messages.add(
            LlmMessage.user(
                Prompts.podcastPrompt(weakLines, wrongLines, summaryLines, avoidLines, toolsAvailable = budget > 0, net = net)
            )
        )

        var content: String? = null
        var round = 0
        while (round < MAX_TOOL_ROUNDS) {
            round++
            val assistant = llm.chat(
                cfg, messages,
                tools = if (budget > 0) session?.toolSpecs ?: emptyList() else emptyList(),
                temperature = 0.7f,
                maxTokens = MAX_TOKENS,
                disableThinking = s.disableThinking
            )
            messages.add(assistant)
            if (assistant.toolCalls.isEmpty()) {
                content = assistant.content
                break
            }
            for (call in assistant.toolCalls) {
                val result: String = if (budget <= 0) {
                    "Tool budget exhausted. Answer now with the best episode you are certain of."
                } else {
                    budget--
                    DebugLog.i(TAG, "tool call '${call.name}' args=${call.argumentsJson.take(120)}")
                    session?.call(call.name, call.argumentsJson) ?: "No web tools."
                }
                messages.add(LlmMessage.tool(call.id, call.name, result))
            }
        }

        var parsed = Prompts.parsePodcastRec(content ?: return null)
        if (parsed != null && parsed.url == null) {
            // One corrective retry: the link is the whole point of the card.
            DebugLog.w(TAG, "no url in recommendation — retrying for a link")
            parsed = try {
                val retry = llm.chat(
                    cfg,
                    listOf(LlmMessage.user(Prompts.podcastUrlRetryPrompt(parsed.show, parsed.title))),
                    temperature = 0.3f,
                    maxTokens = MAX_TOKENS,
                    disableThinking = s.disableThinking
                )
                Prompts.parsePodcastRec(retry.content ?: "")?.takeIf { it.url != null } ?: parsed
            } catch (e: Exception) {
                DebugLog.e(TAG, "url retry failed — keeping linkless rec", e)
                parsed
            }
        }
        // Ground the show in its real RSS feed via the iTunes directory —
        // the feed is what lets podcast apps subscribe to the exact show.
        return parsed?.let { PodcastDirectory.resolve(it) }
    }
}
