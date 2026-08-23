package com.example.inuit.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single-file-per-net JSON persistence for questions, answers, domain
 * stats, knowledge summaries, frontiers and podcast recommendations.
 *
 * NETS: every piece of data above belongs to exactly one net. The store
 * keeps one [NetState] per net (lazily loaded from that net's file) and
 * every read/write method operates on the ACTIVE net — so all existing
 * call sites (selector, generator, harvester, podcasts, stats) became
 * net-scoped without signature changes. The All net keeps the legacy
 * `inuit_store.json` filename, so pre-nets data migrates into the All
 * net for free; other nets live in `inuit_store_<netId>.json`.
 *
 * In-memory state is the source of truth while the app runs; writes are
 * atomic and debounced. Mutations are synchronized and fast; readers get
 * immutable snapshots. [dataVersion] ticks on every mutation (and on net
 * switches) so UI/ViewModel flows recompute.
 */
class QuestionStore(
    context: Context,
    private val scope: CoroutineScope,
    netStore: NetStore? = null
) {
    companion object {
        private const val TAG = "QuestionStore"
        private const val FILE = "inuit_store.json"
        private const val FILE_PREFIX = "inuit_store_"
        private const val FILE_SUFFIX = ".json"
        private const val PERSIST_DEBOUNCE_MS = 1200L

        /** The All net keeps the legacy filename; others are namespaced. */
        fun fileNameFor(netId: String): String =
            if (netId == Net.ALL_ID) FILE else "$FILE_PREFIX$netId$FILE_SUFFIX"
    }

    /** All mutable per-net data in one bundle. */
    private class NetState {
        val questions = mutableListOf<Question>()
        val byId = HashMap<String, Question>()
        val answers = mutableListOf<AnswerRecord>()
        val domainStats = LinkedHashMap<String, DomainStat>()
        val summaries = LinkedHashMap<String, KnowledgeSummary>()
        val frontiers = mutableListOf<String>()

        /** The podcast recommendation currently shown at the bottom of stats. */
        var podcastRec: PodcastRec? = null

        /** Recently clicked recommendations — fed back to the LLM to avoid repeats. */
        val podcastSeen = mutableListOf<PodcastRec>()

        /** Ready-to-show resolved recommendations — a click promotes the next
         *  one instantly instead of waiting for a fresh LLM + directory pass. */
        val podcastQueue = mutableListOf<PodcastRec>()

        /** The question currently on screen (shown but not yet answered). */
        var pendingId: String? = null

        /** Answer count already covered by this net's rolling summaries. */
        var summarizedAnswers: Int = 0
    }

    private val lock = Any()
    private val dir = context.filesDir
    private val netStates = LinkedHashMap<String, NetState>()

    /** The net every method below operates on; kept in sync with NetStore. */
    private var activeId: String = netStore?.active()?.id ?: Net.ALL_ID

    /** Serializes file writes: debounced and immediate persists may overlap. */
    private val fileLock = Any()

    private var persistJob: Job? = null

    /** Ticks on every mutation AND on net switch; combine to recompute. */
    val dataVersion = MutableStateFlow(0L)

    init {
        synchronized(lock) { activeState() } // load the active net's file
        // Follow net switches (All → Juggling → …): swap the active state.
        netStore?.let { ns ->
            scope.launch {
                ns.activeNet.collect { net -> switchNet(net.id) }
            }
        }
    }

    /** The net all reads/writes currently target. */
    val activeNetId: String get() = activeId

    /** Swaps the active net: flushes the old state to its file, loads the
     *  new one (first switch loads it from disk), ticks [dataVersion].
     *
     *  Called SYNCHRONOUSLY by NetStore (via its onNetChanged hook) before
     *  the activeNet flow publishes — so every consumer reacting to the
     *  switch already sees this net active. The async collector in [init]
     *  stays wired as an idempotent safety net. The incoming net must load
     *  inline (its data is read immediately afterwards); the outgoing flush
     *  is offloaded to IO because this now runs on the main thread. */
    fun switchNet(netId: String) {
        var outgoing: Pair<String, JSONObject>? = null
        val changed = synchronized(lock) {
            if (netId == activeId) return
            outgoing = activeId to serializeState(activeState()) // snapshot outgoing net
            activeId = netId
            activeState() // load incoming net if not cached
            true
        }
        if (changed) {
            outgoing?.let { (id, payload) ->
                scope.launch(Dispatchers.IO) { writePayload(id, payload) }
            }
            Log.i(TAG, "switched to net $netId")
            bump()
        }
    }

    /** Erases a net's in-memory state and data file (net deletion). */
    fun deleteNetData(netId: String) {
        if (netId == Net.ALL_ID) return
        synchronized(lock) {
            netStates.remove(netId)
            if (activeId == netId) {
                activeId = Net.ALL_ID
                activeState()
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                File(dir, fileNameFor(netId)).delete()
            } catch (e: Exception) {
                Log.e(TAG, "net file delete failed", e)
            }
        }
        bump()
    }

    /** Active state, loading it from disk on first access. Call under [lock]. */
    private fun activeState(): NetState =
        netStates.getOrPut(activeId) { loadState(activeId) }

    // ── Reads (active net) ───────────────────────────────────────────────

    fun snapshotQuestions(): List<Question> = synchronized(lock) { activeState().questions.toList() }
    fun snapshotAnswers(): List<AnswerRecord> = synchronized(lock) { activeState().answers.toList() }
    fun snapshotDomainStats(): List<DomainStat> = synchronized(lock) { activeState().domainStats.values.toList() }
    fun snapshotSummaries(): List<KnowledgeSummary> = synchronized(lock) { activeState().summaries.values.toList() }
    fun snapshotFrontiers(): List<String> = synchronized(lock) { activeState().frontiers.toList() }
    fun currentPodcast(): PodcastRec? = synchronized(lock) { activeState().podcastRec }
    fun podcastSeen(): List<PodcastRec> = synchronized(lock) { activeState().podcastSeen.toList() }
    fun podcastQueue(): List<PodcastRec> = synchronized(lock) { activeState().podcastQueue.toList() }
    fun questionById(id: String): Question? = synchronized(lock) { activeState().byId[id] }

    /** Answer count already folded into this net's rolling summaries. */
    fun summarizedAnswers(): Int = synchronized(lock) { activeState().summarizedAnswers }

    /** The persisted on-screen question, if it is still unanswered. */
    fun pendingQuestion(): Question? = synchronized(lock) {
        val st = activeState()
        st.pendingId?.let { st.byId[it] }?.takeIf { it.servedCount == 0 }
    }

    /** Unserved questions = the live queue. */
    fun queue(): List<Question> = synchronized(lock) { activeState().questions.filter { it.servedCount == 0 } }

    fun queueSize(): Int = synchronized(lock) { activeState().questions.count { it.servedCount == 0 } }

    // ── Writes (active net) ──────────────────────────────────────────────

    fun insertQuestions(newQuestions: List<Question>) {
        if (newQuestions.isEmpty()) return
        synchronized(lock) {
            val st = activeState()
            for (q in newQuestions) {
                if (st.byId.containsKey(q.id)) continue
                st.questions.add(q)
                st.byId[q.id] = q
            }
        }
        bump()
    }

    /** Persists which question is on screen; survives app close / process death. */
    fun setPendingQuestion(id: String?) {
        synchronized(lock) { activeState().pendingId = id }
        bump()
        persistImmediately()
    }

    /** Records an answer; updates the question, domain stats and streak data.
     *  Answers are persisted immediately — they must survive process death. */
    fun recordAnswer(questionId: String, correct: Boolean, userAnswer: String, elapsedMs: Long): AnswerRecord? {
        val record: AnswerRecord
        synchronized(lock) {
            val st = activeState()
            val q = st.byId[questionId] ?: return null
            val updated = q.copy(servedCount = q.servedCount + 1)
            val idx = st.questions.indexOfFirst { it.id == questionId }
            if (idx >= 0) st.questions[idx] = updated
            st.byId[questionId] = updated
            record = AnswerRecord(questionId = questionId, correct = correct, userAnswer = userAnswer, elapsedMs = elapsedMs)
            st.answers.add(record)
            for (path in updated.domains) {
                val s = st.domainStats.getOrPut(path) { DomainStat(path) }
                st.domainStats[path] = s.copy(
                    attempts = s.attempts + 1,
                    correct = s.correct + if (correct) 1 else 0,
                    lastSeen = record.timestamp
                )
            }
        }
        bump()
        persistImmediately()
        return record
    }

    /** Skip: stays in the queue but with a penalty so it doesn't reappear immediately. */
    fun markSkipped(questionId: String) {
        synchronized(lock) {
            val st = activeState()
            val q = st.byId[questionId] ?: return
            val updated = q.copy(skipCount = q.skipCount + 1)
            val idx = st.questions.indexOfFirst { it.id == questionId }
            if (idx >= 0) st.questions[idx] = updated
            st.byId[questionId] = updated
        }
        bump()
    }

    fun replaceSummaries(newSummaries: List<KnowledgeSummary>) {
        synchronized(lock) {
            val st = activeState()
            st.summaries.clear()
            for (s in newSummaries) st.summaries[s.domain] = s
        }
        bump()
    }

    fun replaceFrontiers(list: List<String>) {
        synchronized(lock) {
            val st = activeState()
            st.frontiers.clear()
            st.frontiers.addAll(list.distinct().take(40))
        }
        bump()
    }

    /** Answer count folded into this net's rolling summaries. */
    fun setSummarizedAnswers(count: Int) {
        synchronized(lock) { activeState().summarizedAnswers = count }
        bump()
    }

    /** Stores a freshly generated podcast recommendation (immediate persist). */
    fun setPodcast(rec: PodcastRec) {
        synchronized(lock) { activeState().podcastRec = rec }
        bump()
        persistImmediately()
    }

    /** Retires the shown recommendation (net podcasts disabled / net switch). */
    fun clearPodcast() {
        synchronized(lock) { activeState().podcastRec = null }
        bump()
        persistImmediately()
    }

    /** Adds a resolved episode to the ready stockpile (deduped, capped). */
    fun enqueuePodcast(rec: PodcastRec) {
        synchronized(lock) {
            val st = activeState()
            val dup = (listOfNotNull(st.podcastRec) + st.podcastQueue + st.podcastSeen)
                .any { it.show == rec.show && it.title == rec.title }
            if (dup) return
            st.podcastQueue.add(rec)
            if (st.podcastQueue.size > 5) st.podcastQueue.removeAt(0)
        }
        bump()
        persistImmediately()
    }

    /** Pops the oldest stockpiled rec, if any (freshness is the caller's call). */
    fun dequeuePodcast(): PodcastRec? {
        val rec = synchronized(lock) {
            val st = activeState()
            if (st.podcastQueue.isEmpty()) null else st.podcastQueue.removeAt(0)
        } ?: return null
        bump()
        persistImmediately()
        return rec
    }

    /** The shown episode was tapped: retire it and remember it for dedup. */
    fun markPodcastClicked(rec: PodcastRec) {
        synchronized(lock) {
            val st = activeState()
            st.podcastSeen.add(rec.copy(clickedAt = System.currentTimeMillis()))
            if (st.podcastSeen.size > 30) st.podcastSeen.removeAt(0)
            if (st.podcastRec?.let { it.show == rec.show && it.title == rec.title } == true) {
                st.podcastRec = null
            }
        }
        bump()
        persistImmediately()
    }

    // ── Persistence ──────────────────────────────────────────────────────

    private fun bump() {
        dataVersion.value = dataVersion.value + 1
        schedulePersist()
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch(Dispatchers.IO) {
            delay(PERSIST_DEBOUNCE_MS)
            persistNow()
        }
    }

    /** Fire-and-forget immediate write (answers, pending question, batches) —
     *  used when losing the last 1.2s of state would hurt. */
    fun persistImmediately() {
        scope.launch(Dispatchers.IO) { persistNow() }
    }

    /** Writes every loaded net state to its own file. */
    fun persistNow() {
        try {
            val snapshots = synchronized(lock) {
                netStates.keys.toList() to netStates.entries.associate { it.key to serializeState(it.value) }
            }
            for ((netId, payload) in snapshots.second) {
                writePayload(netId, payload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "persist failed", e)
        }
    }

    private fun serializeState(st: NetState): JSONObject = JSONObject().apply {
        put("version", 1)
        put("questions", JSONArray().apply { st.questions.forEach { put(it.toJson()) } })
        put("answers", JSONArray().apply { st.answers.forEach { put(it.toJson()) } })
        put("summaries", JSONObject().apply { st.summaries.values.forEach { put(it.domain, JSONObject().apply { put("text", it.text); put("ts", it.createdAt); put("n", it.coveredAnswers) }) } })
        put("frontiers", JSONArray(st.frontiers))
        st.podcastRec?.let { put("podcast", it.toJson()) }
        put("podcastQ", JSONArray().apply { st.podcastQueue.forEach { put(it.toJson()) } })
        put("podcastSeen", JSONArray().apply { st.podcastSeen.forEach { put(it.toJson()) } })
        st.pendingId?.let { put("pending", it) }
        put("summarized", st.summarizedAnswers)
    }

    private fun writePayload(netId: String, payload: JSONObject) {
        val name = fileNameFor(netId)
        synchronized(fileLock) {
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(payload.toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    /** Loads one net's state from its file (empty state when absent). */
    private fun loadState(netId: String): NetState {
        val st = NetState()
        val file = File(dir, fileNameFor(netId))
        if (!file.exists()) return st
        try {
            val root = JSONObject(file.readText())
            val qs = root.optJSONArray("questions") ?: JSONArray()
            for (i in 0 until qs.length()) {
                val q = Question.fromJson(qs.optJSONObject(i) ?: continue)
                st.questions.add(q)
                st.byId[q.id] = q
            }
            val as_ = root.optJSONArray("answers") ?: JSONArray()
            for (i in 0 until as_.length()) {
                st.answers.add(AnswerRecord.fromJson(as_.optJSONObject(i) ?: continue))
            }
            // Rebuild domain stats from answers (cheap, keeps stats consistent).
            val qById = HashMap<String, Question>(st.byId)
            val stats = HashMap<String, DomainStat>()
            for (a in st.answers) {
                val q = qById[a.questionId] ?: continue
                for (path in q.domains) {
                    val s = stats.getOrPut(path) { DomainStat(path) }
                    stats[path] = s.copy(
                        attempts = s.attempts + 1,
                        correct = s.correct + if (a.correct) 1 else 0,
                        lastSeen = maxOf(s.lastSeen, a.timestamp)
                    )
                }
            }
            st.domainStats.putAll(stats)
            val sm = root.optJSONObject("summaries")
            if (sm != null) {
                for (key in sm.keys()) {
                    val o = sm.optJSONObject(key) ?: continue
                    st.summaries[key] = KnowledgeSummary(key, o.optString("text"), o.optLong("ts"), o.optInt("n"))
                }
            }
            val fr = root.optJSONArray("frontiers")
            if (fr != null) for (i in 0 until fr.length()) st.frontiers.add(fr.optString(i))
            st.podcastRec = root.optJSONObject("podcast")?.let { PodcastRec.fromJson(it) }
            val pQ = root.optJSONArray("podcastQ")
            if (pQ != null) for (i in 0 until pQ.length()) {
                st.podcastQueue.add(PodcastRec.fromJson(pQ.optJSONObject(i) ?: continue))
            }
            val pSeen = root.optJSONArray("podcastSeen")
            if (pSeen != null) for (i in 0 until pSeen.length()) {
                st.podcastSeen.add(PodcastRec.fromJson(pSeen.optJSONObject(i) ?: continue))
            }
            st.pendingId = root.optString("pending").ifBlank { null }
            st.summarizedAnswers = root.optInt("summarized", 0)
            Log.i(TAG, "net $netId: loaded ${st.questions.size} questions, ${st.answers.size} answers")
        } catch (e: Exception) {
            Log.e(TAG, "net $netId load failed — starting empty", e)
        }
        return st
    }
}
