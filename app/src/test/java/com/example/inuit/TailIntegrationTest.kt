package com.example.inuit

import com.example.inuit.data.AnswerRecord
import com.example.inuit.data.aggregateAnswersByDate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Backfill aggregation for the Tail integration: answers must be bucketed by
 * LOCAL calendar date (Tail buckets days with LocalDate.now()), so a 23:30Z
 * answer lands on the next day in a UTC+2 zone.
 */
class TailIntegrationTest {

    private val zone = ZoneId.of("Europe/Rome")

    private fun answerAt(iso: String) = AnswerRecord(
        questionId = "q",
        correct = true,
        userAnswer = "a",
        timestamp = Instant.parse(iso).toEpochMilli()
    )

    @Test
    fun `aggregates counts per local date`() {
        val answers = listOf(
            answerAt("2026-08-20T23:30:00Z"), // 01:30 next day in Rome → 08-21
            answerAt("2026-08-21T10:00:00Z"), // 12:00 in Rome          → 08-21
            answerAt("2026-08-22T08:00:00Z")  // 10:00 in Rome          → 08-22
        )
        val byDate = aggregateAnswersByDate(answers, zone)
        assertEquals(mapOf("2026-08-21" to 2, "2026-08-22" to 1), byDate)
    }

    @Test
    fun `empty history aggregates to empty map`() {
        assertEquals(emptyMap<String, Int>(), aggregateAnswersByDate(emptyList(), zone))
    }

    @Test
    fun `utc zone buckets by utc date`() {
        val answers = listOf(answerAt("2026-08-20T23:30:00Z"))
        assertEquals(
            mapOf("2026-08-20" to 1),
            aggregateAnswersByDate(answers, ZoneId.of("UTC"))
        )
    }
}
