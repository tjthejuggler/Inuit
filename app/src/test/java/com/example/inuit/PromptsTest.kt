package com.example.inuit

import com.example.inuit.data.Net
import com.example.inuit.data.gen.ContextBuilder.Context
import com.example.inuit.data.gen.NetAccents
import com.example.inuit.data.gen.Prompts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive-challenge prompt contract: the generator must keep the user
 * at the BOUNDARY of their knowledge — sustained correct answers escalate
 * difficulty, misses ease back — for every net, the All net included, and
 * never framed for one specific domain.
 */
class PromptsTest {

    private fun net() = Net(name = "Juggling", description = "All things juggling")

    private fun context(challenge: List<String>) = Context(
        recentLines = emptyList(),
        unknownGroups = emptyList(),
        knownLines = emptyList(),
        domainDigest = emptyList(),
        noviceDomains = emptyList(),
        challengeDomains = challenge,
        summaries = emptyList(),
        distantFrontiers = emptyList(),
        revisitFrontiers = emptyList(),
        totalsLine = "answers=0 correct=0"
    )

    @Test
    fun `system prompt demands adaptive challenge for every net`() {
        for (prompt in listOf(
            Prompts.systemPrompt(0),
            Prompts.systemPrompt(0, net())
        )) {
            assertTrue(prompt.contains("ADAPTIVE CHALLENGE"))
            assertTrue(prompt.contains("BOUNDARY"))
            assertTrue(prompt.contains("difficulty dial"))
            assertTrue(prompt.contains("until misses appear"))
        }
        // the All-net prompt states the principle generically — it must not
        // be framed around one specific domain
        assertFalse(Prompts.systemPrompt(0).contains("juggling", ignoreCase = true))
    }

    @Test
    fun `user request renders challenge escalation and boundary directive`() {
        val out = Prompts.userRequest(
            context(challenge = listOf("Notation — 9/10 (90%)")),
            batchSize = 10
        )
        assertTrue(out.contains("CHALLENGE ESCALATION"))
        assertTrue(out.contains("Notation — 9/10 (90%)"))
        assertTrue(out.contains("must be HARDER"))
        assertTrue(out.contains("BOUNDARY"))
    }

    @Test
    fun `user request omits escalation section when nothing is mastered`() {
        val out = Prompts.userRequest(context(challenge = emptyList()), batchSize = 10)
        assertFalse(out.contains("CHALLENGE ESCALATION"))
        // the boundary directive still applies — it is unconditional
        assertTrue(out.contains("BOUNDARY"))
    }

    @Test
    fun `user request renders per-source targets from the net mix`() {
        val net = Net(
            name = "Milan", description = "All things Milan",
            sourceWeights = mapOf(
                com.example.inuit.data.SourceMix.LOCATION to 30,
                com.example.inuit.data.SourceMix.DATE to 10
            )
        )
        val accents = NetAccents(
            locationLine = "the user is currently near Milan, Italy",
            dateLines = listOf("today is Saturday, 1 January 2028")
        )
        val out = Prompts.userRequest(context(challenge = emptyList()), batchSize = 20, net = net, accents = accents)
        assertTrue(out.contains("QUESTION SOURCE MIX"))
        // 30% of 20 = 6 location, 10% of 20 = 2 date
        assertTrue(out.contains("6 tied to the LOCATION below"))
        assertTrue(out.contains("2 tied to today's DATE"))
        assertTrue(out.contains("12 are core questions"))
        assertTrue(out.contains("near Milan, Italy"))
    }

    @Test
    fun `user request omits source mix when no accent has weight or data`() {
        val net = Net(name = "Plain", description = "Nothing extra")
        val out = Prompts.userRequest(context(challenge = emptyList()), batchSize = 10, net = net)
        assertFalse(out.contains("QUESTION SOURCE MIX"))
    }

    @Test
    fun `accent without data this batch folds its share into core`() {
        val net = Net(
            name = "Milan", description = "All things Milan",
            sourceWeights = mapOf(com.example.inuit.data.SourceMix.LOCATION to 30)
        )
        // location weight 30% but NO location line (permission off / stale fix)
        val out = Prompts.userRequest(
            context(challenge = emptyList()), batchSize = 10, net = net, accents = NetAccents()
        )
        assertFalse(out.contains("QUESTION SOURCE MIX"))
    }
}
