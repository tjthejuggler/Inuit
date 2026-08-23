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
 * Single-file JSON persistence for questions, answers, domain stats,
 * knowledge summaries and the frontier list. In-memory state is the source
 * of truth while the app runs; writes are atomic and debounced.
 *
 * Mutations are synchronized and fast; readers get immutable snapshots.
 * [dataVersion] ticks on every mutation so UI/ViewModel flows can recompute.
 */
class QuestionStore(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "QuestionStore"
        private const val FILE = "inuit_store.json"
        private const val PERSIST_DEBOUNCE_MS = 1200L
    }

    private val lock = Any()
    private val questions = mutableListOf<Question>()
    private val byId = HashMap<String, Question>()
    private val answers = mutableListOf<AnswerRecord>()
    private val domainStats = LinkedHashMap<String, DomainStat>()
    private val summaries = LinkedHashMap<String, KnowledgeSummary>()
    private val frontiers = mutableListOf<String>()

    /** The podcast recommendation currently shown at the bottom of stats. */
    private var podcastRec: PodcastRec? = null

    /** Recently clicked recommendations — fed back to the LLM to avoid repeats. */
    private val podcastSeen = mutableListOf<PodcastRec>()

    /** Ready-to-show resolved recommendations — a click promotes the next
     *  one instantly instead of waiting for a fresh LLM + directory pass. */
    private val podcastQueue = mutableListOf<PodcastRec>()

    /** The question currently on screen (shown but not yet answered).
     *  Persisted so closing the app never wastes it — it comes back on restart. */
    private var pendingId: String? = null

    /** Serializes file writes: debounced and immediate persists may overlap. */
    private val fileLock = Any()

    private var persistJob: Job? = null
    private val file = File(context.filesDir, FILE)

    /** Ticks on every mutation; combine with it to recompute derived state. */
    val dataVersion = MutableStateFlow(0L)

    init {
        load()
    }

    // ── Reads ────────────────────────────────────────────────────────────

    fun snapshotQuestions(): List<Question> = synchronized(lock) { questions.toList() }
    fun snapshotAnswers(): List<AnswerRecord> = synchronized(lock) { answers.toList() }
    fun snapshotDomainStats(): List<DomainStat> = synchronized(lock) { domainStats.values.toList() }
    fun snapshotSummaries(): List<KnowledgeSummary> = synchronized(lock) { summaries.values.toList() }
    fun snapshotFrontiers(): List<String> = synchronized(lock) { frontiers.toList() }
    fun currentPodcast(): PodcastRec? = synchronized(lock) { podcastRec }
    fun podcastSeen(): List<PodcastRec> = synchronized(lock) { podcastSeen.toList() }
    fun podcastQueue(): List<PodcastRec> = synchronized(lock) { podcastQueue.toList() }
    fun questionById(id: String): Question? = synchronized(lock) { byId[id] }

    /** The persisted on-screen question, if it is still unanswered. */
    fun pendingQuestion(): Question? = synchronized(lock) {
        pendingId?.let { byId[it] }?.takeIf { it.servedCount == 0 }
    }

    /** Unserved questions = the live queue. */
    fun queue(): List<Question> = synchronized(lock) { questions.filter { it.servedCount == 0 } }

    fun queueSize(): Int = synchronized(lock) { questions.count { it.servedCount == 0 } }

    // ── Writes ───────────────────────────────────────────────────────────

    fun insertQuestions(newQuestions: List<Question>) {
        if (newQuestions.isEmpty()) return
        synchronized(lock) {
            for (q in newQuestions) {
                if (byId.containsKey(q.id)) continue
                questions.add(q)
                byId[q.id] = q
            }
        }
        bump()
    }

    /** Persists which question is on screen; survives app close / process death. */
    fun setPendingQuestion(id: String?) {
        synchronized(lock) { pendingId = id }
        bump()
        persistImmediately()
    }

    /** Records an answer; updates the question, domain stats and streak data.
     *  Answers are persisted immediately — they must survive process death. */
    fun recordAnswer(questionId: String, correct: Boolean, userAnswer: String, elapsedMs: Long): AnswerRecord? {
        val record: AnswerRecord
        synchronized(lock) {
            val q = byId[questionId] ?: return null
            val updated = q.copy(servedCount = q.servedCount + 1)
            val idx = questions.indexOfFirst { it.id == questionId }
            if (idx >= 0) questions[idx] = updated
            byId[questionId] = updated
            record = AnswerRecord(questionId = questionId, correct = correct, userAnswer = userAnswer, elapsedMs = elapsedMs)
            answers.add(record)
            for (path in updated.domains) {
                val s = domainStats.getOrPut(path) { DomainStat(path) }
                domainStats[path] = s.copy(
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
            val q = byId[questionId] ?: return
            val updated = q.copy(skipCount = q.skipCount + 1)
            val idx = questions.indexOfFirst { it.id == questionId }
            if (idx >= 0) questions[idx] = updated
            byId[questionId] = updated
        }
        bump()
    }

    fun replaceSummaries(newSummaries: List<KnowledgeSummary>) {
        synchronized(lock) {
            summaries.clear()
            for (s in newSummaries) summaries[s.domain] = s
        }
        bump()
    }

    fun replaceFrontiers(list: List<String>) {
        synchronized(lock) {
            frontiers.clear()
            frontiers.addAll(list.distinct().take(40))
        }
        bump()
    }

    /** Stores a freshly generated podcast recommendation (immediate persist). */
    fun setPodcast(rec: PodcastRec) {
        synchronized(lock) { podcastRec = rec }
        bump()
        persistImmediately()
    }

    /** Adds a resolved episode to the ready stockpile (deduped, capped). */
    fun enqueuePodcast(rec: PodcastRec) {
        synchronized(lock) {
            val dup = (listOfNotNull(podcastRec) + podcastQueue + podcastSeen)
                .any { it.show == rec.show && it.title == rec.title }
            if (dup) return
            podcastQueue.add(rec)
            if (podcastQueue.size > 5) podcastQueue.removeAt(0)
        }
        bump()
        persistImmediately()
    }

    /** Pops the oldest stockpiled rec, if any (freshness is the caller's call). */
    fun dequeuePodcast(): PodcastRec? {
        val rec = synchronized(lock) {
            if (podcastQueue.isEmpty()) null else podcastQueue.removeAt(0)
        } ?: return null
        bump()
        persistImmediately()
        return rec
    }

    /** The shown episode was tapped: retire it and remember it for dedup. */
    fun markPodcastClicked(rec: PodcastRec) {
        synchronized(lock) {
            podcastSeen.add(rec.copy(clickedAt = System.currentTimeMillis()))
            if (podcastSeen.size > 30) podcastSeen.removeAt(0)
            if (podcastRec?.let { it.show == rec.show && it.title == rec.title } == true) {
                podcastRec = null
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

    fun persistNow() {
        try {
            val payload = synchronized(lock) {
                JSONObject().apply {
                    put("version", 1)
                    put("questions", JSONArray().apply { questions.forEach { put(it.toJson()) } })
                    put("answers", JSONArray().apply { answers.forEach { put(it.toJson()) } })
                    put("summaries", JSONObject().apply { summaries.values.forEach { put(it.domain, JSONObject().apply { put("text", it.text); put("ts", it.createdAt); put("n", it.coveredAnswers) }) } })
                    put("frontiers", JSONArray(frontiers))
                    podcastRec?.let { put("podcast", it.toJson()) }
                    put("podcastQ", JSONArray().apply { podcastQueue.forEach { put(it.toJson()) } })
                    put("podcastSeen", JSONArray().apply { podcastSeen.forEach { put(it.toJson()) } })
                    pendingId?.let { put("pending", it) }
                }
            }
            synchronized(fileLock) {
                val tmp = File(file.parentFile, FILE + ".tmp")
                tmp.writeText(payload.toString())
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "persist failed", e)
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val qs = root.optJSONArray("questions") ?: JSONArray()
            for (i in 0 until qs.length()) {
                val q = Question.fromJson(qs.optJSONObject(i) ?: continue)
                questions.add(q)
                byId[q.id] = q
            }
            val as_ = root.optJSONArray("answers") ?: JSONArray()
            for (i in 0 until as_.length()) {
                answers.add(AnswerRecord.fromJson(as_.optJSONObject(i) ?: continue))
            }
            // Rebuild domain stats from answers (cheap, keeps stats consistent).
            val qById = HashMap<String, Question>(byId)
            val stats = HashMap<String, DomainStat>()
            for (a in answers) {
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
            domainStats.putAll(stats)
            val sm = root.optJSONObject("summaries")
            if (sm != null) {
                for (key in sm.keys()) {
                    val o = sm.optJSONObject(key) ?: continue
                    summaries[key] = KnowledgeSummary(key, o.optString("text"), o.optLong("ts"), o.optInt("n"))
                }
            }
            val fr = root.optJSONArray("frontiers")
            if (fr != null) for (i in 0 until fr.length()) frontiers.add(fr.optString(i))
            podcastRec = root.optJSONObject("podcast")?.let { PodcastRec.fromJson(it) }
            val pQ = root.optJSONArray("podcastQ")
            if (pQ != null) for (i in 0 until pQ.length()) {
                podcastQueue.add(PodcastRec.fromJson(pQ.optJSONObject(i) ?: continue))
            }
            val pSeen = root.optJSONArray("podcastSeen")
            if (pSeen != null) for (i in 0 until pSeen.length()) {
                podcastSeen.add(PodcastRec.fromJson(pSeen.optJSONObject(i) ?: continue))
            }
            pendingId = root.optString("pending").ifBlank { null }
            Log.i(TAG, "loaded ${questions.size} questions, ${answers.size} answers")
        } catch (e: Exception) {
            Log.e(TAG, "load failed — starting empty", e)
        }
    }
}
