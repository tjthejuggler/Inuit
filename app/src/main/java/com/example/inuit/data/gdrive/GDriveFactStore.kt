package com.example.inuit.data.gdrive

import android.content.Context
import com.example.inuit.data.DebugLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One distilled Gemini-export document: the key facts worth remembering. */
data class GDriveFact(
    /** Drive file id — dedup guard so a failed trash never double-ingests. */
    val docId: String,
    /** Document title (usually the prompt that started the conversation). */
    val title: String,
    /** The distilled key facts / focal points, compact text. */
    val facts: String,
    val ingestedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("docId", docId); put("title", title)
        put("facts", facts); put("ts", ingestedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): GDriveFact = GDriveFact(
            docId = o.optString("docId"),
            title = o.optString("title"),
            facts = o.optString("facts"),
            ingestedAt = o.optLong("ts", 0L)
        )
    }
}

/**
 * Persisted pool of distilled facts from Gemini-export documents.
 *
 * The pool is GLOBAL (one Drive, one user) — any net with the Drive-doc
 * accent toggled on draws from it; the per-net dosage comes from the net's
 * source mix and the prompt keeps questions inside the net's scope.
 *
 * Storage mirrors [com.example.inuit.data.NetStore]'s tiny-JSON-file idiom:
 * `inuit_gdrive_facts.json`, capped at [MAX_FACTS] (oldest evicted).
 */
class GDriveFactStore(context: Context) {

    private val file = File(context.filesDir, FILE)
    private val facts = LinkedHashMap<String, GDriveFact>()
    private val knownDocIds = HashSet<String>()

    init {
        load()
    }

    /** Current pool, newest first. */
    fun all(): List<GDriveFact> = facts.values.sortedByDescending { it.ingestedAt }

    /** True when this Drive document was already ingested. */
    fun hasDoc(docId: String): Boolean = docId in knownDocIds

    /** Number of facts in the pool. */
    fun size(): Int = facts.size

    /**
     * Adds (or replaces) the fact for a document; persists synchronously —
     * the document is deleted from Drive right after this returns.
     */
    @Synchronized
    fun put(fact: GDriveFact) {
        facts.remove(fact.docId)
        facts[fact.docId] = fact
        knownDocIds.add(fact.docId)
        while (facts.size > MAX_FACTS) {
            val oldest = facts.entries.minByOrNull { it.value.ingestedAt } ?: break
            facts.remove(oldest.key)
        }
        persist()
    }

    /** Renders the accent lines for one generation batch (pure — tested via
     *  [Companion.lines]): newest first, one compact line per fact. */
    fun accentLines(max: Int = MAX_LINES): List<String> =
        lines(all(), max)

    companion object {
        private const val FILE = "inuit_gdrive_facts.json"
        private const val TAG = "GDriveFacts"

        /** Pool cap — old ingests roll off as new documents arrive. */
        const val MAX_FACTS = 60

        /** Accent dosage: at most this many document lines per batch. */
        const val MAX_LINES = 5

        /** Characters of fact text per line. */
        const val CHARS_PER_LINE = 220

        /** Pure renderer — newest first, one compact line per fact. */
        fun lines(facts: List<GDriveFact>, max: Int = MAX_LINES): List<String> =
            facts.asSequence()
                .sortedByDescending { it.ingestedAt }
                .take(max)
                .map { f ->
                    val flat = f.facts.replace('\n', ' ').trim().let {
                        if (it.length <= CHARS_PER_LINE) it else it.take(CHARS_PER_LINE) + "…"
                    }
                    "- DOC \"${f.title.take(80)}\": $flat"
                }
                .toList()

        fun parse(text: String): List<GDriveFact> = try {
            val arr = JSONObject(text).optJSONArray("facts") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { GDriveFact.fromJson(it) }
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "fact store parse failed — starting empty: ${e.message}")
            emptyList()
        }

        fun serialize(facts: List<GDriveFact>): String = JSONObject().apply {
            put("version", 1)
            put("facts", JSONArray().apply { facts.forEach { put(it.toJson()) } })
        }.toString()
    }

    @Synchronized
    private fun load() {
        facts.clear()
        knownDocIds.clear()
        try {
            if (file.exists()) {
                parse(file.readText()).forEach {
                    facts[it.docId] = it
                    knownDocIds.add(it.docId)
                }
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "fact store load failed: ${e.message}")
        }
    }

    @Synchronized
    private fun persist() {
        try {
            file.writeText(serialize(facts.values.toList()))
        } catch (e: Exception) {
            DebugLog.w(TAG, "fact store persist failed: ${e.message}")
        }
    }
}
