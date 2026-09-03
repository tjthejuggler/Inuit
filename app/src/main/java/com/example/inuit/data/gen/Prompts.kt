package com.example.inuit.data.gen

import com.example.inuit.data.AppSettings
import com.example.inuit.data.KnowledgeSummary
import com.example.inuit.data.Net
import com.example.inuit.data.SourceMix
import org.json.JSONArray
import org.json.JSONObject

/** All LLM prompt templates for the question engine. */
object Prompts {

    // ── Main generation ──────────────────────────────────────────────────

    /** Intro + (for custom nets) the NET SCOPE block that pins every
     *  question to the user-chosen slice of knowledge. */
    private fun scopeIntro(net: Net?): String {
        val intro = "You are the question engine of Inuit, an app that trains human intuition with short questions"
        if (net == null || net.isAll) {
            return "$intro spanning all human knowledge. You create batches of questions.\n"
        }
        val desc = net.description.ifBlank { net.name }
        return """
$intro. You create batches of questions.

NET SCOPE — the user is training inside a net named "${net.name}", described as: $desc
Every question must fall STRICTLY within this net's scope. Rule 4's breadth requirement applies WITHIN the net: spread across its subtopics, eras, methods, figures and neighboring facets the description allows. Never emit a question outside the net, however tempting.

NET DOMAIN TAGGING — the app draws the user's knowledge map of this net from your domain tags, so they MUST be hierarchical:
- Every domain path MUST start with the net's name and carry at least one meaningful SUBTOPIC level: "${net.name} > Subtopic" (optionally "${net.name} > Subtopic > Facet").
- The subtopic names the specific slice the question lives in (e.g. "${net.name} > History", "${net.name} > Techniques", "${net.name} > Famous Figures", "${net.name} > Equipment", "${net.name} > Records & Numbers") — mirror whatever facets this net actually has.
- A flat path that is just "${net.name}" is INVALID and will be rejected: it tells the map nothing about where within the net the question belongs.
- Spread the batch across MANY DIFFERENT subtopics (aim for at least half as many distinct subtopics as questions); invent new subtopic names rather than reusing one catch-all.
- "new_frontiers" must also be net-scoped two-level paths ("${net.name} > Unexplored Subtopic").
""".trimIndent() + "\n"
    }

    fun systemPrompt(mcpBudget: Int, net: Net? = null): String = """
${scopeIntro(net)}
ABSOLUTE RULES:
1. FACTUAL RIGOR — anti-hallucination is the highest priority. Only emit questions whose correct answer you are CERTAIN of: stable, objectively verifiable facts you would stake your reputation on. If you are not sure, skip that question entirely. Never emit: opinions, contested claims, changing facts ("current champion"), ambiguous or trick phrasing, or anything you might be conflating with a similar fact.
2. NEVER REVEAL — the app never shows answers or explanations to the user. Do not write prompts that contain their own answer. Do not add hints, explanations or elaborations to prompts. The "rationale" field is internal audit only.
3. SOCRATIC DECOMPOSITION — for each UNKNOWN TARGET [U#] create 1-3 SIMPLER questions that verify a stepping-stone fact needed to eventually DERIVE the target answer. Rules for sub-questions:
   - Each must be independently verifiable and near-certain.
   - Never re-ask the target question itself, and never give away its answer.
   - Ladder from easier to closer: start from a baseline fact most people know, step closer to the target across successive batches (you will see which sub-questions were answered).
   - Vary the angle across batches (magnitude, comparison, ordering, unit, definition, cause, timeline).
   - Prefer multiple_choice and true_false formats for sub-questions — recognition scaffolds recall; save numeric/fill_blank for the target itself.
   - Set "parent_hint" to the [U#] marker of the target you are decomposing.
4. VARIETY — mix all four types (true_false, multiple_choice, numeric, fill_blank). Mix difficulties 1-5 (1 = most people know; 5 = expert). Include famous trivia AND delightfully obscure but rock-solid facts (statistics, magnitudes, records, etymology). At least half the batch must open subdomains never present in the context — breadth of the knowledge space matters more than depth.
4b. TRUE_FALSE PAIRS — every true_false question MUST be emitted as a PAIR of twin statements about the same fact: "pair": {"true": "...", "false": "..."}. The false twin must be the SAME sentence with exactly one detail changed (a name, date, number, place or relationship) so that it is definitely, unambiguously FALSE yet equally plausible to someone who doesn't know the fact — never ambiguous, never trivially absurd. Do NOT emit an "answer" field for true_false; omit "prompt" (the app randomly picks one twin to show, which keeps the user's true/false answers at a perfect 50/50 split). Both twins must independently satisfy Rule 1's factual rigor.
5. DOMAIN TAGS — tag every question with 1-3 hierarchical paths using " > " separators (e.g. "Science > Physics > Optics"). Reuse existing paths from the context when they fit; create deeper/more specific paths when the question is narrower. Top level must be one of the broad realms seen in context or an equally broad new one. Inside a custom net, paths instead start with the net's name plus a subtopic — follow the NET DOMAIN TAGGING block above exactly.
6. WEB TOOLS — you may call the provided web search / web reader tools AT MOST $mcpBudget TIMES TOTAL for this whole batch, and only to (a) ground obscure statistics you are not already certain of, or (b) double-check a borderline fact. Never for common knowledge. If the budget is 0 or exhausted, generate only from certain knowledge.
7. CONFIDENCE — report honest calibrated certainty per question (0-1). Questions below the threshold are discarded; do not inflate.
8. OFF-CATEGORY ANSWERS → NOVICE SCAFFOLDING — recent answers may show the user's actual wrong answer. Classify every wrong free-text answer: a NEAR-MISS (wrong but the right kind of entity, e.g. "Saturn" for the largest planet) just needs the normal ladder; an OFF-CATEGORY answer (not even the right kind of entity, e.g. a continent named as a planet, a city as a country, a person as a chemical element) means the user does not know the BASIC ENTITIES of that domain. For any such domain: rebuild from recognition — over the next batches ask difficulty 1-2 MULTIPLE_CHOICE / TRUE_FALSE questions that introduce and name the domain's basic entities (members, categories, famous examples) BEFORE any recall (fill_blank/numeric) question returns to it. Ladder back up gradually as those are answered.
9. ADAPTIVE CHALLENGE — aim every batch at the BOUNDARY of the user's knowledge, Socratic style: questions they must stretch for but can ultimately reach. Treat recent performance as a difficulty dial: wherever the user answers consistently correctly (especially at difficulty 3+), the next questions there MUST climb — more specialized facets, finer distinctions, +1 or +2 difficulty — until misses appear; wherever they miss, step back down one rung. Never park at comfortable questions the user has clearly mastered, and never bury a struggling user in expert trivia. Early easy questions are calibration, not a destination: an experienced user must feel challenged by every batch.

OUTPUT — reply with a single JSON object, no markdown fences, no commentary:
{
  "questions": [
    {
      "type": "true_false" | "multiple_choice" | "numeric" | "fill_blank",
      "prompt": "question text, self-contained, <= 280 chars",   // NOT for true_false (use "pair")
      "pair": {"true": "...", "false": "..."}, // true_false only: twin statements, see rule 4b
      "choices": ["...","...","..."],        // multiple_choice only: 4 options, plausible, no 'all of the above'
      "answer": <integer index | number | string>,   // multiple_choice / numeric / fill_blank only
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

    fun userRequest(
        ctx: ContextBuilder.Context,
        batchSize: Int,
        net: Net? = null,
        accents: NetAccents? = null
    ): String {
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

        if (ctx.rejectedLines.isNotEmpty()) {
            sb.append("\n== REJECTED QUESTIONS (the user explicitly SKIPPED these — read them as a pattern) ==\n")
            ctx.rejectedLines.forEach { sb.append("- ").append(it).append('\n') }
            sb.append("→ do NOT produce questions resembling these. Infer what KIND of question, topic treatment, ")
                .append("style or flavor the user dislikes — avoid that whole category, not merely the literal facts.\n")
        }

        ctx.rejectionNotes?.let {
            sb.append("\n== REJECTION LESSONS (distilled rules from previously rejected questions — binding guidance) ==\n")
            sb.append(it.trim()).append('\n')
        }

        if (ctx.noviceDomains.isNotEmpty()) {
            sb.append("\n== NOVICE DOMAINS (very low accuracy and/or off-category answers — the user may not know the basic entities) ==\n")
            ctx.noviceDomains.forEach { sb.append("- ").append(it)
                .append(" → scaffold: difficulty 1-2 recognition questions (multiple_choice / true_false) naming the basic entities first\n") }
        }

        sb.append("\n== DOMAIN PROFICIENCY ==\n")
        if (ctx.domainDigest.isEmpty()) sb.append("(no stats yet)\n")
        else ctx.domainDigest.forEach { sb.append(it).append('\n') }

        if (ctx.challengeDomains.isNotEmpty()) {
            sb.append("\n== CHALLENGE ESCALATION (consistently correct — climb here next) ==\n")
            ctx.challengeDomains.forEach { sb.append("- ").append(it).append('\n') }
            sb.append("→ mastered at the current level: the next batch in these areas must be HARDER ")
                .append("(more specialized facets, finer distinctions, +1..2 difficulty) until misses appear.\n")
        }

        if (ctx.distantFrontiers.isNotEmpty()) {
            sb.append("\n== DISTANT FRONTIERS (maximally unlike anything recent — novelty pressure; obscure fields welcome) ==\n")
            ctx.distantFrontiers.forEach { sb.append("- ").append(it).append('\n') }
        }

        if (ctx.revisitFrontiers.isNotEmpty()) {
            sb.append("\n== REVISIT (older threads worth circling back to from a new angle) ==\n")
            ctx.revisitFrontiers.forEach { sb.append("- ").append(it).append('\n') }
        }

        // Per-source question targets from the net's configured mix. An
        // accent only gets a target when its data is actually available
        // this batch; unavailable accents' share quietly folds back into core.
        val mix = net?.mix()
        fun target(key: String): Int {
            val w = mix?.get(key) ?: 0
            if (w <= 0) return 0
            return Math.round(batchSize * w / 100.0).toInt().coerceAtLeast(1)
        }
        val locTarget = if (accents?.locationLine != null) target(SourceMix.LOCATION) else 0
        val dateTarget = if (accents?.dateLines?.isNotEmpty() == true) target(SourceMix.DATE) else 0
        val crossTarget = if (accents?.crossNetLines?.isNotEmpty() == true) target(SourceMix.CROSS_NET) else 0
        val tailTarget = if (accents?.tailTextLines?.isNotEmpty() == true) target(SourceMix.TAIL_TEXT) else 0
        // Custom sources are always "available" — their guidance is static.
        val customTargets = net?.customSources
            ?.map { it to target(SourceMix.customKey(it.id)) }
            ?.filter { it.second > 0 && it.first.label.isNotBlank() }
            ?: emptyList()
        val accentTotal = locTarget + dateTarget + crossTarget + tailTarget + customTargets.sumOf { it.second }
        if (accentTotal > 0) {
            // Null accents can still reach here via custom sources alone.
            val a = accents ?: NetAccents()
            sb.append("\n== QUESTION SOURCE MIX (the user's configured distribution for this net) ==\n")
            sb.append("Of the $batchSize questions, aim for approximately: ")
            val aims = ArrayList<String>(4)
            if (locTarget > 0) aims.add("$locTarget tied to the LOCATION below")
            if (dateTarget > 0) aims.add("$dateTarget tied to today's DATE")
            if (crossTarget > 0) aims.add("$crossTarget anchored in the OTHER NETS below")
            if (tailTarget > 0) aims.add("$tailTarget inspired by the LIFE-LOG below")
            customTargets.forEach { (src, t) -> aims.add("$t from ${src.label}") }
            sb.append(aims.joinToString(", ")).append("; the remaining ").append(batchSize - accentTotal)
                .append(" are core questions driven by the rest of this context. ")
                .append("Treat the counts as targets, not straitjackets: if a source genuinely ")
                .append("cannot supply an honest, certain question that fits this net's scope, ")
                .append("drop it rather than force it.\n")
            if (locTarget > 0) {
                sb.append("- LOCATION (${locTarget} question(s)): ${a.locationLine} — questions tied to that region ")
                    .append("(its history, geography, science, notable people/events) that still fit this net's scope.\n")
            }
            if (dateTarget > 0) {
                sb.append("- DATE (${dateTarget} question(s)): ").append(a.dateLines.joinToString(" · ")).append('\n')
                sb.append("  → questions whose answers are anchored to today's date, this date in history, or one of those past years.\n")
            }
            if (crossTarget > 0) {
                sb.append("- OTHER NETS (${crossTarget} question(s); the user also trains in these):\n")
                a.crossNetLines.forEach { sb.append(it).append('\n') }
                sb.append("  → anchor questions in something they know/missed there — but each question itself must stay STRICTLY inside this net's scope (a bridge, not a departure).\n")
            }
            if (tailTarget > 0) {
                sb.append("- LIFE-LOG (${tailTarget} question(s); the user's own recent notes from their habit tracker):\n")
                a.tailTextLines.forEach { sb.append("  ").append(it).append('\n') }
                sb.append("  → these are personal seeds, NOT quiz material: questions may draw ")
                .append("light inspiration from them (a topic, entity or theme a note mentions), ")
                .append("and must still fit this net's scope with a verifiable answer. ")
                .append("Never quote the notes back and never ask about the user personally.\n")
            }
            for ((src, t) in customTargets) {
                sb.append("- ${src.label} (${t} question(s); the user's own custom direction for this net):\n")
                sb.append("  → ").append(src.guidance.replace('\n', ' ').trim()).append('\n')
                sb.append("  → every such question must still satisfy Rule 1's factual rigor and fit this net's scope.\n")
            }
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
        if (accentTotal > 0) {
            sb.append("Aim for the QUESTION SOURCE MIX targets ($accentTotal accent question(s) total). ")
        }
        if (ctx.noviceDomains.isNotEmpty()) {
            sb.append("For every NOVICE DOMAIN (and any domain where a recent wrong answer was off-category), ")
                sb.append("include at least 2 easy recognition questions (difficulty 1-2, multiple_choice or true_false) ")
                .append("that introduce that domain's basic entities. ")
        }
        val escalationRef = if (ctx.challengeDomains.isNotEmpty()) " (see CHALLENGE ESCALATION)" else ""
        sb.append("Calibrate difficulty to the BOUNDARY of the user's knowledge: in consistently-correct ")
            .append("areas$escalationRef the next questions must be harder — more specialized, ")
            .append("finer distinctions, higher difficulty; in missed areas step back down. Challenging but ")
            .append("never impossible: the user should have to work for most answers yet still be able to reach them.")
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

    // ── Rejection lessons (distilled from the skipped-question pile) ──────

    /**
     * Re-distills the rejection notes: the LLM sees its own PREVIOUS notes
     * (if any) plus the full current pile of rejected questions, and must
     * derive broad, generalizable rules about what NOT to generate.
     */
    fun rejectionNotesPrompt(previousNotes: String?, rejectedLines: List<String>): String = buildString {
        append("""
You maintain the "rejection lessons" for Inuit, a question-generation app. The user sometimes skips questions they do not want; below is the current pile of REJECTED questions (oldest → newest). Your job: distill concise, GENERALIZABLE rules about what KINDS of questions this user dislikes — style, subject treatment, difficulty flavor, format annoyances, topic fatigue — so the generator avoids producing similar ones. Infer patterns; do not merely restate the literal topics.

Rules:
- Max 120 words. Plain prose or short dash bullets. No fluff, no preamble.
- Your reply REPLACES the previous lessons entirely: carry forward whatever still holds, revise or drop what the newest pile members contradict.
- Concrete enough to act on ("avoid X-style questions because …"), broad enough to cover future cases.

Reply with a single JSON object, no markdown fences: {"notes": "..."}

""".trimIndent())
        if (previousNotes.isNullOrBlank()) append("PREVIOUS LESSONS: (none — this is the first distillation)\n\n")
        else append("PREVIOUS LESSONS:\n").append(previousNotes.trim()).append("\n\n")
        append("REJECTED QUESTIONS (oldest → newest):\n")
        rejectedLines.forEach { append("- ").append(it).append('\n') }
    }

    /** Parses the notes reply; empty string when unusable. */
    fun parseRejectionNotes(json: String): String = try {
        JSONObject(extractJson(json)).optString("notes").trim().take(1200)
    } catch (e: Exception) {
        ""
    }

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

    // ── Bulk web harvest (stockpile mode) ────────────────────────────────

    fun harvestSystemPrompt(toolBudget: Int, net: Net? = null): String {
        val scope = if (net == null || net.isAll) "" else
            "\nNET SCOPE — the stockpile belongs to the \"${net.name}\" net: ${net.description.ifBlank { net.name }}. " +
                "Only harvest trivia that falls STRICTLY within this scope; general-knowledge lists outside it are useless here. " +
                "Rule 6's variety applies across the net's subtopics. " +
                "Rule 3's domain paths must be net-scoped and hierarchical — \"${net.name} > Subtopic\" (e.g. \"${net.name} > History\", \"${net.name} > Techniques\"); " +
                "a flat \"${net.name}\" tag is INVALID. Spread the harvest across the net's different subtopics.\n"
        return """
You are the stockpile harvester of Inuit, a trivia app. Your job is VOLUME: use the provided web search / web reader tools (AT MOST $toolBudget CALLS TOTAL) to find large, reputable trivia question lists online, then convert what you actually found into Inuit's question format. These questions are NOT personalized — they are for a stockpile, so no user context is given.$scope

RULES:
1. SOURCE-GROUNDED — only emit questions whose answer is clearly stated in the content you fetched. Do NOT invent questions from thin air in this mode; if a fetched page is thin, search again within budget. Skip anything ambiguous, opinion-based, time-sensitive ("current champion"), or contested.
2. VOLUME — aim for the requested count. Prefer big lists (100+ questions pages) and convert as many usable items as possible.
3. TAGGING — tag every question with 1-3 hierarchical domain paths ("Science > Physics > Optics"). Set difficulty by GENERAL knowledge standards (1 = most people know, 5 = expert), not for any particular user.
4. FORMAT — prefer multiple_choice (generate 4 plausible wrong options yourself; no 'all of the above'); also use true_false and fill_blank where they fit naturally. Self-contained prompts <= 280 chars. Every true_false item MUST be a PAIR of twins per the OUTPUT spec: "pair": {"true": "...", "false": "..."} — the same statement with exactly one detail changed so the false twin is definitely yet non-obviously false; no "prompt"/"answer" fields for true_false (the app picks a twin at random for a perfect 50/50 answer split).
5. CONFIDENCE — 0.9-1.0 only when the fetched source clearly states the answer; otherwise skip the question.
6. VARIETY — spread across many domains; avoid making more than ~4 questions about the same single subject.

OUTPUT — reply with a single JSON object, no markdown fences, no commentary:
{
  "questions": [
    {
     "type": "true_false" | "multiple_choice" | "numeric" | "fill_blank",
     "prompt": "question text, self-contained, <= 280 chars",   // NOT for true_false (use "pair")
     "pair": {"true": "...", "false": "..."}, // true_false only: twin statements
     "choices": ["...","...","..."],
     "answer": <integer index | number | string>,
      "tolerance": 0.5,
      "unit": "km/h",
      "accepted": ["alt answer"],
      "domains": ["A > B", "A > B > C"],
      "difficulty": 1-5,
      "confidence": 0.0-1.0,
      "rationale": "one-line internal justification"
    }
  ],
  "new_frontiers": ["Realm > Subrealm"]
}
""".trim()
    }

    fun harvestUserPrompt(target: Int, themeHint: String, queued: Int): String = """
== TASK ==
The app's question stockpile is low ($queued queued). Search the web for large trivia question lists — e.g. "100 trivia questions and answers", "pub quiz questions list", "general knowledge quiz with answers"$themeHint — then READ one or two of the most promising pages and convert up to $target usable questions into the JSON format.

Prioritize breadth and volume. Every question must be answerable from what you actually read.
""".trim()

    // ── Podcast episode recommendation ────────────────────────────────────

    fun podcastPrompt(
        weakLines: List<String>,
        wrongLines: List<String>,
        summaryLines: List<String>,
        avoidLines: List<String>,
        toolsAvailable: Boolean = false,
        net: Net? = null
    ): String = buildString {
        append("""
You pick podcast episodes for Inuit, an intuition-training app. Below is what the user knows least. Recommend ONE specific, REAL podcast EPISODE that would best teach one of those weak areas.

RULES:
1. REAL ABOVE ALL — only pick an episode you are CERTAIN exists: a famous evergreen episode of a well-known show (e.g. In Our Time, Hardcore History, This American Life, Radiolab, 99% Invisible, The Rest Is History, Ologies, Stuff You Should Know). NEVER invent an episode title. When unsure of the exact title, pick a different episode you are sure of.
2. TARGET THE GAP — the episode's subject must overlap the user's weakest area (or a topic they recently missed). Depth on their single biggest gap beats breadth.
3. ACCESSIBLE — prefer episodes that assume no prior knowledge of the subject.
4. search_query — "show name + episode title", optimized for searching inside a podcast app.
5. url — REQUIRED in practice: the app opens the episode FROM this link, so a missing link degrades the experience. Provide the episode's stable public page — the Apple Podcasts episode page (https://podcasts.apple.com/<locale>/podcast/<show-slug>/id<show-id>?i=<episode-id>) or the show's official episode page. IF WEB TOOLS ARE PROVIDED, use them to find/verify the exact page before answering. Never invent or guess a URL — set url to null only as a last resort.
6. VARIETY, NOT BANS — your guiding principle is always the RIGHT episode for the user's gap. Given comparable candidates, prefer a show NOT listed under ALREADY SUGGESTED and roam widely across shows, hosts, formats and tones — but a clearly better-fitting episode from a recent show still beats a weaker pick from a new one.

Reply with a single JSON object, no markdown fences, no commentary:
{"show": "...", "title": "...", "reason": "one sentence tying the episode to the user's weak area", "search_query": "...", "url": "..." or null}
""".trimIndent())
        if (toolsAvailable) {
            append(
                "\nWEB TOOLS: you may call the provided web search / web reader tools " +
                    "(at most a couple of calls) to find the episode's Apple Podcasts page or " +
                    "official episode URL, and to sanity-check that the episode exists. " +
                    "Prefer grounded URLs over memory.\n"
            )
        }
        if (net != null && !net.isAll) {
            append("\n== NET SCOPE ==\n")
            append("The user trains inside the \"${net.name}\" net: ${net.description.ifBlank { net.name }}. ")
            append("Pick episodes strictly within this scope.\n")
        }
        append("\n\n== WEAKEST AREAS (domain — correct/attempts) ==\n")
        if (weakLines.isEmpty()) append("(no data yet — pick a famous, broadly enlightening episode)\n")
        else weakLines.forEach { append("- ").append(it).append('\n') }
        if (wrongLines.isNotEmpty()) {
            append("\n== RECENTLY MISSED QUESTIONS ==\n")
            wrongLines.forEach { append("- ").append(it).append('\n') }
        }
        if (summaryLines.isNotEmpty()) {
            append("\n== KNOWLEDGE SUMMARIES ==\n")
            summaryLines.forEach { append("- ").append(it).append('\n') }
        }
        if (avoidLines.isNotEmpty()) {
            append("\n== ALREADY SUGGESTED (prefer different shows) ==\n")
            avoidLines.forEach { append("- ").append(it).append('\n') }
        }
    }

    /** Corrective second pass when the first reply lacked a usable URL. */
    fun podcastUrlRetryPrompt(show: String, title: String): String = """
You previously picked the podcast episode "$title" from "$show" for the Inuit app, but the reply lacked a usable episode URL. The app opens the episode FROM that link, so it matters.

Reply again with the SAME JSON object format: {"show": "...", "title": "...", "reason": "...", "search_query": "...", "url": "..."} — this time with the episode's real, stable public page: the Apple Podcasts episode page (https://podcasts.apple.com/<locale>/podcast/<show-slug>/id<show-id>?i=<episode-id>) or the show's official episode page. Verify the format character by character; never fabricate a URL. If you genuinely cannot recall one, return "url": null.
""".trim()

    /** Only absolute http(s) URLs with a real host are usable links. */
    private val HTTP_URL = Regex("^https?://[^\\s/?#]+\\.[^\\s/?#]+", RegexOption.IGNORE_CASE)

    fun parsePodcastRec(json: String): com.example.inuit.data.PodcastRec? {
        return try {
            val o = JSONObject(extractJson(json))
            val show = o.optString("show").trim()
            val title = o.optString("title").trim()
            if (show.isEmpty() || title.isEmpty()) null
            else com.example.inuit.data.PodcastRec(
                show = show,
                title = title,
                reason = o.optString("reason").trim().ifEmpty { "Targets one of your weakest areas." },
                searchQuery = o.optString("search_query").trim().ifEmpty { "$show $title" },
                url = o.optString("url").trim().takeIf { it.isNotBlank() && HTTP_URL.containsMatchIn(it) }
            )
        } catch (e: Exception) {
            null
        }
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
