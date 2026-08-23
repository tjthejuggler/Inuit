package com.example.inuit

import com.example.inuit.data.AnswerRecord
import com.example.inuit.data.aggregateAnswerTimesByDate
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

    // ── protocol v5: per-date answer TIMES ────────────────────────────────

    @Test
    fun `aggregates sorted HH-mm-ss times per local date`() {
        val answers = listOf(
            answerAt("2026-08-21T10:00:02Z"), // 12:00:02 in Rome
            answerAt("2026-08-21T09:59:58Z"), // 11:59:58 in Rome
            answerAt("2026-08-22T08:00:00Z")  // 10:00:00 in Rome
        )
        val byDate = aggregateAnswerTimesByDate(answers, zone)
        assertEquals(
            mapOf(
                "2026-08-21" to listOf("11:59:58", "12:00:02"),
                "2026-08-22" to listOf("10:00:00")
            ),
            byDate
        )
    }

    @Test
    fun `times and counts cover identical dates`() {
        val answers = listOf(
            answerAt("2026-08-20T23:30:00Z"), // crosses into 08-21 in Rome
            answerAt("2026-08-21T10:00:00Z")
        )
        assertEquals(
            aggregateAnswersByDate(answers, zone).keys,
            aggregateAnswerTimesByDate(answers, zone).keys
        )
        assertEquals(
            aggregateAnswersByDate(answers, zone).values.sum(),
            aggregateAnswerTimesByDate(answers, zone).values.sumOf { it.size }
        )
    }

    @Test
    fun `keeps duplicate times as separate units`() {
        val answers = listOf(
            answerAt("2026-08-21T10:00:00Z"),
            answerAt("2026-08-21T10:00:00Z")
        )
        assertEquals(
            mapOf("2026-08-21" to listOf("12:00:00", "12:00:00")),
            aggregateAnswerTimesByDate(answers, zone)
        )
    }

    @Test
    fun `empty history aggregates to empty times map`() {
        assertEquals(emptyMap<String, List<String>>(), aggregateAnswerTimesByDate(emptyList(), zone))
    }
}
