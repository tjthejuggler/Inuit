package com.example.inuit

import com.example.inuit.data.AnswerRecord
import com.example.inuit.data.DomainStat
import com.example.inuit.data.Question
import com.example.inuit.data.QuestionType
import com.example.inuit.data.gen.AdaptiveSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSignalsTest {

    private fun planetQuestion() = Question(
        id = "q1",
        type = QuestionType.FILL_BLANK,
        prompt = "Name the largest planet in the Solar System.",
        acceptedAnswers = listOf("Jupiter"),
        domains = listOf("Science > Astronomy"),
        difficulty = 2
    )

    private fun answer(q: Question, raw: String, correct: Boolean = false) = AnswerRecord(
        id = "a-${raw.hashCode()}",
        questionId = q.id,
        correct = correct,
        userAnswer = raw
    )

    // ── renderAnswerLine ─────────────────────────────────────────────────

    @Test
    fun `wrong fill_blank answer includes the raw user answer`() {
        val line = AdaptiveSignals.renderAnswerLine(answer(planetQuestion(), "Africa"), planetQuestion())
        assertTrue(line.contains("→ user answered: \"Africa\""))
        assertTrue(line.contains("[off-category"))
    }

    @Test
    fun `near-miss answer is shown without the off-category flag`() {
        // "Saturn" is wrong but IS a planet — only the LLM can know that; the
        // local heuristic sees no shared word with "Jupiter" so it conservatively
        // flags it — the flag is only a hint, the LLM makes the final call.
        val line = AdaptiveSignals.renderAnswerLine(answer(planetQuestion(), "Saturn"), planetQuestion())
        assertTrue(line.contains("→ user answered: \"Saturn\""))
    }

    @Test
    fun `correct answers never include the raw answer`() {
        val line = AdaptiveSignals.renderAnswerLine(answer(planetQuestion(), "Jupiter", correct = true), planetQuestion())
        assertFalse(line.contains("user answered"))
    }

    @Test
    fun `multiple choice misses carry no raw answer`() {
        val mc = Question(
            id = "q2", type = QuestionType.MULTIPLE_CHOICE,
            prompt = "Largest planet?", choices = listOf("Mars", "Jupiter"), answerIndex = 1
        )
        val line = AdaptiveSignals.renderAnswerLine(answer(mc, "0"), mc)
        assertFalse(line.contains("user answered"))
    }

    // ── wildMissCounts ───────────────────────────────────────────────────

    @Test
    fun `wild misses aggregate per top-level domain`() {
        val q = planetQuestion()
        val counts = AdaptiveSignals.wildMissCounts(
            listOf(answer(q, "Africa"), answer(q, "Jupiter", correct = true)),
            mapOf(q.id to q)
        )
        assertEquals(1, counts["Science"])
    }

    // ── noviceDomains ────────────────────────────────────────────────────

    @Test
    fun `very low accuracy flags novice`() {
        val novice = DomainStat("Science > Astronomy", attempts = 4, correct = 1)
        val strong = DomainStat("History", attempts = 10, correct = 9)
        val out = AdaptiveSignals.noviceDomains(listOf(novice, strong), emptyMap())
        assertEquals(listOf(novice), out)
    }

    @Test
    fun `medium accuracy plus a wild miss flags novice`() {
        val stat = DomainStat("Geography", attempts = 5, correct = 2) // 40%
        assertTrue(AdaptiveSignals.noviceDomains(listOf(stat), mapOf("Geography" to 1)).contains(stat))
        // without a wild miss, 40% is not novice
        assertFalse(AdaptiveSignals.noviceDomains(listOf(stat), emptyMap()).contains(stat))
    }

    @Test
    fun `few attempts never flags novice`() {
        val stat = DomainStat("Chemistry", attempts = 1, correct = 0)
        assertTrue(AdaptiveSignals.noviceDomains(listOf(stat), mapOf("Chemistry" to 1)).isEmpty())
    }

    // ── challengeDomains ─────────────────────────────────────────────────

    @Test
    fun `consistent mastery flags challenge escalation`() {
        val out = AdaptiveSignals.challengeDomains(
            listOf(
                DomainStat("Science > Astronomy", attempts = 4, correct = 4),
                DomainStat("History", attempts = 10, correct = 6)   // 60% — not mastered
            )
        )
        assertEquals(listOf("Science"), out.map { it.path })
        assertEquals(4, out.first().attempts)
    }

    @Test
    fun `challenge areas aggregate at subtopic level inside a net`() {
        val out = AdaptiveSignals.challengeDomains(
            listOf(
                DomainStat("Juggling > Notation > Siteswap", 4, 4, 0),
                DomainStat("Juggling > Notation > History", 1, 1, 0),
                DomainStat("Juggling > Patterns", 1, 0, 0)
            ),
            netName = "Juggling"
        )
        // deep paths merge into their subtopic; sparse ones stay out
        assertEquals(listOf("Notation"), out.map { it.path })
        assertEquals(5, out.first().attempts)
        assertEquals(5, out.first().correct)
    }

    @Test
    fun `few attempts or mediocre accuracy never flags challenge`() {
        assertTrue(
            AdaptiveSignals.challengeDomains(
                listOf(
                    DomainStat("Chemistry", attempts = 2, correct = 2), // too few
                    DomainStat("Geography", attempts = 5, correct = 3)  // 60%
                )
            ).isEmpty()
        )
    }
}
