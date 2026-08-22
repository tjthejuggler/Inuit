package com.example.inuit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.inuit.data.Question
import com.example.inuit.data.QuestionType
import com.example.inuit.ui.theme.CorrectGreen
import com.example.inuit.ui.theme.StreakFlame
import com.example.inuit.ui.theme.WrongRed
import kotlin.random.Random

/**
 * The question card. CRITICAL INVARIANT: this composable (and everything it
 * calls) must never render the question's stored answer — feedback is
 * correct/incorrect only. The app is Socratic by design.
 */
@Composable
fun QuestionCard(
    question: Question,
    feedback: MainViewModel.Feedback.Result?,
    onSubmit: (raw: String, elapsedMs: Long) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    val startedAt = remember(question.id) { System.nanoTime() }
    var input by remember(question.id) { mutableStateOf("") }
    val answered = feedback != null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // ── meta row ──────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeChip(question.type)
                Spacer(Modifier.width(8.dp))
                DifficultyDots(question.difficulty)
                Spacer(Modifier.width(8.dp))
                if (question.parentId != null) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "thread",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                question.domains.firstOrNull()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                question.prompt,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(16.dp))

            // ── answer input per type ─────────────────────────────────────
            when (question.type) {
                QuestionType.TRUE_FALSE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigChoiceButton("True", enabled = !answered, modifier = Modifier.weight(1f)) {
                            onSubmit("true", elapsedMs(startedAt))
                        }
                        BigChoiceButton("False", enabled = !answered, modifier = Modifier.weight(1f)) {
                            onSubmit("false", elapsedMs(startedAt))
                        }
                    }
                }

                QuestionType.MULTIPLE_CHOICE -> {
                    val order = remember(question.id) {
                        question.choices.indices.shuffled(Random(question.id.hashCode().toLong() * 31))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        order.forEach { idx ->
                            ChoiceButton(
                                text = question.choices[idx],
                                enabled = !answered,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                onSubmit(idx.toString(), elapsedMs(startedAt))
                            }
                        }
                    }
                }

                QuestionType.NUMERIC -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            enabled = !answered,
                            singleLine = true,
                            label = { Text("Your answer") },
                            suffix = question.unit?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { onSubmit(input, elapsedMs(startedAt)) },
                            enabled = !answered && input.isNotBlank()
                        ) { Text("Submit") }
                    }
                }

                QuestionType.FILL_BLANK -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            enabled = !answered,
                            singleLine = true,
                            label = { Text("Your answer") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { onSubmit(input, elapsedMs(startedAt)) },
                            enabled = !answered && input.isNotBlank()
                        ) { Text("Submit") }
                    }
                }
            }

            // ── feedback (NEVER the answer) ───────────────────────────────
            AnimatedVisibility(visible = answered) {
                feedback?.let { fb ->
                    Column {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (fb.correct) CorrectGreen.copy(alpha = 0.14f)
                                    else WrongRed.copy(alpha = 0.14f)
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (fb.correct) "Correct" else "Not quite",
                                    fontWeight = FontWeight.Bold,
                                    color = if (fb.correct) CorrectGreen else WrongRed
                                )
                                if (!fb.correct) {
                                    Text(
                                        "Noted. Simpler threads will weave toward this one.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (fb.streak >= 2) {
                                Text("🔥 ${fb.streak}", color = StreakFlame, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onNext, modifier = Modifier.weight(1f)) {
                                Text("Next question")
                            }
                            OutlinedButton(onClick = onSkip, enabled = false) {
                                Text("Skip")
                            }
                        }
                    }
                }
            }

            if (!answered) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onSkip) { Text("Skip") }
                }
            }
        }
    }
}

private fun elapsedMs(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000L

@Composable
private fun TypeChip(type: QuestionType) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            type.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun DifficultyDots(difficulty: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(5) { i ->
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        if (i < difficulty) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.outline
                    )
            )
        }
    }
}

@Composable
private fun BigChoiceButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ChoiceButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Collapsed one-line form of the question card (stats browsing mode). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsedQuestionBar(
    question: Question?,
    feedback: MainViewModel.Feedback.Result?,
    onExpand: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        onClick = onExpand,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (feedback != null) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (feedback.correct) CorrectGreen else WrongRed)
                )
            } else {
                TypeChip(question?.type ?: QuestionType.MULTIPLE_CHOICE)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                question?.prompt ?: "Tap for the next question",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Expand question",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Header row shown above the expanded card (collapse toggle). */
@Composable
fun CollapseToggle(collapsed: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            contentDescription = if (collapsed) "Expand question" else "Collapse question",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
