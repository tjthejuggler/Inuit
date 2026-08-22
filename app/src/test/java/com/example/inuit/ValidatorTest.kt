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
}
