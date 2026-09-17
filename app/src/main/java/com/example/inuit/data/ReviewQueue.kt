package com.example.inuit.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One "scored in error" dispute waiting for its LLM pass. Persisted so a
 * request survives process death: submitting always queues it, and it is
 * reviewed at the next opportunity (launch, resume, LLM settings saved).
 */
data class ReviewRequest(
    /** The net whose store holds this answer record. */
    val netId: String,
    val answerRecordId: String,
    val questionId: String,
    /** Raw stored answer (MC = choice index). */
    val userAnswer: String,
    /** The app's original grading, as disputed. */
    val gradedCorrect: Boolean,
    val queuedAtMs: Long = System.currentTimeMillis(),
    /** Failed LLM attempts so far — dropped after [ReviewQueue.MAX_ATTEMPTS]. */
    val attempts: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("netId", netId)
        put("recordId", answerRecordId)
        put("questionId", questionId)
        put("answer", userAnswer)
        put("gradedCorrect", gradedCorrect)
        put("queuedAtMs", queuedAtMs)
        put("attempts", attempts)
    }

    companion object {
        fun fromJson(o: JSONObject): ReviewRequest = ReviewRequest(
            netId = o.optString("netId", Net.ALL_ID),
            answerRecordId = o.optString("recordId", ""),
            questionId = o.optString("questionId", ""),
            userAnswer = o.optString("answer", ""),
            gradedCorrect = o.optBoolean("gradedCorrect", false),
            queuedAtMs = o.optLong("queuedAtMs", 0L),
            attempts = o.optInt("attempts", 0)
        )
    }
}

/**
 * File-backed FIFO of dispute requests (`inuit_review_queue.json`).
 *
 * Submitting a dispute always lands here first — even when it also runs
 * right away — so an app kill mid-review can never lose the request. The
 * newest request goes to the FRONT (the user is looking at its flash
 * banner); older queued items trail behind it. Draining is the caller's
 * job: it needs a configured LLM and applies each verdict to the request's
 * own net via [QuestionStore.overturnAnswerFor]. Writes are tiny and rare
 * → synchronous atomic writes (tmp + rename) instead of debounce machinery.
 */
class ReviewQueue(filesDir: File) {

    companion object {
        private const val TAG = "ReviewQueue"
        private const val FILE = "inuit_review_queue.json"

        /** Queue cap — oldest entries dropped FIFO beyond this. */
        const val MAX = 20

        /** A request is dropped after this many failed LLM attempts. */
        const val MAX_ATTEMPTS = 3
    }

    private val lock = Any()
    private val file = File(filesDir, FILE)
    private val items = ArrayList<ReviewRequest>()

    init {
        synchronized(lock) { load() }
    }

    /** Pending request count. */
    val size: Int
        get() = synchronized(lock) { items.size }

    /** Snapshot, front = next to drain. */
    fun all(): List<ReviewRequest> = synchronized(lock) { items.toList() }

    /** The next request to drain, or null when empty. */
    fun peek(): ReviewRequest? = synchronized(lock) { items.firstOrNull() }

    /** Inserts at the front, replacing any earlier request for the same answer. */
    fun enqueueFront(request: ReviewRequest) {
        synchronized(lock) {
            items.removeAll { it.answerRecordId == request.answerRecordId }
            items.add(0, request)
            while (items.size > MAX) items.removeAt(items.size - 1)
            persist()
        }
    }

    fun remove(answerRecordId: String) {
        synchronized(lock) {
            if (items.removeAll { it.answerRecordId == answerRecordId }) persist()
        }
    }

    /** Records a failed attempt (request is kept and retried on the next
     *  drain trigger; dropped by the drainer once [MAX_ATTEMPTS] is hit). */
    fun updateAttempts(answerRecordId: String, attempts: Int) {
        synchronized(lock) {
            val idx = items.indexOfFirst { it.answerRecordId == answerRecordId }
            if (idx >= 0) {
                items[idx] = items[idx].copy(attempts = attempts)
                persist()
            }
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONObject(file.readText()).optJSONArray("items") ?: return
            for (i in 0 until arr.length()) {
                val r = ReviewRequest.fromJson(arr.getJSONObject(i))
                if (r.answerRecordId.isNotEmpty() && r.questionId.isNotEmpty()) items.add(r)
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "load failed: ${e.message}")
        }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            for (r in items) arr.put(r.toJson())
            val json = JSONObject().put("items", arr).toString()
            val tmp = File(file.parentFile, "$FILE.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                // Some filesystems refuse rename-onto-existing: write directly.
                file.writeText(json)
                tmp.delete()
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "persist failed: ${e.message}")
        }
    }
}
