package com.example.inuit.data.gen

import com.example.inuit.data.AppSettings
import com.example.inuit.data.KnowledgeSummary
import org.json.JSONArray
import org.json.JSONObject

/** All LLM prompt templates for the question engine. */
object Prompts {

    // ── Main generation ──────────────────────────────────────────────────

    fun systemPrompt(mcpBudget: Int): String = """
You are the question engine of Inuit, an app that trains human intuition with short questions spanning all human knowledge. You create batches of questions.

ABSOLUTE RULES:
1. FACTUAL RIGOR — anti-hallucination is the highest priority. Only emit questions whose correct answer you are CERTAIN of: stable, objectively verifiable facts you would stake your reputation on. If you are not sure, skip that question entirely. Never emit: opinions, contested claims, changing facts ("current champion"), ambiguous or trick phrasing, or anything you might be conflating with a similar fact.
2. NEVER REVEAL — the app never shows answers or explanations to the user. Do not write prompts that contain their own answer. Do not add hints, explanations or elaborations to prompts. The "rationale" field is internal audit only.
3. SOCRATIC DECOMPOSITION — for each UNKNOWN TARGET [U#] create 1-3 SIMPLER questions that verify a stepping-stone fact needed to eventually DERIVE the target answer. Rules for sub-questions:
   - Each must be independently verifiable and near-certain.
   - Never re-ask the target question itself, and never give away its answer.
   - Ladder from easier to closer: start from a baseline fact most people know, step closer to the target across successive batches (you will see which sub-questions were answered).
   - Vary the angle across batches (magnitude, comparison, ordering, unit, definition, cause, timeline).
   - Set "parent_hint" to the [U#] marker of the target you are decomposing.
4. VARIETY — mix all four types (true_false, multiple_choice, numeric, fill_blank). Mix difficulties 1-5 (1 = most people know; 5 = expert). Include famous trivia AND delightfully obscure but rock-solid facts (statistics, magnitudes, records, etymology). At least half the batch must open subdomains never present in the context — breadth of the knowledge space matters more than depth.
5. DOMAIN TAGS — tag every question with 1-3 hierarchical paths using " > " separators (e.g. "Science > Physics > Optics"). Reuse existing paths from the context when they fit; create deeper/more specific paths when the question is narrower. Top level must be one of the broad realms seen in context or an equally broad new one.
6. WEB TOOLS — you may call the provided web search / web reader tools AT MOST $mcpBudget TIMES TOTAL for this whole batch, and only to (a) ground obscure statistics you are not already certain of, or (b) double-check a borderline fact. Never for common knowledge. If the budget is 0 or exhausted, generate only from certain knowledge.
7. CONFIDENCE — report honest calibrated certainty per question (0-1). Questions below the threshold are discarded; do not inflate.

OUTPUT — reply with a single JSON object, no markdown fences, no commentary:
{
  "questions": [
    {
      "type": "true_false" | "multiple_choice" | "numeric" | "fill_blank",
      "prompt": "question text, self-contained, <= 280 chars",
      "choices": ["...","...","..."],        // multiple_choice only: 4 options, plausible, no 'all of the above'
      "answer": <boolean | integer index | number | string>,   // per type
      "tolerance": 0.5,                       // numeric only: absolute tolerance (required for non-integers)
      "unit": "km/h",                         // numeric only, optional hint shown to the user
      "accepted": ["alt answer"],             // fill_blank only: extra accepted spellings/variants
      "domains": ["A > B", "A > B > C"],
      "difficulty": 1-5,
      "confidence": 0.0-1.0,
      "parent_hint": "U2",                    // only when decomposing that unknown target
      "rationale": "one-line internal justification of the answer"
    }
  ],
  "new_frontiers": ["Realm > Subrealm", "..."]  // up to 5 fresh areas to explore in future batches
}
""".trim()

    fun userRequest(ctx: ContextBuilder.Context, batchSize: Int): String {
        val sb = StringBuilder()
        sb.append("== USER STATE ==\n")
        sb.append(ctx.totalsLine).append('\n')

        if (ctx.summaries.isNotEmpty()) {
            sb.append("\n== KNOWLEDGE SUMMARIES (condensed history; oldest information) ==\n")
            for (s in ctx.summaries) {
                sb.append("### ").append(s.domain).append('\n')
                    .append(s.text.trim()).append('\n')
            }
        }

        sb.append("\n== RECENT ANSWERS (oldest → newest; ✓ = knew it, ✗ = did not) ==\n")
        if (ctx.recentLines.isEmpty()) sb.append("(no answers yet — this is a brand-new user)\n")
        else ctx.recentLines.forEach { sb.append(it).append('\n') }

        sb.append("\n== UNKNOWN TARGETS (the user did NOT know these; decompose them) ==\n")
        if (ctx.unknownGroups.isEmpty()) sb.append("(none — focus on exploration)\n")
        for (g in ctx.unknownGroups) {
            sb.append('[').append(g.marker).append("] (d").append(g.root.difficulty).append(") [")
                .append(g.root.domains.firstOrNull() ?: "untagged").append("] ")
                .append(g.root.prompt).append('\n')
            sb.append("    internal-answer (for your consistency only, NEVER output it): ")
                .append(internalAnswer(g.root)).append('\n')
            if (g.lineage.isNotEmpty()) {
                sb.append("    existing sub-question ladder:\n")
                for (item in g.lineage) {
                    sb.append("      - [").append(item.status).append("] (d").append(item.question.difficulty)
                        .append(") ").append(item.question.prompt).append('\n')
                }
            }
        }

        if (ctx.knownLines.isNotEmpty()) {
            sb.append("\n== KNOWN SAMPLE (calibration — do not re-ask these) ==\n")
            ctx.knownLines.forEach { sb.append(it).append('\n') }
        }

        sb.append("\n== DOMAIN PROFICIENCY ==\n")
        if (ctx.domainDigest.isEmpty()) sb.append("(no stats yet)\n")
        else ctx.domainDigest.forEach { sb.append(it).append('\n') }

        sb.append("\n== DISTANT FRONTIERS (maximally unlike anything recent — novelty pressure; obscure fields welcome) ==\n")
        ctx.distantFrontiers.forEach { sb.append("- ").append(it).append('\n') }

        if (ctx.revisitFrontiers.isNotEmpty()) {
            sb.append("\n== REVISIT (older threads worth circling back to from a new angle) ==\n")
            ctx.revisitFrontiers.forEach { sb.append("- ").append(it).append('\n') }
        }

        val subCount = if (ctx.unknownGroups.isEmpty()) 0 else (batchSize * 0.4).toInt().coerceAtLeast(3)
        sb.append(
            "\n== TASK ==\nGenerate ").append(batchSize).append(" questions: ")
        if (subCount > 0) {
            sb.append("about ").append(subCount)
                .append(" should be Socratic decompositions of the unknown targets above (spread across targets, ")
                .append("respecting what their ladders already covered); the rest should be fresh exploration — ")
        } else {
            sb.append("all fresh exploration — ")
        }
        sb.append("MOST fresh-exploration questions must draw from the DISTANT FRONTIERS: fields far from anything the user has recently seen, ")
            .append("including delightfully obscure ones. ")
        if (ctx.revisitFrontiers.isNotEmpty()) {
            sb.append("One or two may REVISIT an older thread from a new angle (different question type or facet, never a re-ask). ")
        }
        sb.append("Use all four types (at least 2 questions per type). ")
        sb.append("Include at least 2 obscure-but-certain questions (statistics, magnitudes, records). ")
        sb.append("Prefer difficulty near the user's level: slightly above their comfort zone in weak areas, higher in strong areas.")
        return sb.toString()
    }

    /** Internal answer rendering for the generator context (never shown to the user). */
    private fun internalAnswer(q: com.example.inuit.data.Question): String = when (q.type) {
        com.example.inuit.data.QuestionType.TRUE_FALSE -> q.answerBool?.toString() ?: "?"
        com.example.inuit.data.QuestionType.MULTIPLE_CHOICE ->
            q.answerIndex?.let { q.choices.getOrNull(it) } ?: "?"
        com.example.inuit.data.QuestionType.NUMERIC ->
            q.answerNumber?.let { numToString(it) } ?: "?"
        com.example.inuit.data.QuestionType.FILL_BLANK -> q.acceptedAnswers.firstOrNull() ?: "?"
    }

    fun numToString(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    // ── Verifier (second anti-hallucination pass) ────────────────────────

    fun verifierPrompt(itemsJson: JSONArray): String = """
You are a strict fact-checker. Below is a JSON array of draft questions with their intended answers. Flag EVERY item that is: factually wrong or probably wrong, ambiguous, opinion-based, unverifiable, or whose prompt accidentally reveals its answer.

Reply with a single JSON object, no fences: {"flags": [0-based indices of bad items]}
When genuinely uncertain about an item, flag it — false positives are cheap, hallucinated questions are fatal.
Do not flag items merely for being easy or obscure.

DRAFTS:
$itemsJson
""".trim()

    // ── Rolling knowledge summaries ──────────────────────────────────────

    fun summaryPrompt(inputs: List<SummaryInput>): String {
        val sb = StringBuilder()
        sb.append("""
You maintain rolling "knowledge state" summaries for Inuit, an intuition-training app. For each domain below, condense the user's answer history into a compact state description (max 110 words): what they reliably know, what they demonstrably don't (list concrete gaps), their approximate level, and which directions worked. Be specific and factual; no fluff. These summaries replace raw history in future question-generation contexts.

Reply with a single JSON object, no fences: {"summaries": {"<domain>": "<summary text>"}}

""".trimIndent())
        for (input in inputs) {
            sb.append("### DOMAIN: ").append(input.domain).append('\n')
            if (input.previous != null) sb.append("PREVIOUS SUMMARY:\n").append(input.previous).append('\n')
            sb.append("RECENT ANSWERS (✓ knew, ✗ didn't):\n")
            for (line in input.lines) sb.append(line).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }

    data class SummaryInput(val domain: String, val previous: String?, val lines: List<String>)

    fun parseSummaries(json: String): Map<String, String> {
        val obj = JSONObject(extractJson(json))
        val out = HashMap<String, String>()
        val sm = obj.optJSONObject("summaries") ?: obj
        for (k in sm.keys()) {
            val v = sm.optString(k).trim()
            if (v.isNotBlank()) out[k] = v
        }
        return out
    }

    // ── Robust JSON extraction ───────────────────────────────────────────

    /** Strips markdown fences and finds the first balanced JSON object in the text. */
    fun extractJson(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end)
            t = t.trim()
        }
        val start = t.indexOf('{')
        if (start < 0) return t
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until t.length) {
            val c = t[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return t.substring(start, i + 1)
                }
            }
        }
        return t.substring(start)
    }
}
