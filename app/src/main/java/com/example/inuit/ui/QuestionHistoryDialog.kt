package com.example.inuit.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import com.example.inuit.ui.theme.Rose
import com.example.inuit.ui.theme.Teal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The flash shown right under the question card after each submit:
 * "Correct!" in teal, or the correct answer in rose when the user missed.
 */
@Composable
fun AnswerFlashBanner(flash: MainViewModel.AnswerFlash) {
    val correct = flash.correct
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (correct) Teal.copy(alpha = 0.14f) else Rose.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(
            if (correct) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = if (correct) "Correct" else "Incorrect",
            tint = if (correct) Teal else Rose,
            modifier = Modifier.width(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (correct) "Correct!" else "Correct answer: ${flash.correctAnswer}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (correct) Teal else Rose,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Question History for one domain-tree leaf: every answered question in
 * that domain, newest first. Long prompts are truncated; tapping a row
 * expands the full prompt and reveals the correct answer.
 */
@Composable
fun QuestionHistoryDialog(
    domainPath: String,
    items: List<MainViewModel.QuestionHistoryItem>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Column {
                Text("Question History", style = MaterialTheme.typography.titleMedium)
                Text(
                    domainPath,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            if (items.isEmpty()) {
                Text(
                    "No answered questions in this area yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(420.dp)
                ) {
                    items(items.size, key = { items[it].questionId }) { i ->
                        HistoryRow(items[i])
                    }
                }
            }
        }
    )
}

private val HistoryTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM · HH:mm")

@Composable
private fun HistoryRow(item: MainViewModel.QuestionHistoryItem) {
    var expanded by remember(item.questionId) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.prompt,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                if (item.lastCorrect) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = if (item.lastCorrect) "Correct" else "Incorrect",
                tint = if (item.lastCorrect) Teal else Rose,
                modifier = Modifier.width(18.dp)
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Answer: ${item.correctAnswer}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            val time = Instant.ofEpochMilli(item.lastAnsweredAt)
                .atZone(ZoneId.systemDefault())
                .format(HistoryTimeFormat)
            Text(
                "${item.correctCount}/${item.attempts} correct · you said \"${item.lastUserAnswerDisplay}\" · $time",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
