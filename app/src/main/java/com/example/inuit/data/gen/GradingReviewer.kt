package com.example.inuit.data.gen

import com.example.inuit.data.DebugLog
import com.example.inuit.data.Question
import com.example.inuit.data.llm.LlmClient
import com.example.inuit.data.llm.LlmConfig
import com.example.inuit.data.llm.LlmMessage
import org.json.JSONObject

/**
 * LLM adjudication of disputed grades ("this was scored in error").
 *
 * Given the question, the app's expected answer and what the user actually
 * answered, the net's LLM decides:
 *  - whether the user's answer should count as correct (the record and all
 *    stats are then flipped), or
 *  - whether the local grading was right (upheld) — in which case the model
 *    distills ONE generalized rule about why answers like this get rejected,
 *    so future question generation (see Prompts GRADING LESSONS) avoids the
 *    ambiguity that caused the dispute. No verbatim examples are kept —
 *    only the rule, keeping context bounded.
 */
object GradingReviewer {

    data class Verdict(
        /** True when the user's answer should be counted as correct. */
        val userWasCorrect: Boolean,
        /** One generalized sentence when the dispute revealed a grading flaw; blank otherwise. */
        val lesson: String
    )

    suspend fun review(
        llm: LlmClient,
        cfg: LlmConfig,
        question: Question,
        userAnswer: String,
        gradedCorrect: Boolean
    ): Verdict {
        val prompt = buildPrompt(question, userAnswer, gradedCorrect)
        val msg = llm.chat(
            cfg,
            listOf(LlmMessage.user(prompt)),
            temperature = 0.1f,
            maxTokens = 600
        )
        val content = msg.content ?: throw IllegalStateException("review returned no content")
        return parse(content)
    }

    private fun buildPrompt(q: Question, userAnswer: String, gradedCorrect: Boolean): String = buildString {
        append("""You are the adjudicator for a quiz app's automatic grading. A user disputed how their answer was scored. Decide whether the user's answer is actually correct.

QUESTION (${q.type.displayName}): ${q.prompt}
""")
        if (q.type == com.example.inuit.data.QuestionType.MULTIPLE_CHOICE && q.choices.isNotEmpty()) {
            append("CHOICES: ")
            q.choices.forEachIndexed { i, c -> append(('A' + i)).append(") ").append(c).append("  ") }
            append("(the app's expected choice: ").append(q.correctAnswerDisplay).append(")\n")
        } else {
            append("EXPECTED ANSWER: ${q.correctAnswerDisplay}\n")
        }
        append("USER'S ANSWER: $userAnswer\n")
        append("GRADED AS: ${if (gradedCorrect) "correct" else "incorrect"} (the user disputes this)\n\n")
        append("""Rules of judgement:
- The user's answer is CORRECT if it is a factually/equivalent valid answer: synonym, alternate name, plural/singular, unit-equivalent numeric value, or a rounding consistent with the question's tolerance.
- The user's answer is INCORRECT if it is factually wrong or a different entity than asked for. The original grading stands.

Reply with ONLY a JSON object:
{"user_was_correct": true|false, "lesson": "..."}

The "lesson" field: if the dispute revealed a flaw in how the question or its accepted answers were designed (ambiguity, missing synonym, misleading options, bad tolerance), write ONE generalized sentence describing the RULE to follow so this class of problem is avoided in future questions (e.g. "Accept both common name and scientific name in fill-in-the-blank answers"). Never mention the specific question, entities or the user. If the grading was simply correct and the question fine, use an empty string.""")
    }

    /** Tolerant parse: strips code fences, finds the JSON object. */
    fun parse(content: String): Verdict {
        val cleaned = content.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val o = JSONObject(cleaned.substring(start, end + 1))
                return Verdict(
                    userWasCorrect = o.optBoolean("user_was_correct", false),
                    lesson = o.optString("lesson", "").trim()
                )
            } catch (e: Exception) {
                DebugLog.w("GradingReviewer", "JSON parse failed, falling back: ${e.message}")
            }
        }
        // Fallback: look for a bare true/false verdict.
        val lower = cleaned.lowercase()
        val correct = lower.contains("\"user_was_correct\": true") || lower.contains("user_was_correct: true")
        return Verdict(correct, "")
    }
}
