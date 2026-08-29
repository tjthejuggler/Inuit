package com.example.inuit

import com.example.inuit.data.AnswerRecord
import com.example.inuit.data.DomainStat
import com.example.inuit.data.KnowledgeSummary
import com.example.inuit.data.Question
import com.example.inuit.data.TailTextEntry
import com.example.inuit.data.gen.CrossNetAccents
import com.example.inuit.data.gen.DateAccents
import com.example.inuit.data.gen.NetAccents
import com.example.inuit.data.SourceMix
import com.example.inuit.data.gen.TailTextAccents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Pure tests for the occasional-accent context (no Android dependencies). */
class AccentsTest {

    // ── source mix normalization ──────────────────────────────────────────

    @Test
    fun `normalize completes the core remainder and sums to 100`() {
        val mix = SourceMix.normalize(mapOf(SourceMix.LOCATION to 20, SourceMix.DATE to 10))
        assertEquals(70, mix[SourceMix.CORE])
        assertEquals(20, mix[SourceMix.LOCATION])
        assertEquals(10, mix[SourceMix.DATE])
        assertEquals(0, mix[SourceMix.CROSS_NET])
        assertEquals(100, mix.values.sum())
    }

    @Test
    fun `normalize clamps accents combined to the core floor`() {
        val mix = SourceMix.normalize(
            mapOf(
                SourceMix.LOCATION to 60,
                SourceMix.DATE to 60,
                SourceMix.CROSS_NET to 60,
                SourceMix.TAIL_TEXT to 60
            )
        )
        assertTrue(mix[SourceMix.CORE]!! >= 100 - SourceMix.MAX_TOTAL_ACCENTS)
        assertEquals(100, mix.values.sum())
        assertTrue(mix.values.all { it >= 0 })
    }

    @Test
    fun `legacy toggles become small sprinkle shares`() {
        val mix = SourceMix.legacy(location = true, date = false, crossNet = true, tailText = true)
        assertEquals(SourceMix.LEGACY_ACCENT_PERCENT, mix[SourceMix.LOCATION])
        assertEquals(0, mix[SourceMix.DATE])
        assertEquals(SourceMix.LEGACY_ACCENT_PERCENT, mix[SourceMix.CROSS_NET])
        assertEquals(SourceMix.LEGACY_ACCENT_PERCENT, mix[SourceMix.TAIL_TEXT])
        assertEquals(100 - 3 * SourceMix.LEGACY_ACCENT_PERCENT, mix[SourceMix.CORE])
    }

    @Test
    fun `empty accents are empty`() {
        assertTrue(NetAccents().isEmpty)
        assertFalse(NetAccents(locationLine = "near Rome").isEmpty)
        assertFalse(NetAccents(dateLines = listOf("today")).isEmpty)
        assertFalse(NetAccents(crossNetLines = listOf("- NET \"X\"")).isEmpty)
        assertFalse(NetAccents(tailTextLines = listOf("- LOG \"Dreams\"")).isEmpty)
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

    // ── Tail life-log accent ──────────────────────────────────────────────

    private fun entry(habit: String, ts: String, text: String) =
        TailTextEntry(habitName = habit, timestamp = ts, text = text)

    @Test
    fun `tail text lines render one compact line per selected habit newest first`() {
        val lines = TailTextAccents.lines(
            listOf(
                entry("Dreams", "2026-08-24 07:30:00", "flying over the bay"),
                entry("Dreams", "2026-08-25 07:10:00", "late for a train"),
                entry("Reading", "2026-08-25 09:00:00", "finished Dune chapter 4")
            ),
            selectedHabits = listOf("Dreams", "Reading")
        )
        assertEquals(2, lines.size)
        val dreams = lines.first { it.contains("Dreams") }
        assertTrue(dreams.startsWith("- LOG \"Dreams\":"))
        // newest entry first within the line
        assertTrue(dreams.indexOf("late for a train") < dreams.indexOf("flying over the bay"))
        assertTrue(lines.any { it.startsWith("- LOG \"Reading\":") && it.contains("Dune") })
    }

    @Test
    fun `tail text lines drop habits the net did not select`() {
        val lines = TailTextAccents.lines(
            listOf(
                entry("Dreams", "2026-08-25 07:10:00", "flying"),
                entry("Secret", "2026-08-25 08:00:00", "private thought")
            ),
            selectedHabits = listOf("Dreams")
        )
        assertEquals(1, lines.size)
        assertFalse(lines[0].contains("Secret"))
    }

    @Test
    fun `tail text lines cap habits and entries per habit`() {
        val entries = (1..4).map { n ->
            entry("Habit$n", "2026-08-25 0$n:00:00", "note $n")
        } + (1..3).map { n ->
            entry("Habit1", "2026-08-2$n 0$n:00:00", "extra $n")
        }
        val lines = TailTextAccents.lines(entries, selectedHabits = listOf("Habit1", "Habit2", "Habit3", "Habit4"))
        assertEquals(TailTextAccents.MAX_HABITS, lines.size) // alphabetical: Habit1..Habit3
        val habit1 = lines.first { it.contains("Habit1") }
        // only ENTRIES_PER_HABIT quoted entry snippets appear on the line
        // (the habit name itself is also quoted, so match entry texts)
        val quotedCount = Regex("\"(note|extra) \\d+\"").findAll(habit1).count()
        assertEquals(TailTextAccents.ENTRIES_PER_HABIT, quotedCount)
    }

    @Test
    fun `tail text lines flatten newlines and clip long entries`() {
        val long = "x".repeat(TailTextAccents.ENTRY_CHARS + 50)
        val lines = TailTextAccents.lines(
            listOf(entry("Dreams", "2026-08-25 07:00:00", "line one\nline two\n$long")),
            selectedHabits = listOf("Dreams")
        )
        assertEquals(1, lines.size)
        assertFalse(lines[0].contains('\n')) // single flat line
        assertTrue(lines[0].endsWith("…\""))  // clipped, then closed by the quote
        assertTrue(lines[0].length < 300)    // stays compact for the prompt
    }

    @Test
    fun `tail text lines with no shared entries are empty`() {
        assertTrue(TailTextAccents.lines(emptyList(), selectedHabits = listOf("Dreams")).isEmpty())
        // entries exist but the net selected nothing
        assertTrue(
            TailTextAccents.lines(
                listOf(entry("Dreams", "2026-08-25 07:00:00", "flying")),
                selectedHabits = emptyList()
            ).isEmpty()
        )
    }
}
