package com.example.inuit.data.gdrive

import com.example.inuit.data.DebugLog
import com.example.inuit.data.gen.GeminiDocHeuristic
import com.example.inuit.data.gen.GeminiDocHeuristic.Verdict
import com.example.inuit.data.gen.Prompts
import com.example.inuit.data.llm.LlmClient
import com.example.inuit.data.llm.LlmConfig
import com.example.inuit.data.llm.LlmMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Read-and-clean worker for Gemini chat exports in Drive root.
 *
 * Per run:
 *  1. list DIRECT Google-Doc children of root (sub-folders are NEVER touched),
 *  2. skip documents already ingested (dedup guard against failed trashes),
 *  3. export as text, classify with [GeminiDocHeuristic],
 *  4. GEMINI_EXPORT → LLM distills key facts into the fact pool, THEN the
 *     document is trashed (deletion only after successful ingestion),
 *  5. anything else → moved to the `inuit_auto_move` folder, untouched.
 *
 * Every step fails soft: one bad document never aborts the run.
 */
class GDriveSync(
    private val client: GDriveClient,
    private val factStore: GDriveFactStore,
    private val llm: LlmClient
) {

    /**
     * One full pass over Drive root.
     * @param cfg LLM endpoint used for fact distillation.
     * @param disableThinking mirrors the app's thinking toggle.
     * @param maxDocs safety cap per run (the next run picks up the rest).
     */
    suspend fun runOnce(
        creds: GDriveCredentials,
        cfg: LlmConfig,
        disableThinking: Boolean = false,
        maxDocs: Int = MAX_DOCS_PER_RUN
    ): GDriveSyncResult = withContext(Dispatchers.IO) {
        if (!creds.configured) {
            return@withContext GDriveSyncResult(note = "credentials not configured")
        }
        var scanned = 0; var ingested = 0; var moved = 0; var failed = 0
        val problems = ArrayList<String>()

        try {
            val docs = client.listRootDocuments()
            scanned = docs.size
            DebugLog.i(TAG, "root scan: ${docs.size} document(s)")

            var budget = maxDocs
            for (doc in docs) {
                if (budget <= 0) break
                if (factStore.hasDoc(doc.id)) continue // already ingested before
                budget--
                try {
                    val text = client.exportAsText(doc.id)
                    val verdict = GeminiDocHeuristic.classify(doc.name, text)
                    DebugLog.i(
                        TAG,
                        "classify '${doc.name}' -> $verdict (title=${doc.name.take(80)}, " +
                            "body head: ${text.take(160).replace('\n', '|')})"
                    )
                    // Heuristic-confidence is the fast free gate; single-response
                    // exports carry NO speaker markers though, so a heuristic
                    // miss gets one conservative LLM second opinion before we
                    // archive. Deletion still only ever happens on a positive.
                    val isGemini = verdict == Verdict.GEMINI_EXPORT ||
                        llmClassifyAiChat(cfg, disableThinking, doc.name, text)
                    if (isGemini) {
                        val facts = distill(cfg, disableThinking, doc.name, text)
                        factStore.put(
                            GDriveFact(
                                docId = doc.id,
                                title = doc.name,
                                facts = facts,
                                ingestedAt = System.currentTimeMillis()
                            )
                        )
                        client.trash(doc.id) // only AFTER successful ingestion
                        ingested++
                        DebugLog.i(TAG, "ingested+trashed '${doc.name}' (${facts.length} chars of facts)")
                    } else {
                        val folderId = client.ensureFolder(GDriveClient.ARCHIVE_FOLDER_NAME)
                        client.moveToFolder(doc.id, folderId)
                        moved++
                        DebugLog.i(TAG, "moved non-Gemini doc '${doc.name}' to ${GDriveClient.ARCHIVE_FOLDER_NAME}")
                    }
                } catch (e: Exception) {
                    failed++
                    problems.add("${doc.name}: ${e.message}")
                    DebugLog.w(TAG, "doc '${doc.name}' failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            return@withContext GDriveSyncResult(
                scanned = scanned, ingested = ingested, moved = moved, failed = failed,
                note = "sync aborted: ${e.message}"
            )
        }
        GDriveSyncResult(
            scanned = scanned, ingested = ingested, moved = moved, failed = failed,
            note = problems.take(3).joinToString("; ").ifBlank { null }
        )
    }

    /**
     * Conservative LLM second opinion for documents the pattern heuristic
     * could not identify. Answers true ONLY for AI-chat content (assistant
     * answers, transcripts); human-written material of any kind answers
     * false, and any LLM failure answers false (archive is the safe side).
     */
    private suspend fun llmClassifyAiChat(
        cfg: LlmConfig,
        disableThinking: Boolean,
        title: String,
        text: String
    ): Boolean {
        if (!cfg.configured) return false
        return try {
            val reply = llm.chat(
                cfg,
                listOf(
                    LlmMessage.system(CLASSIFY_SYSTEM),
                    LlmMessage.user("Document title: \"$title\"\n\n${text.take(MAX_DOC_CHARS)}")
                ),
                temperature = 0.0f,
                maxTokens = CLASSIFY_MAX_TOKENS,
                disableThinking = disableThinking
            )
            val json = Prompts.extractJson(reply.content ?: "")
            JSONObject(json).optBoolean("ai_chat", false)
        } catch (e: Exception) {
            DebugLog.w(TAG, "llm classify failed (defaulting to archive): ${e.message}")
            false
        }
    }

    /**
     * LLM extraction of key/interesting facts and focal points from one
     * document. Returns compact text; on any failure the document's opening
     * is stored raw rather than losing the material.
     */
    private suspend fun distill(
        cfg: LlmConfig,
        disableThinking: Boolean,
        title: String,
        text: String
    ): String {
        val clipped = text.take(MAX_DOC_CHARS)
        return try {
            val reply = llm.chat(
                cfg,
                listOf(
                    LlmMessage.system(DISTILL_SYSTEM),
                    LlmMessage.user("Document title: \"$title\"\n\n$clipped")
                ),
                temperature = 0.2f,
                maxTokens = DISTILL_MAX_TOKENS,
                disableThinking = disableThinking
            )
            val out = reply.content?.trim().orEmpty()
            if (out.isBlank()) throw IllegalStateException("empty distillation")
            out
        } catch (e: Exception) {
            DebugLog.w(TAG, "distill failed — storing raw excerpt: ${e.message}")
            "Raw excerpt (distillation failed): " + text.take(CHARS_PER_LINE_FALLBACK)
        }
    }

    companion object {
        private const val TAG = "GDriveSync"

        /** Safety cap per run. */
        const val MAX_DOCS_PER_RUN = 10

        /** Characters of document text handed to the LLM. */
        const val MAX_DOC_CHARS = 12_000

        /** Fallback excerpt size when distillation fails. */
        const val CHARS_PER_LINE_FALLBACK = 300

        const val DISTILL_MAX_TOKENS = 1_500

        private const val CLASSIFY_MAX_TOKENS = 200

        private const val DISTILL_SYSTEM =
            "You extract study material from exported AI-chat documents. " +
            "From the document, pull out the KEY FACTS, interesting insights and " +
            "focal points of what was learned — concrete, verifiable statements " +
            "with their numbers/names preserved. Output a compact bullet list " +
            "(max ~8 bullets, one line each). No preamble, no commentary."

        private const val CLASSIFY_SYSTEM =
            "You classify Google Drive documents. Reply ONLY with JSON " +
            "{\"ai_chat\": true} or {\"ai_chat\": false}. " +
            "true ONLY if the document is clearly content PRODUCED BY AN AI " +
            "CHAT ASSISTANT (Gemini, ChatGPT, Claude, …): an assistant answering " +
            "a question or explaining a topic in assistant voice, or a transcript " +
            "of such a conversation, even if only the assistant's answer was " +
            "saved without any speaker labels. " +
            "false for anything a human wrote themselves — notes, essays, " +
            "articles, recipes, meeting minutes, letters, fiction — even if it " +
            "merely MENTIONS AI. When unsure, answer false."
    }
}
