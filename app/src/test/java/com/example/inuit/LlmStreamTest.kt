package com.example.inuit

import com.example.inuit.data.gen.QuestionGenerator
import com.example.inuit.data.llm.ChatStreamAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the streaming chat client introduced after the
 * "generating forever" incident: a reasoning model (GLM-5) regularly thinks
 * for 5+ minutes before the FIRST byte on non-streaming endpoints, which the
 * old client misread as a network timeout and retried infinitely. The SSE
 * accumulator below is what makes streamed deltas usable, and the backoff
 * policy bounds the auto-retry loop.
 */
class LlmStreamTest {

    private fun acc(vararg lines: String) = ChatStreamAccumulator().apply {
        lines.forEach { onLine(it) }
    }

    @Test
    fun `assembles content deltas from SSE chunks`() {
        val a = acc(
            """data: {"choices":[{"delta":{"role":"assistant","content":"Hel"}}]}""",
            """data: {"choices":[{"delta":{"content":"lo "}}]}""",
            ": keep-alive comment — must be ignored",
            "",
            """data: {"choices":[{"delta":{"content":"world"},"finish_reason":null}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "data: [DONE]"
        )
        assertTrue(a.sawChunk)
        assertEquals("stop", a.finishReason)
        val msg = a.build()
        assertEquals("assistant", msg.role)
        assertEquals("Hello world", msg.content)
        assertTrue(msg.toolCalls.isEmpty())
    }

    @Test
    fun `merges fragmented tool calls across chunks`() {
        val a = acc(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"web_search_prime","arguments":""}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"que"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ry\":\"ai\"}"}}]}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "data: [DONE]"
        )
        val msg = a.build()
        assertEquals("tool_calls", a.finishReason)
        assertNull(msg.content)
        assertEquals(1, msg.toolCalls.size)
        val call = msg.toolCalls[0]
        assertEquals("call_1", call.id)
        assertEquals("web_search_prime", call.name)
        assertEquals("""{"query":"ai"}""", call.argumentsJson)
    }

    @Test
    fun `tracks reasoning content and length truncated finish`() {
        val a = acc(
            """data: {"choices":[{"delta":{"reasoning_content":"thinking hard "}}]}""",
            """data: {"choices":[{"delta":{"reasoning_content":"about agents"}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"length"}]}""",
            "data: [DONE]"
        )
        assertEquals("length", a.finishReason)
        assertEquals("thinking hard about agents".length, a.reasoningLength)
        assertNull(a.build().content) // empty-content retry path triggers on this
    }

    @Test
    fun `ignores non-data lines so plain JSON bodies stay undetected`() {
        val a = acc(
            "{",
            """  "choices": []""",
            "}"
        )
        assertFalse(a.sawChunk) // caller falls back to whole-body JSON parsing
        assertNull(a.finishReason)
    }

    @Test
    fun `captures streamed error objects`() {
        val a = acc(
            """data: {"error":{"message":"rate limited","type":"requests"}}""",
            "data: [DONE]"
        )
        assertEquals("rate limited", a.streamError)
    }

    @Test
    fun `reads usage from the final chunk`() {
        val a = acc(
            """data: {"choices":[{"delta":{"content":"hi"}}]}""",
            """data: {"choices":[],"usage":{"completion_tokens":42,"prompt_tokens":100,"completion_tokens_details":{"reasoning_tokens":7}}}""",
            "data: [DONE]"
        )
        assertEquals("completion=42 prompt=100 reasoning=7", a.usageSummary)
    }

    @Test
    fun `auto retry backoff escalates and caps`() {
        // 1 min → 5 min → 25 min would exceed the 15 min cap → stays there.
        assertEquals(300_000L, QuestionGenerator.nextAutoRetryDelay(60_000L))
        assertEquals(900_000L, QuestionGenerator.nextAutoRetryDelay(300_000L))
        assertEquals(900_000L, QuestionGenerator.nextAutoRetryDelay(900_000L))
    }
}
