package com.example.inuit

import com.example.inuit.data.AnswerRecord
import com.example.inuit.data.DomainStat
import com.example.inuit.data.KnowledgeSummary
import com.example.inuit.data.Question
import com.example.inuit.data.gen.CrossNetAccents
import com.example.inuit.data.gen.DateAccents
import com.example.inuit.data.gen.NetAccents
import com.example.inuit.data.gen.accentQuestionCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Pure tests for the occasional-accent context (no Android dependencies). */
class AccentsTest {

    // ── dosage cap ────────────────────────────────────────────────────────

    @Test
    fun `accent question cap stays a sprinkle across batch sizes`() {
        assertEquals(1, accentQuestionCap(1))
        assertEquals(1, accentQuestionCap(6))
        assertEquals(2, accentQuestionCap(12))
        assertEquals(3, accentQuestionCap(18))
        assertEquals(3, accentQuestionCap(40)) // hard ceiling
    }

    @Test
    fun `empty accents are empty`() {
        assertTrue(NetAccents().isEmpty)
        assertFalse(NetAccents(locationLine = "near Rome").isEmpty)
        assertFalse(NetAccents(dateLines = listOf("today")).isEmpty)
        assertFalse(NetAccents(crossNetLines = listOf("- NET \"X\"")).isEmpty)
    }

    // ── date accent ───────────────────────────────────────────────────────

    @Test
    fun `date lines describe today with weekday day-of-year and anniversaries`() {
        val lines = DateAccents.lines(LocalDate.of(2026, 8, 23))
        assertEquals(3, lines.size) // no latitude → no season line
        val today = lines[0]
        assertTrue(today.contains("23 August 2026"))
        assertTrue(today.contains("day 235 of the year")) // 2026 is not a leap year
        assertTrue(today.contains("Sunday"))
        assertTrue(lines[1].contains("on-this-day"))
        assertTrue(lines[1].contains("August 23"))
        val years = lines[2]
        assertTrue(years.contains("1926")) // -100
        assertTrue(years.contains("1876")) // -150
        assertTrue(years.contains("1826")) // -200
        assertTrue(years.contains("1776")) // -250
    }

    @Test
    fun `date lines include hemisphere-correct season when latitude is known`() {
        val north = DateAccents.lines(LocalDate.of(2026, 8, 23), latitude = 45.5)
        assertTrue(north.any { it.contains("summer (northern hemisphere)") })

        val south = DateAccents.lines(LocalDate.of(2026, 8, 23), latitude = -33.9)
        assertTrue(south.any { it.contains("winter (southern hemisphere)") })

        // January flips too
        val southJan = DateAccents.lines(LocalDate.of(2026, 1, 15), latitude = -33.9)
        assertTrue(southJan.any { it.contains("summer (southern hemisphere)") })
    }

    @Test
    fun `season helper flips between hemispheres`() {
        assertEquals("summer", DateAccents.seasonOf(7, northern = true))
        assertEquals("winter", DateAccents.seasonOf(7, northern = false))
        assertEquals("winter", DateAccents.seasonOf(12, northern = true))
        assertEquals("spring", DateAccents.seasonOf(4, northern = true))
        assertEquals("autumn", DateAccents.seasonOf(4, northern = false))
    }

    // ── cross-net accent ──────────────────────────────────────────────────

    private fun summary(domain: String, text: String) =
        KnowledgeSummary(domain, text, createdAt = System.currentTimeMillis(), coveredAnswers = 10)

    @Test
    fun `cross-net lines summarize a source net compactly`() {
        val lines = CrossNetAccents.lines(
            listOf(
                CrossNetAccents.Source(
                    name = "History",
                    description = "Everything human past",
                    summaries = listOf(summary("Antiquity", "Solid on Rome; gaps in Persia.")),
                    stats = listOf(
                        DomainStat("History > Rome", attempts = 9, correct = 2),
                        DomainStat("History > Persia", attempts = 4, correct = 3)
                    ),
                    missedPrompts = listOf("Who founded the Achaemenid Empire?")
                )
            )
        )
        assertEquals(1, lines.size)
        val line = lines[0]
        assertTrue(line.startsWith("- NET \"History\""))
        assertTrue(line.contains("Everything human past"))
        assertTrue(line.contains("Antiquity: Solid on Rome; gaps in Persia."))
        assertTrue(line.contains("History > Rome (2/9)")) // weakest first
        assertTrue(line.contains("recently missed"))
        assertTrue(line.contains("Achaemenid"))
    }

    @Test
    fun `source nets without any data are dropped`() {
        val lines = CrossNetAccents.lines(
            listOf(
                CrossNetAccents.Source("Empty", "", emptyList(), emptyList(), emptyList()),
                CrossNetAccents.Source("Full", "", listOf(summary("D", "x")), emptyList(), emptyList())
            )
        )
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("Full"))
    }

    @Test
    fun `recent missed prompts return newest wrong questions without answers`() {
        val q1 = Question(id = "q1", prompt = "Capital of Assyria?")
        val q2 = Question(id = "q2", prompt = "Capital of Babylonia?")
        val q3 = Question(id = "q3", prompt = "Capital of Sumer?")
        val answers = listOf(
            AnswerRecord(id = "a1", questionId = "q1", correct = false, userAnswer = "Nope"),
            AnswerRecord(id = "a2", questionId = "q2", correct = true, userAnswer = "Babylon"),
            AnswerRecord(id = "a3", questionId = "q3", correct = false, userAnswer = "Uh")
        )
        val prompts = CrossNetAccents.recentMissedPrompts(listOf(q1, q2, q3), answers, limit = 2)
        // newest wrong first, correct answers skipped
        assertEquals(listOf("Capital of Sumer?", "Capital of Assyria?"), prompts)
    }

    @Test
    fun `missed prompts skip questions with no answer record`() {
        val q1 = Question(id = "q1", prompt = "Never asked?")
        val prompts = CrossNetAccents.recentMissedPrompts(listOf(q1), emptyList(), limit = 5)
        assertTrue(prompts.isEmpty())
        assertNull(prompts.firstOrNull())
    }
}
