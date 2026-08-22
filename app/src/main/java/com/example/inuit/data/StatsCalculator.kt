package com.example.inuit.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure aggregation of the store into everything the stats screen shows. */
object StatsCalculator {

    data class DomainAgg(val path: String, val attempts: Int, val correct: Int) {
        val accuracy: Float get() = if (attempts == 0) 0f else correct.toFloat() / attempts
    }

    data class TypeAgg(val type: QuestionType, val attempts: Int, val correct: Int)

    data class DomainNode(
        val name: String,
        val path: String,
        val attempts: Int,
        val correct: Int,
        val children: List<DomainNode>
    ) {
        /** Subtree totals — the map "grows in complexity" as deeper tags appear. */
        val totalAttempts: Int by lazy { attempts + children.sumOf { it.totalAttempts } }
        val totalCorrect: Int by lazy { correct + children.sumOf { it.totalCorrect } }
        val totalAccuracy: Float
            get() = if (totalAttempts == 0) 0f else totalCorrect.toFloat() / totalAttempts
    }

    data class DayPoint(val day: LocalDate, val count: Int, val correct: Int) {
        val accuracy: Float get() = if (count == 0) 0f else correct.toFloat() / count
    }

    data class Snapshot(
        val totalAnswers: Int,
        val totalCorrect: Int,
        val accuracy: Float,
        val streak: Int,
        val bestStreak: Int,
        val domainsExplored: Int,
        val distinctDomains: Int,
        val queueSize: Int,
        val byDay: List<DayPoint>,
        val accuracyTrend: List<Float>,
        val domainTree: List<DomainNode>,
        val topDomains: List<DomainAgg>,
        val weakest: List<DomainAgg>,
        val strongest: List<DomainAgg>,
        val byDifficulty: List<TypeAggLike>,
        val byType: List<TypeAgg>,
        val growth: List<DayPoint>
    ) {
        data class TypeAggLike(val label: String, val attempts: Int, val correct: Int)
    }

    fun compute(
        questions: List<Question>,
        answers: List<AnswerRecord>,
        domainStats: List<DomainStat>,
        queueSize: Int
    ): Snapshot {
        val byId = questions.associateBy { it.id }
        val total = answers.size
        val correctTotal = answers.count { it.correct }

        // streaks (chronological)
        var streak = 0
        for (a in answers.asReversed()) {
            if (a.correct) streak++ else break
        }
        var best = 0
        var run = 0
        for (a in answers) {
            run = if (a.correct) run + 1 else 0
            if (run > best) best = run
        }

        // per-day activity (last 14 days, gaps filled)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dayBuckets = LinkedHashMap<LocalDate, IntArray>() // [count, correct]
        for (i in 13 downTo 0) dayBuckets[today.minusDays(i.toLong())] = IntArray(2)
        for (a in answers) {
            val d = Instant.ofEpochMilli(a.timestamp).atZone(zone).toLocalDate()
            dayBuckets.getOrPut(d) { IntArray(2) }.let {
                it[0]++
                if (a.correct) it[1]++
            }
        }
        val byDay = dayBuckets.entries.sortedBy { it.key }.map { DayPoint(it.key, it.value[0], it.value[1]) }

        // rolling accuracy trend: buckets of 10 answers
        val trend = ArrayList<Float>()
        if (total >= 5) {
            val step = 10
            var i = 0
            while (i < total) {
                val window = answers.subList(i, minOf(i + step, total))
                trend.add(window.count { it.correct }.toFloat() / window.size)
                i += step
            }
        }

        // domain tree from hierarchical paths
        val tree = buildTree(domainStats)

        // top-level aggregation
        val topAgg = HashMap<String, IntArray>()
        for (s in domainStats) {
            val top = s.path.substringBefore(" > ")
            topAgg.getOrPut(top) { IntArray(2) }.let {
                it[0] += s.attempts
                it[1] += s.correct
            }
        }
        val topDomains = topAgg.map { (k, v) -> DomainAgg(k, v[0], v[1]) }
            .sortedByDescending { it.attempts }

        val withAttempts = domainStats.filter { it.attempts >= 3 }
        val weakest = withAttempts
            .sortedWith(compareBy<DomainStat> { it.accuracy }.thenByDescending { it.attempts })
            .take(8).map { DomainAgg(it.path, it.attempts, it.correct) }
        val strongest = withAttempts
            .sortedWith(compareByDescending<DomainStat> { it.accuracy }.thenByDescending { it.attempts })
            .take(8).map { DomainAgg(it.path, it.attempts, it.correct) }

        // difficulty + type profiles
        val diffAgg = Array(5) { IntArray(2) }
        val typeAgg = HashMap<QuestionType, IntArray>()
        for (a in answers) {
            val q = byId[a.questionId] ?: continue
            diffAgg[q.difficulty - 1].let {
                it[0]++
                if (a.correct) it[1]++
            }
            typeAgg.getOrPut(q.type) { IntArray(2) }.let {
                it[0]++
                if (a.correct) it[1]++
            }
        }
        val byDifficulty = diffAgg.mapIndexed { i, v ->
            Snapshot.TypeAggLike("D${i + 1}", v[0], v[1])
        }
        val byType = typeAgg.map { (k, v) -> TypeAgg(k, v[0], v[1]) }
            .sortedByDescending { it.attempts }

        // knowledge-space growth: cumulative distinct top-level domains over the 14-day window
        val seen = HashSet<String>()
        val answersByDay = answers.groupBy {
            Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate()
        }
        val growth = byDay.map { d ->
            answersByDay[d.day].orEmpty().forEach { a ->
                byId[a.questionId]?.domains?.firstOrNull()?.let { seen.add(it.substringBefore(" > ")) }
            }
            DayPoint(d.day, seen.size, 0)
        }

        return Snapshot(
            totalAnswers = total,
            totalCorrect = correctTotal,
            accuracy = if (total == 0) 0f else correctTotal.toFloat() / total,
            streak = streak,
            bestStreak = best,
            domainsExplored = topAgg.size,
            distinctDomains = domainStats.map { it.path }.distinct().size,
            queueSize = queueSize,
            byDay = byDay,
            accuracyTrend = trend,
            domainTree = tree,
            topDomains = topDomains,
            weakest = weakest,
            strongest = strongest,
            byDifficulty = byDifficulty,
            byType = byType,
            growth = growth
        )
    }

    private fun buildTree(stats: List<DomainStat>): List<DomainNode> {
        class Mutable(val name: String, val path: String) {
            var attempts = 0
            var correct = 0
            val children = LinkedHashMap<String, Mutable>()
        }

        val roots = LinkedHashMap<String, Mutable>()
        for (s in stats) {
            if (s.attempts == 0) continue
            val segments = s.path.split(" > ").map { it.trim() }.filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue
            var level = roots
            var pathSoFar = ""
            var node: Mutable? = null
            for (seg in segments) {
                pathSoFar = if (pathSoFar.isEmpty()) seg else "$pathSoFar > $seg"
                node = level.getOrPut(seg) { Mutable(seg, pathSoFar) }
                level = node.children
            }
            node?.let {
                it.attempts += s.attempts
                it.correct += s.correct
            }
        }

        fun toNode(m: Mutable): DomainNode = DomainNode(
            name = m.name,
            path = m.path,
            attempts = m.attempts,
            correct = m.correct,
            children = m.children.values.map { toNode(it) }.sortedByDescending { it.totalAttempts }
        )

        return roots.values.map { toNode(it) }.sortedByDescending { it.totalAttempts }
    }
}
