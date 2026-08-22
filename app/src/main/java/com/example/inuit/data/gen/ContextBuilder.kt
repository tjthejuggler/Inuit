package com.example.inuit.data.gen

import com.example.inuit.data.AnswerRecord
import com.example.inuit.data.DomainStat
import com.example.inuit.data.KnowledgeSummary
import com.example.inuit.data.Question
import com.example.inuit.data.QuestionStore
import kotlin.random.Random

/**
 * Assembles a bounded, stratified context for one generation call.
 *
 * Strategy (keeps token usage flat as history grows):
 *  - last ~40 answers verbatim (recent behavior),
 *  - sampled unknown lineages: wrong answers grouped by lineage root, recent
 *    biased + random older, WITH their existing sub-question chains pulled in
 *    ("related questions from the past"),
 *  - a sample of correct answers (calibration),
 *  - compact domain proficiency digest,
 *  - rolling knowledge summaries (replace raw old history),
 *  - frontier list (novelty pressure).
 */
class ContextBuilder(private val store: QuestionStore, private val rng: Random = Random.Default) {

    data class LineageItem(val question: Question, val status: String)

    data class UnknownGroup(
        val marker: String,
        val root: Question,
        val lineage: List<LineageItem>
    )

    data class Context(
        val recentLines: List<String>,
        val unknownGroups: List<UnknownGroup>,
        val knownLines: List<String>,
        val domainDigest: List<String>,
        val summaries: List<KnowledgeSummary>,
        val frontierLines: List<String>,
        val totalsLine: String
    ) {
        val markerToQuestion: Map<String, Question>
            get() = unknownGroups.associate { it.marker to it.root }
    }

    fun build(): Context {
        val questions = store.snapshotQuestions()
        val answers = store.snapshotAnswers()
        val stats = store.snapshotDomainStats()
        val summaries = store.snapshotSummaries()
        val frontiers = store.snapshotFrontiers()
        val byId = questions.associateBy { it.id }

        // ── recent answers (verbatim, newest last) ────────────────────────
        val recent = answers.takeLast(RECENT_WINDOW)
        val recentLines = recent.map { renderAnswerLine(it, byId) }

        // ── unknown lineages ──────────────────────────────────────────────
        val wrongByRoot = HashMap<String, MutableList<AnswerRecord>>()
        for (a in answers.asReversed()) {
            if (a.correct) continue
            val q = byId[a.questionId] ?: continue
            val root = q.rootId ?: q.id
            wrongByRoot.getOrPut(root) { mutableListOf() }.add(a)
        }
        val roots = wrongByRoot.entries
            .sortedByDescending { it.value.first().timestamp }
            .map { it.key }
        val chosenRoots = ArrayList<String>()
        chosenRoots.addAll(roots.take(RECENT_ROOTS))
        val older = roots.drop(RECENT_ROOTS)
        if (older.isNotEmpty()) {
            chosenRoots.addAll(older.shuffled(rng).take(OLDER_ROOTS))
        }
        val unknownGroups = chosenRoots.mapIndexed { i, rootId ->
            val root = byId[rootId] ?: return@mapIndexed null
            val lineageQuestions = questions
                .filter { it.rootId == rootId && it.id != rootId }
                .sortedBy { it.createdAt }
                .take(LINEAGE_MAX)
            val lineage = lineageQuestions.map { q ->
                LineageItem(q, statusOf(q.id, answers))
            }
            UnknownGroup("U${i + 1}", root, lineage)
        }.filterNotNull()

        // ── known sample (calibration) ────────────────────────────────────
        val correctQIds = answers.filter { it.correct }.map { it.questionId }.distinct()
        val knownLines = correctQIds.shuffled(rng).take(KNOWN_SAMPLE).mapNotNull { id ->
            val q = byId[id] ?: return@mapNotNull null
            "✓ (d${q.difficulty}) [${q.domains.firstOrNull() ?: "untagged"}] ${q.prompt}"
        }

        // ── domain digest ─────────────────────────────────────────────────
        val withAttempts = stats.filter { it.attempts > 0 }
        val weakest = withAttempts.filter { it.attempts >= 3 }
            .sortedWith(compareBy<DomainStat> { it.accuracy }.thenBy { it.path })
            .take(10)
        val strongest = withAttempts.filter { it.attempts >= 3 }
            .sortedWith(compareByDescending<DomainStat> { it.accuracy }.thenBy { it.path })
            .take(5)
        val active = withAttempts.sortedByDescending { it.attempts }.take(10)
        val digest = LinkedHashSet<String>()
        for (s in weakest) digest.add("weak  ${renderStat(s)}")
        for (s in strongest) digest.add("strong ${renderStat(s)}")
        for (s in active) digest.add("active ${renderStat(s)}")

        // ── frontiers (novelty pressure) ──────────────────────────────────
        val seenPaths = stats.map { it.path }.toSet()
        val untouched = SEED_FRONTIERS.filter { it !in seenPaths }
        val frontierPool = (frontiers + untouched).distinct()
        val frontierLines = frontierPool.take(12).ifEmpty { listOf(SEED_FRONTIER_FALLBACK) }

        val totals = "answers=${answers.size} correct=${answers.count { it.correct }} " +
            "questionsAsked=${questions.count { it.servedCount > 0 }} queued=${store.queueSize()}"

        return Context(recentLines, unknownGroups, knownLines, digest.toList(), summaries, frontierLines, totals)
    }

    private fun statusOf(questionId: String, answers: List<AnswerRecord>): String {
        val records = answers.filter { it.questionId == questionId }
        val last = records.lastOrNull() ?: return "unseen"
        return if (last.correct) "answered-correctly" else "answered-wrong"
    }

    private fun renderAnswerLine(a: AnswerRecord, byId: Map<String, Question>): String {
        val q = byId[a.questionId]
        val mark = if (a.correct) "✓" else "✗"
        val dom = q?.domains?.firstOrNull() ?: "untagged"
        val diff = q?.difficulty ?: "?"
        return "$mark (d$diff) [$dom] ${q?.prompt ?: "(deleted question)"}"
    }

    private fun renderStat(s: DomainStat): String =
        "${s.path} — ${s.correct}/${s.attempts} (${(s.accuracy * 100).toInt()}%)"

    companion object {
        private const val RECENT_WINDOW = 40
        private const val RECENT_ROOTS = 8
        private const val OLDER_ROOTS = 5
        private const val LINEAGE_MAX = 8
        private const val KNOWN_SAMPLE = 8
        private const val SEED_FRONTIER_FALLBACK = "Mathematics"

        /** Diverse seed realms used until real stats accumulate. */
        val SEED_FRONTIERS = listOf(
            "Science > Physics", "Science > Chemistry", "Science > Biology",
            "Mathematics", "History", "Geography", "Literature", "Music",
            "Visual Arts", "Philosophy", "Religion & Mythology", "Language & Etymology",
            "Technology & Computing", "Engineering", "Medicine & Human Body",
            "Psychology & Mind", "Economics & Finance", "Law & Politics",
            "Sports & Games", "Food & Cooking", "Everyday Statistics",
            "Nature & Animals", "Space & Astronomy", "Culture & Folklore",
            "Measurement & Units", "Inventions & Discoveries"
        )
    }
}
