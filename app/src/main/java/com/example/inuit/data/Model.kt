package com.example.inuit.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Core domain model for Inuit.
 *
 * IMPORTANT INVARIANT: [Question] carries the correct answer (needed for local
 * grading and for the generator to build consistent sub-questions) but NO UI
 * path may ever render it. The app is Socratic: it never reveals answers.
 */

enum class QuestionType(val displayName: String) {
    TRUE_FALSE("True / False"),
    MULTIPLE_CHOICE("Multiple choice"),
    NUMERIC("Numeric"),
    FILL_BLANK("Fill in the blank");

    companion object {
        fun from(s: String?): QuestionType =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: MULTIPLE_CHOICE
    }
}

data class Question(
    val id: String = UUID.randomUUID().toString(),
    val type: QuestionType = QuestionType.MULTIPLE_CHOICE,
    val prompt: String = "",
    /** Multiple choice only: 3-5 options, display-shuffled by the UI. */
    val choices: List<String> = emptyList(),
    /** Multiple choice: index into [choices]. */
    val answerIndex: Int? = null,
    /** True/false answer. */
    val answerBool: Boolean? = null,
    /** Numeric answer. */
    val answerNumber: Double? = null,
    /** Numeric tolerance (absolute). Null → exact match required. */
    val tolerance: Double? = null,
    /** Optional unit hint shown next to the numeric input (never the answer). */
    val unit: String? = null,
    /** Fill-in-the-blank accepted answers (first is canonical). */
    val acceptedAnswers: List<String> = emptyList(),
    /** Hierarchical domain tags, e.g. "Science > Physics > Optics". */
    val domains: List<String> = emptyList(),
    /** 1 (trivial) .. 5 (expert). */
    val difficulty: Int = 3,
    /** Sub-question lineage: direct parent, and the root of the lineage. */
    val parentId: String? = null,
    val rootId: String? = null,
    /** Generator self-reported certainty 0..1 (anti-hallucination filter). */
    val confidence: Double = 0.8,
    /** Passed the second-pass verifier (if enabled). */
    val verified: Boolean = false,
    /** "core" or "mcp:<server>:<tool>" when grounded via web tools. */
    val source: String = "core",
    /** Internal audit note; NEVER shown to the user. */
    val rationale: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val servedCount: Int = 0,
    val skipCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("prompt", prompt)
        put("choices", JSONArray(choices))
        answerIndex?.let { put("answerIndex", it) }
        answerBool?.let { put("answerBool", it) }
        answerNumber?.let { put("answerNumber", it) }
        tolerance?.let { put("tolerance", it) }
        unit?.let { put("unit", it) }
        put("accepted", JSONArray(acceptedAnswers))
        put("domains", JSONArray(domains))
        put("difficulty", difficulty)
        parentId?.let { put("parentId", it) }
        rootId?.let { put("rootId", it) }
        put("confidence", confidence)
        put("verified", verified)
        put("source", source)
        rationale?.let { put("rationale", it) }
        put("createdAt", createdAt)
        put("servedCount", servedCount)
        put("skipCount", skipCount)
    }

    companion object {
        fun fromJson(o: JSONObject): Question = Question(
            id = o.optString("id", UUID.randomUUID().toString()),
            type = QuestionType.from(o.optString("type")),
            prompt = o.optString("prompt", ""),
            choices = o.optJSONArray("choices").toStringList(),
            answerIndex = if (o.has("answerIndex") && !o.isNull("answerIndex")) o.optInt("answerIndex") else null,
            answerBool = if (o.has("answerBool") && !o.isNull("answerBool")) o.optBoolean("answerBool") else null,
            answerNumber = if (o.has("answerNumber") && !o.isNull("answerNumber")) o.optDouble("answerNumber") else null,
            tolerance = if (o.has("tolerance") && !o.isNull("tolerance")) o.optDouble("tolerance") else null,
            unit = o.optString("unit").ifBlank { null },
            acceptedAnswers = o.optJSONArray("accepted").toStringList(),
            domains = o.optJSONArray("domains").toStringList(),
            difficulty = o.optInt("difficulty", 3).coerceIn(1, 5),
            parentId = o.optString("parentId").ifBlank { null },
            rootId = o.optString("rootId").ifBlank { null },
            confidence = o.optDouble("confidence", 0.8),
            verified = o.optBoolean("verified", false),
            source = o.optString("source", "core"),
            rationale = o.optString("rationale").ifBlank { null },
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            servedCount = o.optInt("servedCount", 0),
            skipCount = o.optInt("skipCount", 0)
        )
    }
}

/** One answered question. `userAnswer` is what the user typed/chose (never the correct answer). */
data class AnswerRecord(
    val id: String = UUID.randomUUID().toString(),
    val questionId: String,
    val correct: Boolean,
    val userAnswer: String,
    val timestamp: Long = System.currentTimeMillis(),
    val elapsedMs: Long = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("q", questionId); put("ok", correct)
        put("ans", userAnswer); put("ts", timestamp); put("ms", elapsedMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = AnswerRecord(
            id = o.optString("id", UUID.randomUUID().toString()),
            questionId = o.optString("q"),
            correct = o.optBoolean("ok"),
            userAnswer = o.optString("ans"),
            timestamp = o.optLong("ts", System.currentTimeMillis()),
            elapsedMs = o.optLong("ms", 0)
        )
    }
}

/** Incrementally maintained per-domain-path counters. */
data class DomainStat(
    val path: String,
    val attempts: Int = 0,
    val correct: Int = 0,
    val lastSeen: Long = 0
) {
    val accuracy: Float get() = if (attempts == 0) 0f else correct.toFloat() / attempts
}

/** Rolling LLM-written knowledge-state summary for one top-level domain. */
data class KnowledgeSummary(
    val domain: String,
    val text: String,
    val createdAt: Long,
    val coveredAnswers: Int
)

/** Extract JSON array of strings, tolerating missing arrays. */
internal fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out.add(optString(i))
    return out
}
