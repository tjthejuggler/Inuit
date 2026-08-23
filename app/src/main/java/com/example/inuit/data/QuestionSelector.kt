package com.example.inuit.data

import kotlin.random.Random

/**
 * Pure question-selection strategy (no Android dependencies — unit-testable).
 *
 * Priority order per pick:
 *  1. SOCRATIC THREAD — with [THREAD_PERCENT] chance, surface an unseen
 *     sub-question of a lineage the user missed BEFORE the current session
 *     started. The session-boundary gate is the blind-training invariant: a
 *     follow-up must never betray how the question just answered went.
 *     Choice-format sub-questions (multiple choice / true-false) are preferred
 *     — recognition scaffolds recall on the path to the harder parent.
 *  2. SPACED REVISIT — with [REVISIT_PERCENT] chance, re-serve a previously
 *     answered question. Wrong answers come back far more often than correct
 *     ones, but correct ones DO come back — the mix is what keeps a re-ask
 *     from revealing correctness. A question's latest answer must be at
 *     least [REVISIT_GAP] answers in the past (spacing), and the question on
 *     screen is never eligible.
 *  3. FRESH — an unserved question chosen for maximal realm distance from
 *     the last few answers, with a skip penalty.
 *
 * When the queue is empty, a revisit candidate is returned as a fallback so
 * the user is never idle while a batch generates (null only when there is
 * truly nothing to show).
 */
object QuestionSelector {

    /** Chance (percent) of surfacing a thread sub-question when one exists. */
    const val THREAD_PERCENT = 50

    /** Chance (percent) of a spaced revisit when a candidate exists. */
    const val REVISIT_PERCENT = 20

    /** A revisit's latest answer must be at least this many answers back. */
    const val REVISIT_GAP = 6

    /** Revisit weights: wrong answers return far more often than correct ones. */
    const val WRONG_WEIGHT = 4
    const val CORRECT_WEIGHT = 1

    /** How many recent answers define the "recent realms" to leap away from. */
    const val REALM_MEMORY = 6

    private const val PRIOR_WRONG_SCAN = 40

    fun select(
        questions: List<Question>,
        answers: List<AnswerRecord>,
        currentId: String?,
        sessionBoundaryMs: Long,
        rng: Random
    ): Question? {
        val byId = questions.associateBy { it.id }
        val queue = questions.filter { it.servedCount == 0 }
        val candidates = queue.filter { it.id != currentId }.ifEmpty { queue }
        val revisits = revisitCandidates(questions, answers, currentId, byId)

        if (candidates.isEmpty()) {
            // Queue exhausted: fall back to a spaced revisit while generating.
            return revisits?.let { weightedPick(it, rng) }
        }

        // ── 1. Socratic thread (prior-session misses only) ────────────────
        val priorWrongRoots = answers
            .filter { it.timestamp < sessionBoundaryMs }
            .takeLast(PRIOR_WRONG_SCAN)
            .filter { !it.correct }
            .mapNotNull { a -> byId[a.questionId]?.let { q -> q.rootId ?: q.id } }
            .toSet()
        val subQuestions = candidates.filter {
            it.rootId != null && it.id != it.rootId && it.rootId in priorWrongRoots
        }
        val choiceSubs = subQuestions.filter {
            it.type == QuestionType.MULTIPLE_CHOICE || it.type == QuestionType.TRUE_FALSE
        }.ifEmpty { subQuestions }
        if (choiceSubs.isNotEmpty() && rng.nextInt(100) < THREAD_PERCENT) {
            return choiceSubs.random(rng)
        }

        // ── 2. Spaced revisit (wrong-weighted, correctness-blind mix) ─────
        if (revisits != null && rng.nextInt(100) < REVISIT_PERCENT) {
            return weightedPick(revisits, rng)
        }

        // ── 3. Fresh: realm-distance pressure + skip penalty ──────────────
        // Diversity keys on the first TWO path segments: across all knowledge
        // that is realm + subrealm, and inside a custom net (where every path
        // shares the net-name top segment) it is the SUBTOPIC — so consecutive
        // questions roam the net's different areas instead of clustering.
        val recentRealms = answers
            .takeLast(REALM_MEMORY)
            .mapNotNull { a -> byId[a.questionId]?.let { diversityKey(it) } }
            .toSet()
        val diverse = candidates.filter { q ->
            diversityKey(q) !in recentRealms
        }.ifEmpty { candidates }
        val fresh = diverse.filter { it.skipCount == 0 }.ifEmpty { diverse }
        return fresh.random(rng)
    }

    /** First two domain-path segments, lowercased ("realm > subrealm"); null when untagged. */
    internal fun diversityKey(q: Question): String? {
        val segs = q.domains.firstOrNull()
            ?.split(">")
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?: return null
        return when (segs.size) {
            0 -> null
            1 -> segs[0]
            else -> "${segs[0]} > ${segs[1]}"
        }
    }

    private data class Revisit(val question: Question, val lastCorrect: Boolean)

    /** Latest answer per question, spaced far enough back, never the current one. */
    private fun revisitCandidates(
        questions: List<Question>,
        answers: List<AnswerRecord>,
        currentId: String?,
        byId: Map<String, Question>
    ): List<Revisit>? {
        if (answers.isEmpty()) return null
        val lastByQuestion = HashMap<String, Pair<Int, Boolean>>() // id -> (index, correct)
        answers.forEachIndexed { i, a ->
            lastByQuestion[a.questionId] = i to a.correct // later entries win = latest
        }
        val newestIndex = answers.size - 1
        val out = ArrayList<Revisit>(lastByQuestion.size)
        for ((id, last) in lastByQuestion) {
            if (id == currentId) continue
            if (newestIndex - last.first < REVISIT_GAP) continue
            val q = byId[id] ?: continue
            out.add(Revisit(q, last.second))
        }
        return out.ifEmpty { null }
    }

    private fun weightedPick(candidates: List<Revisit>, rng: Random): Question {
        val total = candidates.sumOf { if (it.lastCorrect) CORRECT_WEIGHT else WRONG_WEIGHT }
        var roll = rng.nextInt(total)
        for (c in candidates) {
            roll -= if (c.lastCorrect) CORRECT_WEIGHT else WRONG_WEIGHT
            if (roll < 0) return c.question
        }
        return candidates.last().question
    }
}
