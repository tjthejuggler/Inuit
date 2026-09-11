package com.example.inuit

import com.example.inuit.data.gen.GeminiDocHeuristic
import com.example.inuit.data.gen.GeminiDocHeuristic.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Gemini-export classifier must be CONSERVATIVE: confident matches get
 * ingested and deleted from the user's Drive; anything else must be treated
 * as an ordinary document (archived, never deleted).
 */
class GeminiDocHeuristicTest {

    // ── confident: title signal ─────────────────────────────────────────

    @Test
    fun `title 'Conversation with Gemini' is a confident match even with short body`() {
        val v = GeminiDocHeuristic.classify(
            title = "Conversation with Gemini - 2026-09-10",
            text = "why is the sky blue?"
        )
        assertEquals(Verdict.GEMINI_EXPORT, v)
    }

    @Test
    fun `title is matched case-insensitively`() {
        val v = GeminiDocHeuristic.classify(
            title = "conversation with gemini",
            text = ""
        )
        assertEquals(Verdict.GEMINI_EXPORT, v)
    }

    @Test
    fun `legacy Bard conversation titles match too`() {
        assertEquals(
            Verdict.GEMINI_EXPORT,
            GeminiDocHeuristic.classify("Bard conversation: the echo of light", "")
        )
    }

    // ── confident: corroborated body signals ────────────────────────────

    @Test
    fun `gemini speaker plus you-said marker is a confident match`() {
        val text = """
            How do black holes evaporate?

            **Gemini**

            Black holes evaporate through Hawking radiation…

            You said: and what is the timescale?

            **Gemini**

            For a solar-mass black hole it is ~10^67 years…
        """.trimIndent()
        assertEquals(
            Verdict.GEMINI_EXPORT,
            GeminiDocHeuristic.classify("Black holes", text)
        )
    }

    @Test
    fun `two gemini speaker markers alone are a confident match`() {
        val text = """
            prompt about entropy

            Gemini:

            Entropy is…

            Gemini:

            To clarify…
        """.trimIndent()
        assertEquals(Verdict.GEMINI_EXPORT, GeminiDocHeuristic.classify("Entropy", text))
    }

    // ── NOT confident: must never delete ────────────────────────────────

    @Test
    fun `an essay ABOUT gemini with a plain title is not an export`() {
        val text = """
            The history of conversational AI covers Bard and Gemini.
            You: a rhetorical device used by ancient orators.
            The assistant said something else entirely.
        """.trimIndent()
        assertEquals(Verdict.OTHER, GeminiDocHeuristic.classify("My research essay", text))
    }

    @Test
    fun `a recipe is not an export`() {
        assertEquals(
            Verdict.OTHER,
            GeminiDocHeuristic.classify("Grandma's lasagna", "Layer pasta, ragù, bechamel…")
        )
    }

    @Test
    fun `empty body with non-matching title is not an export`() {
        assertEquals(Verdict.OTHER, GeminiDocHeuristic.classify("Meeting notes", ""))
    }

    @Test
    fun `a single weak marker alone is not enough`() {
        assertEquals(
            Verdict.OTHER,
            GeminiDocHeuristic.classify("Chat transcript draft", "You said: hello\n\nNobody answered.")
        )
    }

    // ── gate helper ─────────────────────────────────────────────────────

    @Test
    fun `only the confident verdict is ingestible`() {
        assertTrue(GeminiDocHeuristic.isIngestible(Verdict.GEMINI_EXPORT))
        assertFalse(GeminiDocHeuristic.isIngestible(Verdict.OTHER))
        assertFalse(GeminiDocHeuristic.isIngestible(Verdict.UNCERTAIN))
    }
}
