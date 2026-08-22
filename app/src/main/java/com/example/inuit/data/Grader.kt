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
