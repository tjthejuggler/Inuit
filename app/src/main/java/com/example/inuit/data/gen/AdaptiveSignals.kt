package com.example.inuit.data.gen

import com.example.inuit.data.AnswerRecord
import com.example.inuit.data.DomainStat
import com.example.inuit.data.Grader
import com.example.inuit.data.Question
import com.example.inuit.data.QuestionType
import com.example.inuit.data.StatsCalculator

/**
 * Pure adaptive-difficulty signals extracted from answer history (no Android
 * dependencies — unit-testable).
 *
 * The core idea: a WRONG answer is not all the same kind of wrong.
 *  - A near-miss ("Saturn" for "Jupiter") means the user knows the category.
 *  - An OFF-CATEGORY answer ("Africa" for the largest planet) means the user
 *    doesn't even know the basic entities of the domain — they need easier,
 *    recognition-format (multiple choice / true-false) questions there first.
 *
 * The raw user answer is passed to the generator LLM (which has the world
 * knowledge to classify near-miss vs off-category); [Grader.isWildMiss] is the
 * cheap local pre-filter, and [noviceDomains] turns signals into domain flags.
 */
object AdaptiveSignals {

    /** Max characters of the user's raw answer included in a context line. */
    private const val ANSWER_SNIPPET = 60

    /**
     * One context line for a recent answer. For free-text/numeric misses the
     * user's actual answer is included (internal only — never rendered by the
     * UI) so the generator can judge whether it was off-category.
     */
    fun renderAnswerLine(a: AnswerRecord, q: Question?): String {
        val mark = if (a.correct) "✓" else "✗"
        val dom = q?.domains?.firstOrNull() ?: "untagged"
        val diff = q?.difficulty ?: "?"
        val sb = StringBuilder("$mark (d$diff) [$dom] ${q?.prompt ?: "(deleted question)"}")
        if (!a.correct && q != null && showsRawAnswer(q)) {
            val wild = Grader.isWildMiss(q, a.userAnswer)
            sb.append("  → user answered: \"").append(a.userAnswer.take(ANSWER_SNIPPET)).append('"')
            if (wild) sb.append("  [off-category: not even the right kind of entity — treat domain as NOVICE]")
        }
        return sb.toString()
    }

    /** Only these types carry an informative raw answer (MC/TF answers are indexes). */
    private fun showsRawAnswer(q: Question): Boolean =
        q.type == QuestionType.FILL_BLANK || q.type == QuestionType.NUMERIC

    /**
     * Domain paths that should be scaffolded with easy recognition questions.
     * A domain is NOVICE when:
     *  - accuracy < 35% over >= 2 attempts, or
     *  - accuracy < 50% over >= 2 attempts AND at least one wild miss there.
     */
    fun noviceDomains(
        stats: List<DomainStat>,
        wildMissCounts: Map<String, Int>
    ): List<DomainStat> = stats.filter { s ->
        if (s.attempts < 2) return@filter false
        val wild = wildMissCounts[s.path] ?: 0
        s.accuracy < 0.35f || (s.accuracy < 0.5f && wild > 0)
    }

    /**
     * The other half of the adaptive dial: areas the user has CONSISTENTLY
     * MASTERED (>= 80% over >= 3 attempts), aggregated at the same level the
     * stats screen shows (subtopic inside a custom net, top realm otherwise).
     * The generator must ESCALATE difficulty here — sustained correct
     * answers are a signal to climb, not to keep serving comfortable
     * questions (Socratic: stay at the boundary of the user's knowledge).
     */
    fun challengeDomains(
        stats: List<DomainStat>,
        netName: String? = null
    ): List<DomainStat> {
        val agg = LinkedHashMap<String, IntArray>()
        for (s in stats) {
            val key = StatsCalculator.topKey(s.path, netName)
            agg.getOrPut(key) { IntArray(2) }.let {
                it[0] += s.attempts
                it[1] += s.correct
            }
        }
        return agg.map { (k, v) -> DomainStat(k, v[0], v[1], 0) }
            .filter { it.attempts >= 3 && it.accuracy >= 0.8f }
            .sortedWith(
                compareByDescending<DomainStat> { it.accuracy }.thenByDescending { it.attempts }
            )
            .take(8)
    }

    /**
     * Counts wild misses per top-level domain over the recent answer window
     * (the question's first domain path, truncated to its top realm so the
     * signal aggregates instead of scattering across deep paths).
     */
    fun wildMissCounts(
        recent: List<AnswerRecord>,
        byId: Map<String, Question>
    ): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (a in recent) {
            if (a.correct) continue
            val q = byId[a.questionId] ?: continue
            if (!Grader.isWildMiss(q, a.userAnswer)) continue
            val dom = q.domains.firstOrNull()?.substringBefore(" > ") ?: continue
            out[dom] = (out[dom] ?: 0) + 1
        }
        return out
    }
}
