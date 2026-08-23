package com.example.inuit

import com.example.inuit.data.AnswerRecord
import com.example.inuit.data.StatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Day-streak of usage: consecutive calendar days with at least one answer,
 * anchored at today — or yesterday when today has no answers yet (the
 * streak survives until the user answers again today). Correctness is
 * irrelevant: this is a usage streak, not a correct-answer run.
 */
class StatsDayStreakTest {

    private val zone = ZoneId.systemDefault()

    private fun answerOn(day: LocalDate, correct: Boolean = true): AnswerRecord {
        val ts = day.atTime(LocalTime.NOON).atZone(zone)
        return AnswerRecord(
            questionId = "q",
            correct = correct,
            userAnswer = "a",
            timestamp = ts.toInstant().toEpochMilli()
        )
    }

    private fun compute(answers: List<AnswerRecord>): StatsCalculator.Snapshot =
        StatsCalculator.compute(emptyList(), answers, emptyList(), 0)

    @Test
    fun `no answers means zero streak`() {
        assertEquals(0, compute(emptyList()).dayStreak)
    }

    @Test
    fun `answers only today count one`() {
        val today = LocalDate.now(zone)
        assertEquals(1, compute(listOf(answerOn(today), answerOn(today))).dayStreak)
    }

    @Test
    fun `consecutive days build a streak`() {
        val today = LocalDate.now(zone)
        val answers = (0..4L).map { answerOn(today.minusDays(it)) }
        assertEquals(5, compute(answers).dayStreak)
    }

    @Test
    fun `gap breaks the streak at the gap`() {
        val today = LocalDate.now(zone)
        val answers = listOf(
            answerOn(today),
            answerOn(today.minusDays(1)),
            answerOn(today.minusDays(5))
        )
        assertEquals(2, compute(answers).dayStreak)
    }

    @Test
    fun `yesterday only still counts until today ends`() {
        val today = LocalDate.now(zone)
        assertEquals(1, compute(listOf(answerOn(today.minusDays(1)))).dayStreak)
    }

    @Test
    fun `streak ignores correctness`() {
        val today = LocalDate.now(zone)
        val answers = listOf(
            answerOn(today, correct = false),
            answerOn(today.minusDays(1), correct = false),
            answerOn(today.minusDays(2), correct = false)
        )
        assertEquals(3, compute(answers).dayStreak)
    }
}
