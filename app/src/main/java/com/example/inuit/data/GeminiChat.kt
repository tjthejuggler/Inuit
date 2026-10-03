package com.example.inuit.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Opens the user's Gemini app for a conversational deep-dive on a question.
 *
 * There is no public API to pre-type text into the Gemini app's composer,
 * so the launch is a best-effort pre-fill over a guaranteed clipboard
 * baseline (the same philosophy as [PodcastApps]):
 *
 *  1. the prompt ALWAYS lands on the clipboard first — one paste away no
 *     matter what the app does with it,
 *  2. the Gemini app is opened as a text share target (recent versions
 *     pre-fill a new chat from a share); if it declines, a cold launch,
 *  3. no Gemini installed → the prompt stays on the clipboard and the
 *     Play Store listing opens.
 */
object GeminiChat {

    /** The Google Gemini app package. */
    const val GEMINI_PACKAGE = "com.google.android.apps.bard"

    private const val PLAY_STORE_URL =
        "https://play.google.com/store/apps/details?id=$GEMINI_PACKAGE"

    /**
     * Generic "tell me all about this" prompt for a question. For the
     * post-answer recap the user's answer and the canonical correct answer
     * ride along so the chat can continue right where the quiz left off.
     */
    fun buildPrompt(
        questionText: String,
        userAnswer: String? = null,
        correctAnswer: String? = null,
        topic: String? = null
    ): String = buildString {
        append("Tell me all about this — the places, times, people involved, and anything else interesting.")
        append("\n\n").append(questionText)
        userAnswer?.takeIf { it.isNotBlank() }?.let { append("\n\nMy answer: ").append(it) }
        correctAnswer?.takeIf { it.isNotBlank() }?.let { append("\nCorrect answer: ").append(it) }
        topic?.takeIf { it.isNotBlank() }?.let { append("\n\nTopic: ").append(it) }
    }

    /** Copies [prompt] to the clipboard and opens the Gemini app — see the
     *  class doc for the share → cold-launch → Play Store chain. */
    fun open(context: Context, prompt: String) {
        copyToClipboard(context, prompt)
        // 1. Share target: recent Gemini versions pre-fill a new chat from
        //    a text share. If the app is gone or declines, this throws and
        //    we fall through — the clipboard copy covers the user either way.
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, prompt)
            setPackage(GEMINI_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryLaunch(context, share)) {
            Toast.makeText(
                context,
                "Prompt copied — paste it if Gemini didn't fill it in",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        // 2. Cold launch: prompt is on the clipboard, one paste away.
        val launch = context.packageManager.getLaunchIntentForPackage(GEMINI_PACKAGE)
        if (launch != null && tryLaunch(context, launch.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        ) {
            Toast.makeText(context, "Prompt copied — paste it into Gemini", Toast.LENGTH_SHORT)
                .show()
            return
        }
        // 3. Not installed: prompt stays on the clipboard; show the listing.
        tryLaunch(
            context,
            Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        Toast.makeText(context, "Gemini app not found — prompt copied to clipboard", Toast.LENGTH_LONG)
            .show()
    }

    private fun tryLaunch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Inuit question", text))
    }
}
