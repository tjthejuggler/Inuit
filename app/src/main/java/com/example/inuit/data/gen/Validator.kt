package com.example.inuit.data.gen

import com.example.inuit.data.AppSettings
import com.example.inuit.data.Grader
import com.example.inuit.data.Net
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
        markerToQuestion: Map<String, Question>,
        net: Net? = null
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

        val tfBalancer = TfBalancer(existingQuestions)
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
            val built = buildQuestion(o, settings, markerToQuestion, net, tfBalancer)
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
        markerToQuestion: Map<String, Question>,
        net: Net? = null,
        tfBalancer: TfBalancer = TfBalancer(emptyList())
    ): Pair<Question?, String?> {
        val type = QuestionType.from(o.optString("type"))
        val pair = o.optJSONObject("pair")
        var prompt = o.optString("prompt").trim()
        // true_false pairs carry their own prompts (the twins); the top-level
        // "prompt" field is intentionally absent for them.
        if (prompt.length !in 8..320 && !(type == QuestionType.TRUE_FALSE && pair != null))
            return null to "prompt length ${prompt.length} outside 8..320"
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
        // Custom nets: the knowledge map is drawn from "Net > Subtopic" paths,
        // so flat or foreign-rooted tags are normalized (or rejected when no
        // subtopic can be recovered). See NET DOMAIN TAGGING in Prompts.
        val finalDomains: List<String> = if (net != null && !net.isAll) {
            val (normalized, reason) = normalizeNetDomains(cleanDomains, net)
            if (normalized == null) return null to reason
            normalized
        } else cleanDomains

        var answerIndex: Int? = null
        var answerBool: Boolean? = null
        var answerNumber: Double? = null
        var tolerance: Double? = null
        var unit: String? = null
        var accepted: List<String> = emptyList()
        var choices: List<String> = emptyList()

        when (type) {
            QuestionType.TRUE_FALSE -> {
                if (pair != null) {
                    // Twin-pair format (see Prompts rule 4b): the model emits a
                    // TRUE statement and a plausible FALSE twin; the balancer
                    // picks which one becomes the served question, keeping the
                    // long-run true/false answer ratio near 50/50.
                    val trueStmt = pair.optString("true").trim()
                    val falseStmt = pair.optString("false").trim()
                    if (trueStmt.length !in 8..320)
                        return null to "pair true twin length ${trueStmt.length} outside 8..320"
                    if (falseStmt.length !in 8..320)
                        return null to "pair false twin length ${falseStmt.length} outside 8..320"
                    if (Grader.normalize(trueStmt) == Grader.normalize(falseStmt))
                        return null to "pair twins are identical"
                    if (tfBalancer.pick()) {
                        answerBool = true
                        prompt = trueStmt
                    } else {
                        answerBool = false
                        prompt = falseStmt
                    }
                } else {
                    // Legacy format: a single statement with an explicit answer.
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
            domains = finalDomains,
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

    /**
     * Normalizes domain paths for a custom net so the knowledge map can grow
     * territories inside the net:
     *  - "Subtopic" (bare)            → "Net > Subtopic"
     *  - "Net > Subtopic"             → unchanged
     *  - "Broad > Net > Subtopic"     → "Net > Subtopic"  (strips a leading
     *    all-knowledge realm the model prepended)
     *  - "Other > Path"               → "Net > Other > Path"
     *  - "Net" (flat net name only)   → rejected — no subtopic to chart.
     */
    internal fun normalizeNetDomains(domains: List<String>, net: Net): Pair<List<String>?, String?> {
        val netName = net.name.trim()
        val out = LinkedHashSet<String>()
        for (d in domains) {
            val segs = d.split(">").map { it.trim() }.filter { it.isNotEmpty() }
            when {
                segs.size == 1 && segs[0].equals(netName, ignoreCase = true) ->
                    // flat net-name tag: nothing to chart — caller drops the question
                    continue
                segs.size == 1 ->
                    out.add("$netName > ${segs[0]}")
                segs[0].equals(netName, ignoreCase = true) ->
                    out.add(segs.joinToString(" > "))
                segs[1].equals(netName, ignoreCase = true) ->
                    out.add(segs.drop(1).joinToString(" > "))
                else ->
                    out.add((listOf(netName) + segs).joinToString(" > "))
            }
        }
        val valid = out.filter { it.length in 2..120 }
        if (valid.isEmpty()) {
            return null to "domain tag is just the flat net name \"$netName\" — " +
                "every question needs a \"Net > Subtopic\" path for the knowledge map"
        }
        return valid to null
    }

    fun wordSet(s: String): Set<String> =
        Grader.normalize(s).split(" ").filter { it.length > 2 }.toSet()

    /**
     * Decides which twin of a true/false pair becomes the served question.
     *
     * Base rate is a fair coin (50/50 in expectation), biased toward whichever
     * answer is currently under-represented so short-run drift is actively
     * corrected: each excess "true" in the store lowers p(true) by 10pp
     * (capped at 10%..90%). Seeded from the existing question snapshot, so
     * the balance carries across batches and app restarts — the app is thus
     * self-aware of its own true/false ratio without extra persistence.
     */
    class TfBalancer(existing: List<Question>) {
        private var trues = existing.count {
            it.type == QuestionType.TRUE_FALSE && it.answerBool == true
        }
        private var falses = existing.count {
            it.type == QuestionType.TRUE_FALSE && it.answerBool == false
        }

        /** Returns true when the TRUE twin should be served. */
        fun pick(): Boolean {
            val pTrue = (0.5 - (trues - falses) * 0.1).coerceIn(0.1, 0.9)
            val pickTrue = Math.random() < pTrue
            if (pickTrue) trues++ else falses++
            return pickTrue
        }
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return inter / union
    }
}
