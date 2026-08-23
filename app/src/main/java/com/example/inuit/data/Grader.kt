package com.example.inuit.data

import kotlin.math.abs
import kotlin.math.max

/**
 * Deterministic local grading. The correct answer never leaves this class
 * except as a boolean verdict — the UI must only ever see that verdict.
 */
object Grader {

    fun grade(q: Question, raw: String): Boolean = when (q.type) {
        QuestionType.TRUE_FALSE ->
            q.answerBool != null && raw.trim().equals(q.answerBool.toString(), ignoreCase = true)

        QuestionType.MULTIPLE_CHOICE ->
            raw.trim().toIntOrNull() != null && raw.trim().toIntOrNull() == q.answerIndex

        QuestionType.NUMERIC -> {
            val expected = q.answerNumber
            val given = parseNumber(raw)
            expected != null && given != null &&
                abs(given - expected) <= (q.tolerance ?: 0.0) + 1e-9
        }

        QuestionType.FILL_BLANK -> {
            val given = normalize(raw)
            given.isNotEmpty() && q.acceptedAnswers.any { closeEnough(normalize(it), given) }
        }
    }

    /** Forgiving numeric parse: "1,234.5", "3e8", "  -42 ", "1 000 000" all work. */
    fun parseNumber(raw: String): Double? {
        val cleaned = raw.replace(",", "").replace(" ", "")
        val m = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?").find(cleaned) ?: return null
        return m.value.toDoubleOrNull()
    }

    /** Normalization for free-text comparison. */
    fun normalize(s: String): String {
        var t = s.trim().lowercase()
        t = t.replace("’", "'")
        // strip possessives and punctuation
        t = t.replace("'s", "")
        t = t.replace(Regex("[^a-z0-9\\s]"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        // drop leading articles
        val parts = t.split(" ").toMutableList()
        while (parts.size > 1 && parts.first() in setOf("the", "a", "an")) parts.removeAt(0)
        return parts.joinToString(" ")
    }

    /**
     * A miss so far off it suggests the user doesn't know the basic ENTITIES
     * of the domain (e.g. naming a continent when asked for a planet, a city
     * for a country). This is a heuristic pre-filter — the generator LLM sees
     * the raw answer text and makes the final call (near-misses like "Saturn"
     * for "Jupiter" are wrong-but-in-category and only the LLM can tell).
     */
    fun isWildMiss(q: Question, raw: String): Boolean {
        if (grade(q, raw)) return false
        return when (q.type) {
            QuestionType.FILL_BLANK -> {
                val given = normalize(raw)
                if (given.isEmpty()) return false
                q.acceptedAnswers.none { accepted ->
                    val norm = normalize(accepted)
                    closeEnough(norm, given) || sharesSignificantWord(norm, given)
                }
            }
            // Typed something that isn't even a number for a numeric question.
            QuestionType.NUMERIC -> parseNumber(raw) == null
            else -> false
        }
    }

    /** True when both strings share a word of >= 4 letters ("united" in
     *  "United Kingdom" vs "United States") — same-family signal. */
    fun sharesSignificantWord(a: String, b: String): Boolean {
        val wa = a.split(" ").filter { it.length >= 4 }.toSet()
        if (wa.isEmpty()) return false
        return b.split(" ").any { it.length >= 4 && it in wa }
    }

    /** Exact match, or a tiny edit distance for long words (typos). */
    fun closeEnough(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val d = levenshtein(a, b)
        val allowed = when {
            max(a.length, b.length) >= 12 -> 2
            max(a.length, b.length) >= 6 -> 1
            else -> 0
        }
        return d <= allowed
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
