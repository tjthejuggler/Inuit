package com.example.inuit.data.gen

import com.example.inuit.data.AnswerRecord
import com.example.inuit.data.DomainStat
import com.example.inuit.data.Question
import kotlin.math.exp
import kotlin.random.Random

/**
 * Serendipity planner — Inuit's embedding-free analog of the Serendipity
 * Engine's "distant topics" retrieval.
 *
 * The Serendipity Engine embeds everything a user has learned into vectors,
 * computes a time-decayed profile vector, and then queries a topic database
 * for the topics FARTHEST from that profile. Inuit has no embedding service,
 * so distance is computed lexically over hierarchical domain paths:
 *
 *  - PROFILE: every recent answer contributes a decaying weight
 *    (half-life ≈ [HALF_LIFE_DAYS]) to each domain path it touched —
 *    the same exponential decay idea as the engine's profileCalculator.
 *  - FAMILIARITY of a candidate path = Σ weight × segment-affinity, where
 *    sharing the top-level realm counts heavily, the second level mildly,
 *    and word overlap weakly.
 *  - DISTANT FRONTIERS = taxonomy + model-proposed paths with maximal
 *    distance from the profile — obscure fields the user has never neared.
 *  - REVISITS = previously seen paths that have aged out of the recency
 *    shadow (circling back), biased toward weaker accuracy.
 */
object Serendipity {

    data class FrontierPlan(
        /** Maximally unfamiliar paths — push the knowledge space wider. */
        val distant: List<String>,
        /** Previously explored paths worth circling back to. */
        val revisits: List<String>
    )

    private const val LN2 = 0.6931471805599453

    /** Familiarity half-life in days: recent questions dominate the profile. */
    const val HALF_LIFE_DAYS = 2.0

    /** A path seen within this many days is too fresh to revisit. */
    const val REVISIT_MIN_AGE_DAYS = 3.0

    private const val DAY_MS = 24.0 * 60 * 60 * 1000
    private const val DISTANT_COUNT = 8
    private const val REVISIT_COUNT = 4
    private const val PROFILE_WINDOW = 250

    /** Weight of one profile entry at the given familiarity mass. */
    private const val FAMILIARITY_SCALE = 1.5f

    fun planFrontiers(
        recentAnswers: List<AnswerRecord>,
        questionsById: Map<String, Question>,
        domainStats: List<DomainStat>,
        llmFrontiers: List<String>,
        nowMs: Long = System.currentTimeMillis(),
        rng: Random = Random.Default
    ): FrontierPlan {
        val profile = decayedProfile(recentAnswers, questionsById, nowMs)
        val seenPaths = domainStats.filter { it.attempts > 0 }.map { it.path }.toSet()
        val seenRealms = seenPaths.mapNotNull { RealmTaxonomy.topRealm(it) }.toSet()

        val candidates = (RealmTaxonomy.ALL_PATHS + llmFrontiers).distinct()
        val distant = pickDistant(candidates, profile, seenRealms, rng)
        val distantRealms = distant.mapNotNull { RealmTaxonomy.topRealm(it) }.toSet()
        val revisits = pickRevisits(domainStats, nowMs, distantRealms, rng)
        return FrontierPlan(distant, revisits)
    }

    // ── profile ──────────────────────────────────────────────────────────

    internal class ProfileEntry(
        val segments: List<String>,
        val tokens: Set<String>,
        var weight: Float
    )

    internal fun decayedProfile(
        answers: List<AnswerRecord>,
        byId: Map<String, Question>,
        nowMs: Long
    ): List<ProfileEntry> {
        val byPath = LinkedHashMap<String, ProfileEntry>()
        for (a in answers.takeLast(PROFILE_WINDOW)) {
            val q = byId[a.questionId] ?: continue
            val ageDays = ((nowMs - a.timestamp).coerceAtLeast(0)) / DAY_MS
            val w = exp(-LN2 * ageDays / HALF_LIFE_DAYS).toFloat()
            for (path in q.domains) {
                val segs = RealmTaxonomy.segments(path)
                if (segs.isEmpty()) continue
                val entry = byPath.getOrPut(path) {
                    ProfileEntry(segs, segs.tokens(), 0f)
                }
                entry.weight += w
            }
        }
        return byPath.values.filter { it.weight > 0.01f }
    }

    private fun List<String>.tokens(): Set<String> =
        flatMap { it.split(" ", "-").filter { it.length > 2 } }.toSet()

    // ── familiarity & distance ───────────────────────────────────────────

    /** How familiar a candidate path feels given the decayed profile (0..∞). */
    internal fun familiarity(candidate: String, profile: List<ProfileEntry>): Float {
        val segs = RealmTaxonomy.segments(candidate)
        if (segs.isEmpty() || profile.isEmpty()) return 0f
        val candTokens = segs.tokens()
        var fam = 0f
        for (e in profile) {
            val affinity = when {
                e.segments.first() == segs.first() -> 1f
                e.segments.getOrNull(1) != null && e.segments[1] == segs.getOrNull(1) -> 0.35f
                else -> {
                    val inter = e.tokens.intersect(candTokens).size
                    val union = e.tokens.union(candTokens).size
                    if (union == 0) 0f else 0.3f * inter / union
                }
            }
            if (affinity > 0f) fam += e.weight * affinity
        }
        return fam
    }

    /** Smooth 0..1 distance: 1 = totally unfamiliar, →0 = heavily trodden. */
    internal fun distance(candidate: String, profile: List<ProfileEntry>): Float {
        val fam = familiarity(candidate, profile)
        return 1f - fam / (fam + FAMILIARITY_SCALE)
    }

    private fun pickDistant(
        candidates: List<String>,
        profile: List<ProfileEntry>,
        seenRealms: Set<String>,
        rng: Random
    ): List<String> {
        data class Scored(val path: String, val realm: String?, val score: Float)

        val scored = candidates.mapNotNull { path ->
            val realm = RealmTaxonomy.topRealm(path) ?: return@mapNotNull null
            var s = distance(path, profile)
            if (realm !in seenRealms) s += 0.15f   // never-touched realm bonus
            s += rng.nextFloat() * 0.05f           // tie-breaking jitter
            Scored(path, realm, s)
        }.sortedByDescending { it.score }

        val out = ArrayList<String>(DISTANT_COUNT)
        val usedRealms = HashSet<String>()
        for (c in scored) {
            if (out.size >= DISTANT_COUNT) break
            val realm = c.realm ?: continue
            if (realm in usedRealms) continue       // frontier diversity itself
            usedRealms.add(realm)
            out.add(c.path)
        }
        return out
    }

    private fun pickRevisits(
        stats: List<DomainStat>,
        nowMs: Long,
        excludedRealms: Set<String>,
        rng: Random
    ): List<String> {
        data class Scored(val path: String, val realm: String?, val score: Float)

        val scored = stats.mapNotNull { s ->
            if (s.attempts < 2) return@mapNotNull null
            val ageDays = ((nowMs - s.lastSeen).coerceAtLeast(0)) / DAY_MS
            if (ageDays < REVISIT_MIN_AGE_DAYS) return@mapNotNull null
            val realm = RealmTaxonomy.topRealm(s.path) ?: return@mapNotNull null
            if (realm in excludedRealms) return@mapNotNull null
            // circle back harder when it was weak and has had time to fade
            val age = (ageDays / 7.0).coerceAtMost(1.5).toFloat()
            val weakness = 1f - s.accuracy
            Scored(s.path, realm, weakness * age + rng.nextFloat() * 0.05f)
        }.sortedByDescending { it.score }

        val out = ArrayList<String>(REVISIT_COUNT)
        val usedRealms = HashSet<String>()
        for (c in scored) {
            if (out.size >= REVISIT_COUNT) break
            val realm = c.realm ?: continue
            if (realm in usedRealms) continue
            usedRealms.add(realm)
            out.add(c.path)
        }
        return out
    }
}
