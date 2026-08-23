package com.example.inuit

import com.example.inuit.data.Grader
import com.example.inuit.data.Question
import com.example.inuit.data.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraderTest {

    private fun tf(answer: Boolean) = Question(
        type = QuestionType.TRUE_FALSE, prompt = "Is the sky blue on a clear day?", answerBool = answer
    )

    private fun mc(answer: Int) = Question(
        type = QuestionType.MULTIPLE_CHOICE,
        prompt = "Largest planet?",
        choices = listOf("Mars", "Jupiter", "Venus"),
        answerIndex = answer
    )

    private fun num(answer: Double, tol: Double?) = Question(
        type = QuestionType.NUMERIC,
        prompt = "Speed of light (km/s)?",
        answerNumber = answer,
        tolerance = tol
    )

    private fun fill(vararg accepted: String) = Question(
        type = QuestionType.FILL_BLANK,
        prompt = "Capital of France?",
        acceptedAnswers = accepted.toList()
    )

    @Test
    fun trueFalse() {
        assertTrue(Grader.grade(tf(true), "true"))
        assertTrue(Grader.grade(tf(true), "TRUE"))
        assertTrue(Grader.grade(tf(false), "false"))
        assertFalse(Grader.grade(tf(true), "false"))
    }

    @Test
    fun multipleChoice() {
        assertTrue(Grader.grade(mc(1), "1"))
        assertFalse(Grader.grade(mc(1), "0"))
        assertFalse(Grader.grade(mc(1), "abc"))
    }

    @Test
    fun numericTolerance() {
        assertTrue(Grader.grade(num(299792.458, 1.0), "299792"))
        assertTrue(Grader.grade(num(299792.458, 1.0), "299,792.5"))
        assertTrue(Grader.grade(num(3.0e8, 1e6), "300000000"))
        assertFalse(Grader.grade(num(299792.458, 1.0), "300000"))
        assertNull(Grader.parseNumber("not a number"))
    }

    @Test
    fun fillBlankNormalization() {
        assertTrue(Grader.grade(fill("Paris"), "paris"))
        assertTrue(Grader.grade(fill("Paris"), " Paris "))
        assertTrue(Grader.grade(fill("Paris"), "the paris"))
        assertTrue(Grader.grade(fill("United Kingdom", "UK"), "united kingdom"))
        assertTrue(Grader.grade(fill("United Kingdom", "UK"), "UK"))
        // typo tolerance for long answers
        assertTrue(Grader.grade(fill("Chrysanthemum"), "chrysanthemun"))
        assertFalse(Grader.grade(fill("Paris"), "london"))
    }

    @Test
    fun normalization() {
        assertEquals("eiffel tower", Grader.normalize("The Eiffel-Tower!"))
        assertEquals("cat", Grader.normalize("a cat's"))
    }

    @Test
    fun levenshtein() {
        assertEquals(0, Grader.levenshtein("abc", "abc"))
        assertEquals(1, Grader.levenshtein("abc", "abd"))
        assertEquals(3, Grader.levenshtein("abc", "xyz"))
    }

    // ── wild misses (off-category answers) ───────────────────────────────

    private val planets = Question(
        type = QuestionType.FILL_BLANK,
        prompt = "Name the largest planet in the Solar System.",
        acceptedAnswers = listOf("Jupiter")
    )

    @Test
    fun `continent named as planet is a wild miss`() {
        assertTrue(Grader.isWildMiss(planets, "Africa"))
        assertTrue(Grader.isWildMiss(planets, "the ocean"))
    }

    @Test
    fun `same-family answers are not wild misses`() {
        // shares the significant word "united"
        val uk = Question(
            type = QuestionType.FILL_BLANK,
            prompt = "Which country has the Union Jack on its flag?",
            acceptedAnswers = listOf("United Kingdom")
        )
        assertFalse(Grader.isWildMiss(uk, "United States"))
        // typo-level closeness
        assertFalse(Grader.isWildMiss(planets, "Jupite"))
    }

    @Test
    fun `correct answers are never wild misses`() {
        assertFalse(Grader.isWildMiss(planets, "Jupiter"))
    }

    @Test
    fun `non-numeric answer to a numeric question is a wild miss`() {
        val n = Question(
            type = QuestionType.NUMERIC,
            prompt = "Speed of light in km/s?",
            answerNumber = 299792.0
        )
        assertTrue(Grader.isWildMiss(n, "very fast"))
        // a wrong but numeric guess is NOT wild (they understood the question)
        assertFalse(Grader.isWildMiss(n, "150000"))
    }

    @Test
    fun `multiple choice and true-false never report wild misses`() {
        assertFalse(Grader.isWildMiss(mc(1), "0"))
        assertFalse(Grader.isWildMiss(tf(true), "false"))
    }

    @Test
    fun `sharesSignificantWord`() {
        assertTrue(Grader.sharesSignificantWord("united kingdom", "united states"))
        assertFalse(Grader.sharesSignificantWord("jupiter", "africa"))
        assertFalse(Grader.sharesSignificantWord("uk", "uruguay")) // too short to count
    }
}
