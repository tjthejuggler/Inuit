package com.example.inuit.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.inuit.data.Question
import com.example.inuit.data.QuestionType
import com.example.inuit.ui.theme.Indigo
import com.example.inuit.ui.theme.Teal
import kotlin.random.Random

/**
 * The question card. CRITICAL INVARIANTS:
 *  - never render the question's stored answer,
 *  - never signal whether an answer was correct or wrong (blind training).
 *    Submitting advances to the next question IMMEDIATELY — no correctness
 *    acknowledgment, no "next" tap. Correctness only ever surfaces as
 *    aggregate, session-frozen statistics that refresh when the user
 *    returns to the app.
 */
@Composable
fun QuestionCard(
    question: Question,
    onSubmit: (questionId: String, raw: String, elapsedMs: Long) -> Unit,
    onSkip: () -> Unit
) {
    val startedAt = remember(question.id) { System.nanoTime() }
    var input by remember(question.id) { mutableStateOf("") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                listOf(Indigo.copy(alpha = 0.40f), Teal.copy(alpha = 0.22f), MaterialTheme.colorScheme.outlineVariant)
            )
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            // ── meta row ──────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeChip(question.type)
                Spacer(Modifier.width(10.dp))
                DifficultyMeter(question.difficulty)
                Spacer(Modifier.weight(1f))
                if (question.parentId != null) {
                    MetaTag("THREAD", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(6.dp))
                }
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

            Spacer(Modifier.height(14.dp))
            Text(
                question.prompt,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(18.dp))

            // ── answer input per type ─────────────────────────────────────
            when (question.type) {
                QuestionType.TRUE_FALSE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigChoiceButton("True", modifier = Modifier.weight(1f)) {
                            onSubmit(question.id, "true", elapsedMs(startedAt))
                        }
                        BigChoiceButton("False", modifier = Modifier.weight(1f)) {
                            onSubmit(question.id, "false", elapsedMs(startedAt))
                        }
                    }
                }

                QuestionType.MULTIPLE_CHOICE -> {
                    val order = remember(question.id) {
                        question.choices.indices.shuffled(Random(question.id.hashCode().toLong() * 31))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        order.forEachIndexed { position, idx ->
                            ChoiceButton(
                                text = question.choices[idx],
                                letter = ('A' + position),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                onSubmit(question.id, idx.toString(), elapsedMs(startedAt))
                            }
                        }
                    }
                }

                QuestionType.NUMERIC -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            label = { Text("Your answer") },
                            suffix = question.unit?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { onSubmit(question.id, input, elapsedMs(startedAt)) },
                            enabled = input.isNotBlank(),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Submit") }
                    }
                }

                QuestionType.FILL_BLANK -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            label = { Text("Your answer") },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { onSubmit(question.id, input, elapsedMs(startedAt)) },
                            enabled = input.isNotBlank(),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Submit") }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onSkip, shape = RoundedCornerShape(14.dp)) { Text("Skip") }
            }
        }
    }
}

private fun elapsedMs(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000L

// ── small meta components ─────────────────────────────────────────────────

@Composable
private fun TypeChip(type: QuestionType) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            type.displayName.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun MetaTag(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

/** Segmented difficulty meter (1–5) — reads like an instrument gauge. */
@Composable
private fun DifficultyMeter(difficulty: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            Box(
                Modifier
                    .size(width = 10.dp, height = 5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (i < difficulty) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
    }
}

// ── answer controls ───────────────────────────────────────────────────────

@Composable
private fun BigChoiceButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChoiceButton(
    text: String,
    letter: Char,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                letter.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Left,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
