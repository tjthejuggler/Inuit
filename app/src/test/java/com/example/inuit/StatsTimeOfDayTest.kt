package com.example.inuit

import com.example.inuit.data.AnswerRecord
import com.example.inuit.data.StatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Time-of-day aggregation: answers bucket into 24 hourly points and 8
 * three-hour bands (system zone — the same zone the calculator uses), the
 * peak hour is the busiest, and sharpest/weakest band highlights only appear
 * once a band has enough attempts to be comparable.
 */
class StatsTimeOfDayTest {

    private val zone = ZoneId.systemDefault()

    /** An answer at a fixed LOCAL time today (deterministic hour bucket). */
    private fun answerAt(hour: Int, minute: Int, correct: Boolean): AnswerRecord {
        val ts = LocalDate.now(zone).atTime(LocalTime.of(hour, minute)).atZone(zone)
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
    fun `buckets volume and accuracy per hour`() {
        val snap = compute(
            listOf(
                answerAt(9, 0, correct = true),
                answerAt(9, 30, correct = false),
                answerAt(22, 0, correct = true)
            )
        )
        assertEquals(24, snap.byHour.size)
        assertEquals(2, snap.byHour[9].attempts)
        assertEquals(1, snap.byHour[9].correct)
        assertEquals(0.5f, snap.byHour[9].accuracy)
        assertEquals(1, snap.byHour[22].attempts)
        assertEquals(0, snap.byHour[3].attempts)
    }

    @Test
    fun `bands aggregate three hours each`() {
        val snap = compute(
            listOf(
                answerAt(9, 0, correct = true),
                answerAt(11, 59, correct = true),
                answerAt(12, 0, correct = false) // next band (12–15)
            )
        )
        assertEquals(8, snap.byBand.size)
        val morning = snap.byBand.first { it.startHour == 9 }
        assertEquals(2, morning.attempts)
        assertEquals(1f, morning.accuracy)
        val noon = snap.byBand.first { it.startHour == 12 }
        assertEquals(1, noon.attempts)
        assertEquals(0f, noon.accuracy)
    }

    @Test
    fun `peak hour is the busiest`() {
        val snap = compute(
            listOf(
                answerAt(14, 0, correct = true),
                answerAt(14, 10, correct = true),
                answerAt(14, 20, correct = false),
                answerAt(20, 0, correct = true)
            )
        )
        assertEquals(14, snap.peakHour?.hour)
        assertEquals(3, snap.peakHour?.attempts)
    }

    @Test
    fun `sharpest and weakest bands need minimum attempts`() {
        // 09–12: 5/5 correct (eligible). 21–24: 0/5 (eligible). 15–18: 1/1 (too few).
        val answers = buildList {
            repeat(5) { add(answerAt(9 + it / 2, 0, correct = true)) }
            repeat(5) { add(answerAt(21 + it / 3, 0, correct = false)) }
            add(answerAt(15, 0, correct = true))
        }
        val snap = compute(answers)
        assertEquals(9, snap.sharpestBand?.startHour)
        assertEquals(1f, snap.sharpestBand?.accuracy)
        assertEquals(21, snap.weakestBand?.startHour)
        assertEquals(0f, snap.weakestBand?.accuracy)
    }

    @Test
    fun `no answers yield no highlights`() {
        val snap = compute(emptyList())
        assertEquals(24, snap.byHour.size)
        assertEquals(8, snap.byBand.size)
        assertNull(snap.peakHour)
        assertNull(snap.sharpestBand)
        assertNull(snap.weakestBand)
    }
}
