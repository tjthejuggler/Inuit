package com.example.inuit.data.gen

/**
 * Conservative classifier for Gemini "Export to Docs" documents.
 *
 * A Gemini chat export lands in Drive root as a normal Google Doc with a
 * handful of tell-tale traits:
 *  - The title is literally "Conversation with Gemini <date>" (most common
 *    today), or the prompt that started the conversation (legacy).
 *  - The body starts with the user's prompt, then "**Gemini**" (or "Gemini:")
 *    followed by the answer; repeated turns repeat the pattern, and footers
 *    like "Posted by" / timestamp lines show up in some locales.
 *
 * Detection must NEVER be greedy: a false positive deletes an ordinary
 * personal document. Every candidate is scored; only confident matches are
 * treated as Gemini exports. Everything else in root is left untouched
 * (moved to the archive folder by the sync worker).
 */
object GeminiDocHeuristic {

    /** Strong title signals - match these and the doc is a Gemini export. */
    private val TITLE_PATTERNS = listOf(
        Regex("^Conversation with Gemini.*", RegexOption.IGNORE_CASE),
        Regex("^Bard conversation.*", RegexOption.IGNORE_CASE), // pre-Gemini naming
        Regex("^Chat with Gemini.*", RegexOption.IGNORE_CASE)
    )

    /** Body-level markers, weighted. */
    private val BODY_STRONG = listOf(
        Regex("^\\s*\\*?\\*?Gemini\\*?\\*?\\s*:?\\s*$", RegexOption.MULTILINE),
        Regex("^\\s*Bard\\s*:?\\s*$", RegexOption.MULTILINE),
        Regex("conversation with gemini", RegexOption.IGNORE_CASE)
    )

    private val BODY_WEAK = listOf(
        // MULTILINE so repeated turn headers match anywhere in the transcript.
        Regex("^\\s*You said:?", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        Regex("^\\s*You:\\s", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        Regex("^\\s*\\*?\\*?You\\*?\\*?\\s*:\\s*\\S", RegexOption.MULTILINE)
    )

    /** Result of classification. */
    enum class Verdict {
        /** Confident this is a Gemini chat export -> ingest, then delete. */
        GEMINI_EXPORT,
        /** Some signal, not enough to delete for -> move to the archive folder. */
        UNCERTAIN,
        /** No signal at all -> move to the archive folder. */
        OTHER
    }

    /**
     * Classifies one candidate document.
     *
     * @param title the document title from Drive metadata.
     * @param text the exported plain-text body (head is enough; the whole
     *   text is fine too).
     */
    fun classify(title: String, text: String): Verdict {
        val t = title.trim()
        if (TITLE_PATTERNS.any { it.containsMatchIn(t) }) return Verdict.GEMINI_EXPORT

        // Count OCCURRENCES, not just which patterns matched: a real
        // transcript repeats its speaker markers once per turn.
        val strong = BODY_STRONG.sumOf { it.findAll(text).count() }
        val weak = BODY_WEAK.sumOf { it.findAll(text).count() }
        // Require corroboration: one strong marker alone could be an essay
        // ABOUT Gemini; a strong marker + any turn marker, or two+ turn
        // markers (a real transcript), are treated as an export.
        return when {
            strong >= 2 -> Verdict.GEMINI_EXPORT
            strong == 1 && weak >= 1 -> Verdict.GEMINI_EXPORT
            else -> Verdict.OTHER
        }
    }

    /** True when [verdict] allows ingestion + deletion of the document. */
    fun isIngestible(verdict: Verdict): Boolean = verdict == Verdict.GEMINI_EXPORT
}
