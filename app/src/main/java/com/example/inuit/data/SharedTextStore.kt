package com.example.inuit.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One piece of text the user SHARED to Inuit from anywhere on the phone
 * (Android share sheet → Inuit). It becomes raw material for question
 * generation via the "Shared snippets" source in a net's mix.
 */
data class SharedText(
    /** Stable id. */
    val id: String = UUID.randomUUID().toString(),
    /** The shared text, verbatim (trimmed). */
    val text: String,
    val sharedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("text", text); put("ts", sharedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): SharedText = SharedText(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            text = o.optString("text"),
            sharedAt = o.optLong("ts", 0L)
        )
    }
}

/**
 * Persisted pool of texts shared into the app. The pool is GLOBAL — any net
 * with the shared-text source weighted above zero draws from it; the per-net
 * dosage comes from the net's source mix.
 *
 * Storage mirrors GDriveFactStore's tiny-JSON-file idiom:
 * `inuit_shared_texts.json`, capped at [MAX_SNIPPETS] (oldest evicted).
 */
class SharedTextStore(context: Context) {

    private val file = File(context.filesDir, FILE)
    private val items = LinkedHashMap<String, SharedText>()

    init {
        load()
    }

    /** Current pool, newest first. */
    fun all(): List<SharedText> = items.values.sortedByDescending { it.sharedAt }

    /** Number of snippets in the pool. */
    fun size(): Int = items.size

    /**
     * Adds a snippet (deduped by exact text); persists synchronously.
     * Returns false for blank text or an exact duplicate.
     */
    @Synchronized
    fun add(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (items.values.any { it.text == trimmed }) return false
        val snippet = SharedText(text = trimmed.take(MAX_CHARS))
        items[snippet.id] = snippet
        while (items.size > MAX_SNIPPETS) {
            val oldest = items.entries.minByOrNull { it.value.sharedAt } ?: break
            items.remove(oldest.key)
        }
        persist()
        return true
    }

    /** Removes one snippet by id. */
    @Synchronized
    fun remove(id: String) {
        if (items.remove(id) != null) persist()
    }

    /** Renders the accent lines for one generation batch: newest first,
     *  one compact classified line per snippet. */
    fun accentLines(max: Int = Companion.MAX_LINES): List<String> =
        lines(all(), max)

    companion object {
        private const val FILE = "inuit_shared_texts.json"
        private const val TAG = "SharedTexts"

        /** Pool cap — old snippets roll off as new ones arrive. */
        const val MAX_SNIPPETS = 60

        /** Accent dosage: at most this many snippet lines per batch. */
        const val MAX_LINES = 5

        /** Characters of shared text per line. */
        const val CHARS_PER_LINE = 320

        /** Hard cap on a single stored snippet (share sheets can send whole articles). */
        const val MAX_CHARS = 8000

        /**
         * Heuristic kind of a snippet — pure, unit tested. A snippet with
         * full-sentence facts ("full facts") is quiz material itself; a few
         * words or a bare topic is INSPIRATION for questions, not material.
         * Rule of thumb: several sentences / sentence punctuation and enough
         * words ⇒ FACTS; anything shorter ⇒ TOPIC.
         */
        fun kindOf(text: String): SharedTextKind {
            val flat = text.trim()
            val words = flat.split(Regex("\\s+")).count { it.isNotBlank() }
            val sentences = Regex("[.!?](\\s|$)").findAll(flat).count()
            return if (words >= FACTS_MIN_WORDS && sentences >= FACTS_MIN_SENTENCES) {
                SharedTextKind.FACTS
            } else SharedTextKind.TOPIC
        }

        const val FACTS_MIN_WORDS = 20
        const val FACTS_MIN_SENTENCES = 2

        /** Pure renderer — newest first, one compact classified line per snippet. */
        fun lines(snippets: List<SharedText>, max: Int = MAX_LINES): List<String> =
            snippets.asSequence()
                .sortedByDescending { it.sharedAt }
                .take(max)
                .map { s ->
                    val flat = s.text.replace('\n', ' ').trim().let {
                        if (it.length <= CHARS_PER_LINE) it else it.take(CHARS_PER_LINE) + "…"
                    }
                    "- SHARED (${kindOf(s.text).label}): \"$flat\""
                }
                .toList()

        fun parse(text: String): List<SharedText> = try {
            val arr = JSONObject(text).optJSONArray("snippets") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { SharedText.fromJson(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }

        fun serialize(snippets: List<SharedText>): String = JSONObject().apply {
            put("version", 1)
            put("snippets", JSONArray().apply { snippets.forEach { put(it.toJson()) } })
        }.toString()
    }

    @Synchronized
    private fun load() {
        items.clear()
        try {
            if (file.exists()) {
                parse(file.readText()).forEach { items[it.id] = it }
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "shared-text store load failed: ${e.message}")
        }
    }

    @Synchronized
    private fun persist() {
        try {
            file.writeText(serialize(items.values.toList()))
        } catch (e: Exception) {
            DebugLog.w(TAG, "shared-text store persist failed: ${e.message}")
        }
    }
}

/** How a shared snippet should be treated by the generator. */
enum class SharedTextKind(val label: String) {
    /** Full factual statements — questions can quiz these facts and closely related material. */
    FACTS("facts"),
    /** A few words / a topic — use as INSPIRATION for question creation, not as quiz material. */
    TOPIC("topic")
}
