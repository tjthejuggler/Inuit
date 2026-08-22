package com.example.inuit.data.gen

import com.example.inuit.data.AppSettings
import com.example.inuit.data.Grader
import com.example.inuit.data.Question
import com.example.inuit.data.QuestionType
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Parses the generator's JSON output and validates every question:
 * structural checks per type, confidence threshold, answer-leak checks and
 * near-duplicate detection against existing questions.
 */
object Validator {

    data class Result(
        val questions: List<Question>,
        val dropped: Int,
        val newFrontiers: List<String>,
        /** Human-readable reasons for every dropped candidate (diagnostics). */
        val dropReasons: List<String> = emptyList()
    )

    fun parseAndValidate(
        raw: String,
        existingQuestions: List<Question>,
        settings: AppSettings,
        markerToQuestion: Map<String, Question>
    ): Result {
        val root = try {
            JSONObject(Prompts.extractJson(raw))
        } catch (e: Exception) {
            return Result(
                emptyList(), 0, emptyList(),
                listOf("model output contained no parseable JSON " +
                    "(length=${raw.length}, head=\"${raw.take(120)}…\")")
            )
        }
        val arr = root.optJSONArray("questions")
            ?: return Result(emptyList(), 0, frontiers(root),
                listOf("JSON had no \"questions\" array (keys=${root.keys().asSequence().take(6).joinToString()})"))

        val seenPrompts = existingQuestions
            .takeLast(1200)
            .map { wordSet(it.prompt) }
        val batchSets = ArrayList<Set<String>>()
        val out = ArrayList<Question>()
        val reasons = ArrayList<String>()
        var dropped = 0

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
                ?: continue.also { reasons.add("#$i: not a JSON object") }
            val built = buildQuestion(o, settings, markerToQuestion)
            val q = built.first
            if (q == null) {
                dropped++
                reasons.add("#$i: ${built.second ?: "invalid"}")
                continue
            }
            // near-duplicate check (Jaccard on word sets)
            val words = wordSet(q.prompt)
            val dup = batchSets.any { jaccard(it, words) > 0.75 } ||
                seenPrompts.any { jaccard(it, words) > 0.75 }
            if (dup) {
                dropped++
                reasons.add("#$i: duplicate/near-duplicate of an existing question")
                continue
            }
            batchSets.add(words)
            out.add(q)
        }
        return Result(out, dropped, frontiers(root), reasons)
    }

    private fun frontiers(root: JSONObject): List<String> =
        root.optJSONArray("new_frontiers")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifBlank { null } }
        } ?: emptyList()

    /** Returns (question, dropReason); exactly one is non-null. */
    private fun buildQuestion(
        o: JSONObject,
        settings: AppSettings,
        markerToQuestion: Map<String, Question>
    ): Pair<Question?, String?> {
        val type = QuestionType.from(o.optString("type"))
        val prompt = o.optString("prompt").trim()
        if (prompt.length !in 8..320) return null to "prompt length ${prompt.length} outside 8..320"
        val difficulty = o.optInt("difficulty", 3).coerceIn(1, 5)
        val confidence = o.optDouble("confidence", 0.0)
        if (confidence < settings.minConfidence)
            return null to "confidence $confidence below threshold ${settings.minConfidence}"

        val domains = o.optJSONArray("domains")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifBlank { null } }
        } ?: emptyList()
        if (domains.isEmpty() || domains.size > 3)
            return null to "domains missing or >3 (${domains.size})"
        val cleanDomains = domains.map { d ->
            d.split(">").joinToString(" > ") { it.trim().replaceFirstChar { c -> c.uppercase() } }
        }.filter { it.length in 2..80 }.distinct()
        if (cleanDomains.isEmpty()) return null to "all domain paths invalid after cleaning"

        var answerIndex: Int? = null
        var answerBool: Boolean? = null
        var answerNumber: Double? = null
        var tolerance: Double? = null
        var unit: String? = null
        var accepted: List<String> = emptyList()
        var choices: List<String> = emptyList()

        when (type) {
            QuestionType.TRUE_FALSE -> {
                val a = o.opt("answer")
                answerBool = when (a) {
                    is Boolean -> a
                    is String -> when (a.trim().lowercase()) {
                        "true", "t", "yes" -> true
                        "false", "f", "no" -> false
                        else -> return null to "true_false answer unrecognised: \"$a\""
                    }
                    else -> return null to "true_false answer wrong type (${a?.javaClass?.simpleName})"
                }
            }

            QuestionType.MULTIPLE_CHOICE -> {
                choices = o.optJSONArray("choices")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifBlank { null } }
                } ?: emptyList()
                if (choices.size !in 2..6) return null to "multiple_choice needs 2..6 choices (got ${choices.size})"
                if (choices.map { Grader.normalize(it) }.distinct().size != choices.size)
                    return null to "multiple_choice has duplicate choices"
                val a = o.opt("answer")
                answerIndex = when (a) {
                    is Int -> a
                    is Number -> a.toInt()
                    is String -> {
                        val s = a.trim()
                        s.toIntOrNull() ?: choices.indexOfFirst { Grader.normalize(it) == Grader.normalize(s) }
                    }
                    else -> -1
                }
                if (answerIndex !in choices.indices)
                    return null to "multiple_choice answer index $answerIndex outside choices"
            }

            QuestionType.NUMERIC -> {
                val a = o.opt("answer")
                answerNumber = when (a) {
                    is Number -> a.toDouble()
                    is String -> Grader.parseNumber(a)
                        ?: return null to "numeric answer unparseable: \"$a\""
                    else -> return null to "numeric answer wrong type (${a?.javaClass?.simpleName})"
                }
                tolerance = if (o.has("tolerance") && !o.isNull("tolerance")) {
                    o.optDouble("tolerance").takeIf { it >= 0 }
                } else null
                if (tolerance == null) {
                    // auto-tolerance for non-integers so grading is fair
                    tolerance = if (answerNumber == Math.floor(answerNumber)) 0.0
                    else maxOf(abs(answerNumber) * 0.02, 0.1)
                }
                unit = o.optString("unit").trim().ifBlank { null }
            }

            QuestionType.FILL_BLANK -> {
                val a = o.optString("answer").trim()
                if (a.isBlank()) return null to "fill_blank answer empty"
                val extras = o.optJSONArray("accepted")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifBlank { null } }
                } ?: emptyList()
                accepted = (listOf(a) + extras).distinctBy { Grader.normalize(it) }
                // leak check: the answer must not appear inside the prompt
                val normPrompt = Grader.normalize(prompt)
                if (accepted.any { normPrompt.contains(Grader.normalize(it)) })
                    return null to "fill_blank leaks its answer into the prompt"
            }
        }

        val hint = o.optString("parent_hint").trim()
        var parentId: String? = null
        var rootId: String? = null
        if (hint.isNotEmpty()) {
            val parent = markerToQuestion[hint]
            if (parent != null) {
                parentId = parent.id
                rootId = parent.rootId ?: parent.id
            }
        }

        val id = java.util.UUID.randomUUID().toString()
        val q = Question(
            id = id,
            type = type,
            prompt = prompt,
            choices = choices,
            answerIndex = answerIndex,
            answerBool = answerBool,
            answerNumber = answerNumber,
            tolerance = tolerance,
            unit = unit,
            acceptedAnswers = accepted,
            domains = cleanDomains,
            difficulty = difficulty,
            parentId = parentId,
            rootId = rootId ?: id,
            confidence = confidence,
            verified = false,
            source = o.optString("source").ifBlank { "core" },
            rationale = o.optString("rationale").trim().ifBlank { null }
        )
        return q to null
    }

    fun wordSet(s: String): Set<String> =
        Grader.normalize(s).split(" ").filter { it.length > 2 }.toSet()

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return inter / union
    }
}
