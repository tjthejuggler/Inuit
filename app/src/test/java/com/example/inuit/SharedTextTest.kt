package com.example.inuit

import com.example.inuit.data.Net
import com.example.inuit.data.SharedText
import com.example.inuit.data.SharedTextKind
import com.example.inuit.data.SharedTextStore
import com.example.inuit.data.SourceMix
import com.example.inuit.data.gen.NetAccents
import com.example.inuit.data.gen.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTextTest {

    private fun context() = com.example.inuit.data.gen.ContextBuilder.Context(
        recentLines = emptyList(),
        unknownGroups = emptyList(),
        knownLines = emptyList(),
        domainDigest = emptyList(),
        noviceDomains = emptyList(),
        challengeDomains = emptyList(),
        summaries = emptyList(),
        distantFrontiers = emptyList(),
        revisitFrontiers = emptyList(),
        totalsLine = "answers=0 correct=0"
    )

    // ── classifier ───────────────────────────────────────────────────────

    @Test
    fun `full factual text is classified as facts`() {
        val facts = "The Eiffel Tower is 330 metres tall. It was completed in 1889 " +
            "for the World's Fair in Paris. Gustave Eiffel's company designed it."
        assertEquals(SharedTextKind.FACTS, SharedTextStore.kindOf(facts))
    }

    @Test
    fun `short topics are classified as topic`() {
        assertEquals(SharedTextKind.TOPIC, SharedTextStore.kindOf("juggling"))
        assertEquals(SharedTextKind.TOPIC, SharedTextStore.kindOf("deep sea creatures"))
        assertEquals(SharedTextKind.TOPIC, SharedTextStore.kindOf("history of coffee"))
    }

    @Test
    fun `a single sentence is a topic not facts`() {
        assertEquals(
            SharedTextKind.TOPIC,
            SharedTextStore.kindOf("The speed of light is about 300000 km per second.")
        )
    }

    // ── renderer ─────────────────────────────────────────────────────────

    @Test
    fun `lines are newest first classified and capped`() {
        val snippets = listOf(
            SharedText(id = "a", text = "juggling", sharedAt = 1L),
            SharedText(id = "b", text = "The Eiffel Tower is 330 metres tall. It was finished in 1889 in Paris for the World's Fair that year.", sharedAt = 3L),
            SharedText(id = "c", text = "deep sea", sharedAt = 2L)
        )
        val lines = SharedTextStore.lines(snippets)
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("- SHARED (facts):"))
        assertTrue(lines[0].contains("Eiffel Tower"))
        assertTrue(lines[1].startsWith("- SHARED (topic):"))
        assertTrue(lines[2].startsWith("- SHARED (topic):"))
        assertEquals(2, SharedTextStore.lines(snippets, max = 2).size)
    }

    @Test
    fun `long snippets are clipped`() {
        val long = SharedText(id = "x", text = "word ".repeat(500), sharedAt = 1L)
        val line = SharedTextStore.lines(listOf(long)).single()
        assertTrue(line.length < SharedTextStore.CHARS_PER_LINE + 60)
        assertTrue(line.endsWith("…\""))
    }

    // ── serialization ────────────────────────────────────────────────────

    @Test
    fun `round trip through serialize and parse`() {
        val snippets = listOf(
            SharedText(id = "1", text = "topic one", sharedAt = 10L),
            SharedText(id = "2", text = "Some longer factual statement. With a second sentence and enough words to look like facts.", sharedAt = 20L)
        )
        val parsed = SharedTextStore.parse(SharedTextStore.serialize(snippets))
        assertEquals(snippets, parsed)
    }

    @Test
    fun `garbage parses to empty`() {
        assertTrue(SharedTextStore.parse("not json").isEmpty())
    }

    // ── source mix ───────────────────────────────────────────────────────

    @Test
    fun `shared is a built-in accent key`() {
        assertTrue(SourceMix.SHARED in SourceMix.ACCENTS)
    }

    @Test
    fun `legacy mix gives shared toggled nets a sprinkle`() {
        val mix = SourceMix.legacy(false, false, false, false, false, shared = true)
        assertEquals(SourceMix.LEGACY_ACCENT_PERCENT, mix[SourceMix.SHARED])
        assertEquals(100 - SourceMix.LEGACY_ACCENT_PERCENT, mix[SourceMix.CORE])
    }

    @Test
    fun `net mix reads the shared weight`() {
        val net = Net(
            name = "P",
            sharedTextEnabled = true,
            sourceWeights = mapOf(SourceMix.SHARED to 25)
        )
        assertEquals(25, net.mix()[SourceMix.SHARED])
        assertEquals(75, net.mix()[SourceMix.CORE])
        assertTrue(net.sharedTextEnabled)
    }

    // ── prompt ───────────────────────────────────────────────────────────

    @Test
    fun `shared texts reach the prompt when weighted`() {
        val net = Net(
            name = "P",
            sourceWeights = mapOf(SourceMix.SHARED to 20)
        )
        val out = Prompts.userRequest(
            ctx = context(),
            batchSize = 10,
            net = net,
            accents = NetAccents(
                sharedLines = listOf("- SHARED (facts): \"The Eiffel Tower is 330 metres tall. …\"")
            )
        )
        assertTrue("SHARED TEXTS" in out)
        assertTrue("INSPIRATION" in out)
    }

    @Test
    fun `shared texts are omitted when the pool is empty or the weight is zero`() {
        val weighted = Net(name = "P", sourceWeights = mapOf(SourceMix.SHARED to 20))
        val emptyPool = Prompts.userRequest(
            ctx = context(),
            batchSize = 10,
            net = weighted,
            accents = NetAccents()
        )
        assertFalse("SHARED TEXTS" in emptyPool)

        val unweighted = Net(name = "P", sourceWeights = emptyMap())
        val fullPool = Prompts.userRequest(
            ctx = context(),
            batchSize = 10,
            net = unweighted,
            accents = NetAccents(sharedLines = listOf("- SHARED (topic): \"juggling\""))
        )
        assertFalse("SHARED TEXTS" in fullPool)
    }
}
