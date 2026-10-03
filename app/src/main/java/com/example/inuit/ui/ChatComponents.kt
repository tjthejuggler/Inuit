package com.example.inuit.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.inuit.data.GeminiChat
import kotlinx.coroutines.delay

/** How long the skip-undo flash stays on screen. */
private const val SKIP_UNDO_WINDOW_MS = 7_000L

/**
 * Compact chat-bubble icon button: opens the Gemini app with [prompt]
 * pre-staged (clipboard + best-effort pre-fill — see [GeminiChat]).
 * Lives in the question card's bottom row.
 */
@Composable
fun GeminiChatIconButton(prompt: String) {
    val context = LocalContext.current
    IconButton(onClick = { GeminiChat.open(context, prompt) }) {
        Icon(
            Icons.Default.ChatBubble,
            contentDescription = "Chat about this question with Gemini",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Labeled variant for dialogs (the post-answer recap): same behaviour as
 * [GeminiChatIconButton], but an explicit full-width button so its purpose
 * is readable outside the live question context.
 */
@Composable
fun GeminiChatButton(prompt: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { GeminiChat.open(context, prompt) },
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.ChatBubble,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("Chat about this with Gemini")
    }
}

/**
 * The transient bottom flash after a skip: "Question skipped" + Undo,
 * visible for [SKIP_UNDO_WINDOW_MS]. [onExpire] clears the ViewModel's
 * skipped-question state when the window ends.
 */
@Composable
fun SkipUndoFlash(
    questionKey: Any,
    prompt: String,
    onUndo: () -> Unit,
    onExpire: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(questionKey) {
        delay(SKIP_UNDO_WINDOW_MS)
        onExpire()
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.inverseSurface,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Text(
                "Question skipped",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onUndo) {
                Icon(
                    Icons.Default.Undo,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.inversePrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Undo",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.inversePrimary
                )
            }
        }
    }
}
