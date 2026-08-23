package com.example.inuit

import com.example.inuit.data.Net
import com.example.inuit.data.gen.ContextBuilder.Context
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
}
