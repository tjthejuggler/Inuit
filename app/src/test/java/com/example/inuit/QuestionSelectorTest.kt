package com.example.inuit

import com.example.inuit.data.AnswerRecord
import com.example.inuit.data.Question
import com.example.inuit.data.QuestionSelector
import com.example.inuit.data.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pure-JVM tests for the selection strategy: Socratic threading (gated on
 * the session boundary, choice-format preferred), spaced wrong-weighted
 * revisits, and the empty-queue fallback.
 */
class QuestionSelectorTest {

    private val boundary = 1_000L

    private fun q(
        id: String,
        type: QuestionType = QuestionType.MULTIPLE_CHOICE,
        domain: String = "Alpha > One",
        served: Int = 0,
        rootId: String? = null
    ) = Question(
        id = id, type = type, prompt = id, domains = listOf(domain),
        servedCount = served, rootId = rootId
    )

    private fun a(qid: String, correct: Boolean, ts: Long = 0L) =
        AnswerRecord(questionId = qid, correct = correct, userAnswer = "x", timestamp = ts)

    @Test
    fun `thread surfaces prior-session sub-questions but never current-session ones`() {
        val questions = listOf(
            q("R1", served = 1, domain = "Alpha > One"),
            q("S1", domain = "Alpha > One", rootId = "R1"),
            q("R2", served = 1, domain = "Beta > Two"),
            q("S2", domain = "Beta > Two", rootId = "R2"),
            q("F", domain = "Gamma > Three")
        )
        val answers = listOf(
            a("R1", correct = false, ts = 500),   // before boundary → thread root
            a("R2", correct = false, ts = 5_000)  // current session → must NOT thread
        )
        var threadPicks = 0
        var freshPicks = 0
        val rng = Random(42)
        repeat(300) {
            when (QuestionSelector.select(questions, answers, null, boundary, rng)?.id) {
                "S1" -> threadPicks++
                "F" -> freshPicks++
                "S2" -> error("current-session sub-question surfaced — leaks correctness")
            }
        }
        assertTrue("thread sub-question should surface", threadPicks > 0)
        assertTrue("fresh question should surface", freshPicks > 0)
    }

    @Test
    fun `thread prefers choice-format sub-questions`() {
        val questions = listOf(
            q("R", served = 1, domain = "Alpha > One"),
            q("S_MC", type = QuestionType.MULTIPLE_CHOICE, domain = "Alpha > One", rootId = "R"),
            q("S_NUM", type = QuestionType.NUMERIC, domain = "Alpha > One", rootId = "R"),
            q("F", domain = "Gamma > Three")
        )
        val answers = listOf(a("R", correct = false, ts = 500))
        var mcPicks = 0
        val rng = Random(7)
        repeat(200) {
            when (QuestionSelector.select(questions, answers, null, boundary, rng)?.id) {
                "S_MC" -> mcPicks++
                "S_NUM" -> error("numeric sub-question picked over multiple-choice")
            }
        }
        assertTrue(mcPicks > 0)
    }

    @Test
    fun `revisit is spaced, wrong-weighted and skips the current question`() {
        val questions = listOf(
            q("W", served = 1, domain = "Alpha > One"),
            q("C", served = 1, domain = "Beta > Two"),
            q("RECENT", served = 1, domain = "Gamma > Three"),
            q("F", served = 0, domain = "Delta > Four")
        )
        // indices: W=0, C=1, fillers 2..9, RECENT=10, ghost=11 (newest)
        val answers = buildList {
            add(a("W", correct = false))
            add(a("C", correct = true))
            repeat(8) { i -> add(a("ghost-$i", correct = true)) }
            add(a("RECENT", correct = false))
            add(a("ghost-9", correct = true))
        }
        var wrongPicks = 0
        var correctPicks = 0
        var freshPicks = 0
        val rng = Random(1234)
        repeat(1_000) {
            when (QuestionSelector.select(questions, answers, "RECENT", boundary, rng)?.id) {
                "W" -> wrongPicks++
                "C" -> correctPicks++
                "F" -> freshPicks++
                "RECENT" -> error("current question re-served")
                else -> error("unexpected pick")
            }
        }
        assertTrue("revisits must happen", wrongPicks + correctPicks > 0)
        assertTrue("wrong answers must dominate revisits", wrongPicks > correctPicks)
        assertTrue("correct answers must still occasionally return", correctPicks > 0)
        assertTrue("fresh question must be served", freshPicks > 0)
    }

    @Test
    fun `empty queue falls back to a spaced revisit`() {
        val questions = listOf(q("W", served = 1))
        val answers = buildList {
            add(a("W", correct = false))
            repeat(6) { i -> add(a("ghost-$i", correct = true)) } // gap = 6 → eligible
        }
        val pick = QuestionSelector.select(questions, answers, null, boundary, Random(1))
        assertNotNull("fallback revisit expected", pick)
        assertEquals("W", pick!!.id)
    }

    @Test
    fun `empty queue with only recent answers returns null`() {
        val questions = listOf(q("W", served = 1))
        val answers = listOf(a("W", correct = false)) // gap = 0 → not eligible
        assertNull(QuestionSelector.select(questions, answers, null, boundary, Random(1)))
    }

    @Test
    fun `current question is never the immediate revisit even when eligible`() {
        val questions = listOf(q("W", served = 1))
        val answers = buildList {
            add(a("W", correct = false))
            repeat(6) { i -> add(a("ghost-$i", correct = true)) }
        }
        assertNull(QuestionSelector.select(questions, answers, "W", boundary, Random(1)))
    }

    @Test
    fun `nothing at all returns null`() {
        assertNull(QuestionSelector.select(emptyList(), emptyList(), null, boundary, Random(1)))
    }
}
