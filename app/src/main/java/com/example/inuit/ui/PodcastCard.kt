package com.example.inuit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.inuit.data.PodcastRec

/**
 * The bottom-of-stats podcast prescription: one LLM-chosen episode targeting
 * the user's weakest knowledge area. Tapping the card opens it in the podcast
 * app and retires the recommendation — a fresh one generates in the background.
 * The "why this" reason is collapsed to two lines until expanded. A small
 * history button lists previously clicked episodes (tap a row to expand its
 * reason — one at a time — or the ▶ button to open it), and when no podcast
 * app is chosen the card links to the Settings picker.
 */
@Composable
fun PodcastCard(
    rec: PodcastRec?,
    loading: Boolean,
    history: List<PodcastRec>,
    appConfigured: Boolean,
    onOpen: (PodcastRec) -> Unit,
    onOpenHistory: (PodcastRec) -> Unit,
    onOpenSettings: () -> Unit
) {
    var showHistory by remember { mutableStateOf(false) }

    SectionCard(title = "Podcast prescription", subtitle = "an episode for what you know least") {
        Column {
            if (history.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(
                        onClick = { showHistory = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = "Episode history",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            when {
                rec != null -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                            .clickable { onOpen(rec) }
                            .padding(12.dp)
                    ) {
                        Text(
                            rec.show,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            rec.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        CollapsibleReason(rec.reason)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tap to open in your podcast app ›",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Picking an episode for your weakest area…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> Text(
                    "An episode pick will appear here once the LLM is reachable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!appConfigured) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "No podcast app chosen — episodes may open in the browser.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Choose your podcast app in Settings ›",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onOpenSettings() }
                        .padding(vertical = 2.dp)
                )
            }
        }
    }

    if (showHistory) {
        PodcastHistoryDialog(
            history = history,
            onDismiss = { showHistory = false },
            onOpen = onOpenHistory
        )
    }
}

/** The "why this" reason, collapsed to two lines until the reader asks for more. */
@Composable
private fun CollapsibleReason(reason: String) {
    var expanded by remember(reason) { mutableStateOf(false) }
    var overflows by remember(reason) { mutableStateOf(false) }
    Text(
        reason,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow }
    )
    if (overflows || expanded) {
        Text(
            if (expanded) "less" else "more",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp)
        )
    }
}

@Composable
private fun PodcastHistoryDialog(
    history: List<PodcastRec>,
    onDismiss: () -> Unit,
    onOpen: (PodcastRec) -> Unit
) {
    // Only one row's reason is expanded at a time.
    var expandedKey by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Episode history") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                history.forEach { rec ->
                    val key = "${rec.createdAt}:${rec.title}"
                    val expanded = expandedKey == key
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { expandedKey = if (expanded) null else key }
                            .padding(vertical = 6.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                rec.show,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(rec.title, style = MaterialTheme.typography.bodyMedium)
                            if (expanded && rec.reason.isNotBlank()) {
                                Text(
                                    rec.reason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = { onOpen(rec) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Open episode",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
