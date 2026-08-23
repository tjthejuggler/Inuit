package com.example.inuit.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure aggregation of the store into everything the stats screen shows. */
object StatsCalculator {

    /**
     * Minimum answers a 3-hour band needs before it is eligible for the
     * "sharpest / weakest time of day" highlights — below this, accuracy
     * comparisons are noise.
     */
    const val MIN_BAND_ATTEMPTS: Int = 5

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

    /** Answers bucketed by hour of day (0..23) — drives the volume chart. */
    data class HourPoint(val hour: Int, val attempts: Int, val correct: Int) {
        val accuracy: Float get() = if (attempts == 0) 0f else correct.toFloat() / attempts
    }

    /**
     * Answers bucketed into a 3-hour band of the day (00–03, 03–06, …) —
     * robust granularity for accuracy-by-time-of-day (single hours are
     * usually too sparse to compare fairly).
     */
    data class TimeBand(val startHour: Int, val attempts: Int, val correct: Int) {
        val accuracy: Float get() = if (attempts == 0) 0f else correct.toFloat() / attempts
    }

    data class Snapshot(
        val totalAnswers: Int,
        val totalCorrect: Int,
        val accuracy: Float,
        /** Consecutive calendar days with ≥1 answer, ending today (or yesterday
         *  when today has no answers yet — the streak survives until the user
         *  answers again today). */
        val dayStreak: Int,
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
        val growth: List<DayPoint>,
        /** Volume + accuracy per hour of day, 24 entries (00h..23h). */
        val byHour: List<HourPoint> = emptyList(),
        /** Volume + accuracy per 3-hour band, 8 entries (00–03 … 21–24). */
        val byBand: List<TimeBand> = emptyList(),
        /** The hour with the most answers ever; null when nothing answered. */
        val peakHour: HourPoint? = null,
        /** Best-accuracy 3-hour band among those with enough attempts; null if none qualify. */
        val sharpestBand: TimeBand? = null,
        /** Worst-accuracy 3-hour band among those with enough attempts; null if none qualify. */
        val weakestBand: TimeBand? = null,
        /** Active custom net's name when these stats are net-scoped; null for the All net. */
        val netName: String? = null
    ) {
        data class TypeAggLike(val label: String, val attempts: Int, val correct: Int)
    }

    /**
     * @param netName active custom net's name, if any. Inside a net every
     *   domain path shares the net name as its first segment, so "top-level
     *   realm" aggregation would collapse the whole net into one row
     *   ("Juggling — 27/30"). Net mode therefore aggregates — and DISPLAYS —
     *   at the SUBTOPIC level with the redundant net prefix stripped
     *   ("Notation", not "Juggling > Notation": everything on screen is
     *   already that net) for topDomains / domainsExplored / growth / the
     *   domain tree, which is what the stats knowledge map, realm chips and
     *   knowledge-map screen show.
     */
    fun compute(
        questions: List<Question>,
        answers: List<AnswerRecord>,
        domainStats: List<DomainStat>,
        queueSize: Int,
        netName: String? = null
    ): Snapshot {
        val byId = questions.associateBy { it.id }
        val total = answers.size
        val correctTotal = answers.count { it.correct }

        // per-day activity (last 14 days, gaps filled)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        // day-streak of usage: consecutive calendar days with at least one
        // answer, anchored at today — or yesterday when today has none yet.
        val activeDays = HashSet<LocalDate>()
        for (a in answers) {
            activeDays.add(Instant.ofEpochMilli(a.timestamp).atZone(zone).toLocalDate())
        }
        var dayStreak = 0
        var cursor = if (today in activeDays) today else today.minusDays(1)
        while (cursor in activeDays) {
            dayStreak++
            cursor = cursor.minusDays(1)
        }
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

        // domain tree from hierarchical paths (net mode: net root stripped)
        val tree = buildTree(domainStats, netName)

        // top-level aggregation (subtopic level inside a custom net)
        val topAgg = HashMap<String, IntArray>()
        for (s in domainStats) {
            val top = topKey(s.path, netName)
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

        // time-of-day profile: volume + accuracy per hour and per 3-hour band
        val hourAgg = Array(24) { IntArray(2) }
        for (a in answers) {
            val h = Instant.ofEpochMilli(a.timestamp).atZone(zone).hour
            hourAgg[h].let {
                it[0]++
                if (a.correct) it[1]++
            }
        }
        val byHour = hourAgg.mapIndexed { h, v -> HourPoint(h, v[0], v[1]) }
        val byBand = (0 until 24 step 3).map { start ->
            val slice = hourAgg.slice(start until start + 3)
            TimeBand(start, slice.sumOf { it[0] }, slice.sumOf { it[1] })
        }
        val peakHour = byHour.filter { it.attempts > 0 }.maxByOrNull { it.attempts }
        val qualified = byBand.filter { it.attempts >= MIN_BAND_ATTEMPTS }
        val sharpestBand = qualified.maxByOrNull { it.accuracy }
        val weakestBand = qualified.minByOrNull { it.accuracy }

        // knowledge-space growth: cumulative distinct top-level domains over the 14-day window
        val seen = HashSet<String>()
        val answersByDay = answers.groupBy {
            Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate()
        }
        val growth = byDay.map { d ->
            answersByDay[d.day].orEmpty().forEach { a ->
                byId[a.questionId]?.domains?.firstOrNull()?.let { seen.add(topKey(it, netName)) }
            }
            DayPoint(d.day, seen.size, 0)
        }

        return Snapshot(
            totalAnswers = total,
            totalCorrect = correctTotal,
            accuracy = if (total == 0) 0f else correctTotal.toFloat() / total,
            dayStreak = dayStreak,
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
            growth = growth,
            byHour = byHour,
            byBand = byBand,
            peakHour = peakHour,
            sharpestBand = sharpestBand,
            weakestBand = weakestBand,
            netName = netName
        )
    }

    /**
     * Aggregation key for "top-level realm" views: the first path segment,
     * except inside a custom net where net-rooted paths key on the SUBTOPIC
     * alone — the net name is redundant on screen (everything shown is that
     * net) and must not prefix every realm label.
     */
    fun topKey(path: String, netName: String?): String {
        val segs = path.split(" > ").map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return path
        if (netName != null &&
            segs.size >= 2 &&
            segs[0].equals(netName.trim(), ignoreCase = true)
        ) {
            return segs[1]
        }
        return segs[0]
    }

    private fun buildTree(stats: List<DomainStat>, netName: String? = null): List<DomainNode> {
        class Mutable(val name: String, val path: String) {
            var attempts = 0
            var correct = 0
            val children = LinkedHashMap<String, Mutable>()
        }

        val roots = LinkedHashMap<String, Mutable>()
        for (s in stats) {
            if (s.attempts == 0) continue
            var segments = s.path.split(" > ").map { it.trim() }.filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue
            // Net mode: the net-name root would prefix every branch of the
            // tree — drop it so the tree (and the knowledge map built from
            // it) starts at the subtopics themselves.
            if (netName != null && segments.size >= 2 &&
                segments[0].equals(netName.trim(), ignoreCase = true)
            ) {
                segments = segments.drop(1)
            }
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
