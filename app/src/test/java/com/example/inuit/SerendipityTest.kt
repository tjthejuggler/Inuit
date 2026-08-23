package com.example.inuit

import com.example.inuit.data.AnswerRecord
import com.example.inuit.data.DomainStat
import com.example.inuit.data.Question
import com.example.inuit.data.QuestionType
import com.example.inuit.data.gen.RealmTaxonomy
import com.example.inuit.data.gen.Serendipity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SerendipityTest {

    private val day = 24.0 * 60 * 60 * 1000

    private fun q(id: String, vararg domains: String) = Question(
        id = id,
        type = QuestionType.TRUE_FALSE,
        prompt = "p $id",
        answerBool = true,
        domains = domains.toList()
    )

    private fun answer(qid: String, ts: Long) =
        AnswerRecord(questionId = qid, correct = true, userAnswer = "true", timestamp = ts)

    @Test
    fun `taxonomy is wide and wellformed`() {
        assertTrue("at least 40 realms", RealmTaxonomy.REALMS.size >= 40)
        assertTrue("at least 200 paths", RealmTaxonomy.ALL_PATHS.size >= 200)
        RealmTaxonomy.ALL_PATHS.forEach {
            assertTrue("path has realm + subrealm: $it", RealmTaxonomy.segments(it).size >= 2)
        }
    }

    @Test
    fun `recent heavy realm is distant-low scoring`() {
        val now = 100L * day.toLong()
        val physics = q("q1", "Science > Physics > Optics")
        val byId = mapOf(physics.id to physics)
        // answered physics questions all through today
        val answers = (0 until 10).map { answer("q1", now - it * 3_600_000L) }
        val profile = Serendipity.decayedProfile(answers, byId, now)

        val near = Serendipity.distance("Science > Physics > Acoustics", profile)
        val far = Serendipity.distance("Heraldry & Signs > Vexillology", profile)
        assertTrue("near realm scores closer than far realm ($near vs $far)", near < far)
        assertTrue("untouched realm is nearly maximally distant", far > 0.9f)
    }

    @Test
    fun `distance grows as memories decay`() {
        val physics = q("q1", "Science > Physics > Optics")
        val byId = mapOf(physics.id to physics)
        val now = 100L * day.toLong()

        val fresh = Serendipity.decayedProfile(listOf(answer("q1", now - 1_000_000L)), byId, now)
        val stale = Serendipity.decayedProfile(listOf(answer("q1", now - 30L * day.toLong())), byId, now)

        val dFresh = Serendipity.distance("Science > Physics > Acoustics", fresh)
        val dStale = Serendipity.distance("Science > Physics > Acoustics", stale)
        assertTrue("stale memory → more distant ($dFresh vs $dStale)", dStale > dFresh)
    }

    @Test
    fun `plan avoids recently active realms in distant frontiers`() {
        val now = 100L * day.toLong()
        val physics = q("q1", "Science > Physics > Optics")
        val byId = mapOf(physics.id to physics)
        val answers = (0 until 8).map { answer("q1", now - it * 3_600_000L) }

        val plan = Serendipity.planFrontiers(
            recentAnswers = answers,
            questionsById = byId,
            domainStats = listOf(DomainStat("Science > Physics > Optics", 8, 8, now)),
            llmFrontiers = emptyList(),
            nowMs = now,
            rng = Random(42)
        )

        assertEquals(8, plan.distant.size)
        // frontier diversity: one suggestion per top-level realm
        val realms = plan.distant.map { RealmTaxonomy.topRealm(it) }
        assertEquals(realms.size, realms.distinct().size)
        // the just-trodden realm must not appear among distant suggestions
        assertTrue(
            plan.distant.none { RealmTaxonomy.topRealm(it) == "science > physics" }
        )
    }

    @Test
    fun `revisits surface old weak threads but not fresh ones`() {
        val now = 100L * day.toLong()
        val q1 = q("q1", "History > Middle Ages")
        val byId = mapOf(q1.id to q1)
        val answers = listOf(
            answer("q1", now - 20L * day.toLong()), // old, weak → revisit candidate
            answer("q1", now - 1L * day.toLong())   // fresh → excluded
        )

        val plan = Serendipity.planFrontiers(
            recentAnswers = answers,
            questionsById = byId,
            domainStats = listOf(
                DomainStat("History > Middle Ages", 5, 1, now - 1L * day.toLong()), // too fresh
                DomainStat("Mathematics > Number Theory", 6, 1, now - 14L * day.toLong()) // old & weak
            ),
            llmFrontiers = emptyList(),
            nowMs = now,
            rng = Random(7)
        )

        assertTrue(plan.revisits.contains("Mathematics > Number Theory"))
        assertTrue(plan.revisits.none { it.startsWith("History") })
    }

    @Test
    fun `llm frontiers participate as candidates`() {
        val now = 100L * day.toLong()
        val plan = Serendipity.planFrontiers(
            recentAnswers = emptyList(),
            questionsById = emptyMap(),
            domainStats = emptyList(),
            llmFrontiers = listOf("Cartography > Map Projections"),
            nowMs = now,
            rng = Random(1)
        )
        // with an empty profile everything is maximally distant; the LLM
        // proposal competes on equal footing (may or may not be picked),
        // and the plan is still well-formed.
        assertEquals(8, plan.distant.size)
        assertTrue(plan.distant.all { RealmTaxonomy.topRealm(it) != null })
    }

    @Test
    fun `net mode scopes frontiers to net subtopics and dedupes by subtopic`() {
        val now = 100L * day.toLong()
        val answered = q("q1", "Juggling > Siteswap")
        val queued = q("q2", "Juggling > History")
        val queuedDeep = q("q3", "Juggling > History > Vaudeville")
        val byId = mapOf(answered.id to answered, queued.id to queued, queuedDeep.id to queuedDeep)
        val answers = (0 until 5).map { answer("q1", now - it * 3_600_000L) }

        val plan = Serendipity.planFrontiers(
            recentAnswers = answers,
            questionsById = byId,
            domainStats = listOf(DomainStat("Juggling > Siteswap", 5, 5, now)),
            llmFrontiers = listOf("Juggling > Records", "Vexillology > Flags", "Juggling > Records"),
            nowMs = now,
            rng = Random(3),
            netName = "Juggling"
        )

        // no all-knowledge taxonomy leaks into a net's frontier plan
        assertTrue(plan.distant.isNotEmpty())
        assertTrue(plan.distant.none { it.startsWith("Vexillology") })
        assertTrue(plan.distant.all { it.startsWith("Juggling") })
        // diversity is enforced at the subtopic level inside a net
        val keys = plan.distant.mapNotNull { Serendipity.subtopicKey(it) }
        assertEquals(keys.size, keys.distinct().size)
        // unseen queued subtopics count as unexplored frontier territory
        assertTrue(
            plan.distant.any { it == "Juggling > History" || it == "Juggling > History > Vaudeville" }
        )
    }

    @Test
    fun `net mode revisits dedupe by subtopic not by net`() {
        val now = 100L * day.toLong()
        val q1 = q("q1", "Juggling > Siteswap")
        val byId = mapOf(q1.id to q1)
        val answers = listOf(answer("q1", now - 20L * day.toLong()))

        val plan = Serendipity.planFrontiers(
            recentAnswers = answers,
            questionsById = byId,
            domainStats = listOf(
                DomainStat("Juggling > Siteswap", 6, 1, now - 14L * day.toLong()),
                DomainStat("Juggling > History", 6, 2, now - 14L * day.toLong())
            ),
            llmFrontiers = emptyList(),
            nowMs = now,
            rng = Random(11),
            netName = "Juggling"
        )

        // both aged weak subtopics revisit — the old top-realm dedupe would
        // have collapsed them to one because both share the "Juggling" root
        assertTrue(plan.revisits.contains("Juggling > Siteswap"))
        assertTrue(plan.revisits.contains("Juggling > History"))
    }
}
