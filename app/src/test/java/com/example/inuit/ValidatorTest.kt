package com.example.inuit

import com.example.inuit.data.AppSettings
import com.example.inuit.data.QuestionType
import com.example.inuit.data.gen.Prompts
import com.example.inuit.data.gen.Validator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorTest {

    private val settings = AppSettings(minConfidence = 0.8f)
    private val jugglingNet = com.example.inuit.data.Net(
        id = "net-juggle", name = "Juggling", description = "all things juggling"
    )

    private fun questionJson(
        type: String = "true_false",
        prompt: String = "Is water wet?",
        answer: Any = true,
        domains: String = """["Science > Physics"]""",
        confidence: Double = 0.95,
        extra: String = ""
    ): String = """
        {"type":"$type","prompt":"$prompt","answer":$answer,
         "domains":$domains,"difficulty":3,"confidence":$confidence$extra}
    """.trimIndent()

    @Test
    fun parsesValidBatch() {
        val raw = """
            {"questions":[
              ${questionJson()},
              ${questionJson(type = "multiple_choice", prompt = "Which is a prime number?", answer = 2,
                  domains = """["Mathematics > Number Theory"]""",
                  extra = ""","choices":["4","2","9","15"]""")},
              ${questionJson(type = "numeric", prompt = "How many legs does a spider have?", answer = 8,
                  domains = """["Nature > Animals"]""",
                  extra = ""","tolerance":0""")},
              ${questionJson(type = "fill_blank", prompt = "The Great Wall is in which country?", answer = "China",
                  domains = """["History","Geography > Asia"]""")}
            ],"new_frontiers":["Art > Architecture"]}
        """.trimIndent()
        val result = Validator.parseAndValidate(raw, emptyList(), settings, emptyMap())
        assertEquals(4, result.questions.size)
        assertEquals(listOf("Art > Architecture"), result.newFrontiers)
        val fill = result.questions.last()
        assertEquals(QuestionType.FILL_BLANK, fill.type)
        assertTrue(fill.acceptedAnswers.contains("China"))
    }

    @Test
    fun dropsLowConfidence() {
        val raw = """{"questions":[${questionJson(confidence = 0.5)}]}"""
        val result = Validator.parseAndValidate(raw, emptyList(), settings, emptyMap())
        assertEquals(0, result.questions.size)
        assertEquals(1, result.dropped)
    }

    @Test
    fun dropsFillBlankThatLeaksAnswer() {
        val raw = """{"questions":[${questionJson(
            type = "fill_blank", prompt = "What is the capital of France?", answer = "Paris"
        )}]}"""
        // "Paris" does not appear in the prompt — should pass
        assertEquals(1, Validator.parseAndValidate(raw, emptyList(), settings, emptyMap()).questions.size)
        val leaky = """{"questions":[${questionJson(
            type = "fill_blank",
            prompt = "The Great Barrier Reef lies off the coast of which country, Australia?",
            answer = "Australia"
        )}]}"""
        assertEquals(0, Validator.parseAndValidate(leaky, emptyList(), settings, emptyMap()).questions.size)
    }

    @Test
    fun dropsNearDuplicates() {
        val existing = com.example.inuit.data.Question(
            id = "q1", type = QuestionType.TRUE_FALSE,
            prompt = "Is the Great Wall of China visible from low Earth orbit with the naked eye?"
        )
        val raw = """{"questions":[${questionJson(
            prompt = "Is the Great Wall of China visible from low orbit with the naked eye?"
        )}]}"""
        val result = Validator.parseAndValidate(raw, listOf(existing), settings, emptyMap())
        assertEquals(0, result.questions.size)
    }

    @Test
    fun linksParentHintToMarker() {
        val parent = com.example.inuit.data.Question(id = "root1", prompt = "parent")
        val raw = """{"questions":[${questionJson(
            prompt = "Is a kilometer longer than a mile?", answer = false,
            extra = ""","parent_hint":"U1""""
        )}]}"""
        val result = Validator.parseAndValidate(raw, emptyList(), settings, mapOf("U1" to parent))
        assertEquals(1, result.questions.size)
        assertEquals("root1", result.questions[0].parentId)
        assertEquals("root1", result.questions[0].rootId)
    }

    // ── true/false twin pairs (50/50 answer balance) ─────────────────────

    private fun pairJson(
        trueStmt: String = "Mount Everest is the highest mountain above sea level.",
        falseStmt: String = "K2 is the highest mountain above sea level.",
        domains: String = """["Geography > Mountains"]"""
    ): String = """
        {"type":"true_false","pair":{"true":"$trueStmt","false":"$falseStmt"},
         "domains":$domains,"difficulty":2,"confidence":0.95}
    """.trimIndent()

    @Test
    fun `pair true_false yields one question whose prompt and answer match the chosen twin`() {
        val raw = """{"questions":[${pairJson()}]}"""
        val result = Validator.parseAndValidate(raw, emptyList(), settings, emptyMap())
        assertEquals(1, result.questions.size)
        val q = result.questions[0]
        assertEquals(QuestionType.TRUE_FALSE, q.type)
        assertNotNull(q.answerBool)
        val expectedPrompt = if (q.answerBool!!) "Mount Everest is the highest mountain above sea level."
        else "K2 is the highest mountain above sea level."
        assertEquals(expectedPrompt, q.prompt)
    }

    @Test
    fun `identical pair twins are rejected`() {
        val raw = """{"questions":[${pairJson(
            trueStmt = "The Nile is the longest river in Africa.",
            falseStmt = "The Nile is the longest river in Africa."
        )}]}"""
        val result = Validator.parseAndValidate(raw, emptyList(), settings, emptyMap())
        assertEquals(0, result.questions.size)
        assertTrue(result.dropReasons.any { it.contains("identical") })
    }

    @Test
    fun `balancer keeps long-run ratio near half and corrects existing drift`() {
        // Store already heavily skewed toward true answers (10 true, 0 false):
        // the balancer must push back toward false.
        val skewed = List(10) {
            com.example.inuit.data.Question(
                type = QuestionType.TRUE_FALSE, answerBool = true, prompt = "existing true #$it"
            )
        }
        val balancer = Validator.TfBalancer(skewed)
        var trues = 0
        val n = 2000
        repeat(n) { if (balancer.pick()) trues++ }
        // The balancer must erase the 10-true head start: after n picks the
        // TOTAL counts (existing + picked) must be near equal.
        val totalTrues = trues + 10
        val totalFalses = n - trues
        assertTrue("trues=$trues", kotlin.math.abs(totalTrues - totalFalses) <= 80)

        // From a balanced start the coin is fair.
        val fair = Validator.TfBalancer(emptyList())
        var t = 0
        repeat(n) { if (fair.pick()) t++ }
        assertTrue("t=$t", t in (n * 0.44).toInt()..(n * 0.56).toInt())
    }

    @Test
    fun extractJsonHandlesFences() {
        val fenced = "Sure! Here you go:\n```json\n{\"questions\":[]}\n```\nDone."
        assertEquals("{\"questions\":[]}", Prompts.extractJson(fenced))
        val embedded = "prefix text {\"a\":{\"b\":1}} suffix"
        assertEquals("{\"a\":{\"b\":1}}", Prompts.extractJson(embedded))
    }

    @Test
    fun numericAutoTolerance() {
        val raw = """{"questions":[${questionJson(
            type = "numeric", prompt = "What is pi to two decimals?", answer = 3.14
        )}]}"""
        val result = Validator.parseAndValidate(raw, emptyList(), settings, emptyMap())
        assertEquals(1, result.questions.size)
        assertNotNull(result.questions[0].tolerance)
        assertTrue(result.questions[0].tolerance!! > 0)
    }

    // ── custom-net domain tagging (knowledge-map territories) ─────────────

    @Test
    fun `net questions get net-rooted subtopic paths`() {
        val raw = """{"questions":[
            ${questionJson(prompt = "In siteswap notation, is 5 a higher throw than 3?", answer = true,
                domains = """["Juggling > Siteswap"]""")},
            ${questionJson(prompt = "Was Enrico Rastelli a famous juggler of the 1920s?", answer = true,
                domains = """["Siteswap"]""")},
            ${questionJson(prompt = "Are beanbags the most common beginner prop?", answer = true,
                domains = """["Sports & Games > Juggling > Props"]""")},
            ${questionJson(prompt = "Is the cascade a basic juggling pattern?", answer = true,
                domains = """["Patterns"]""")}
        ]}""".trimIndent()
        val result = Validator.parseAndValidate(raw, emptyList(), settings, emptyMap(), jugglingNet)
        assertEquals(4, result.questions.size)
        // already well-formed → unchanged
        assertEquals(listOf("Juggling > Siteswap"), result.questions[0].domains)
        // bare subtopic → prefixed with the net name
        assertEquals(listOf("Juggling > Siteswap"), result.questions[1].domains)
        // broad all-knowledge realm prepended → stripped back to the net root
        assertEquals(listOf("Juggling > Props"), result.questions[2].domains)
        // bare subtopic → prefixed
        assertEquals(listOf("Juggling > Patterns"), result.questions[3].domains)
    }

    @Test
    fun `flat net-name domain tag is rejected but mixed tags survive`() {
        val raw = """{"questions":[
            ${questionJson(prompt = "Is juggling older than recorded history?", answer = false,
                domains = """["Juggling"]""")},
            ${questionJson(prompt = "Can most people learn three-ball juggling?", answer = true,
                domains = """["Juggling","Juggling > Basics"]""")}
        ]}""".trimIndent()
        val result = Validator.parseAndValidate(raw, emptyList(), settings, emptyMap(), jugglingNet)
        assertEquals(1, result.questions.size)
        assertEquals(listOf("Juggling > Basics"), result.questions[0].domains)
        assertEquals(1, result.dropped)
        assertTrue(result.dropReasons.any { it.contains("flat net name") })
    }

    @Test
    fun `all net keeps legacy flat tagging behavior`() {
        val raw = """{"questions":[${questionJson(domains = """["History"]""")}]}"""
        val result = Validator.parseAndValidate(
            raw, emptyList(), settings, emptyMap(), com.example.inuit.data.Net.ALL
        )
        assertEquals(1, result.questions.size)
        assertEquals(listOf("History"), result.questions[0].domains)
    }
}
