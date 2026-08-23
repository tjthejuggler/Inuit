package com.example.inuit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inuit.data.HabitEntry
import com.example.inuit.ui.theme.Amber
import com.example.inuit.ui.theme.Indigo
import com.example.inuit.ui.theme.LogOk
import com.example.inuit.ui.theme.TextSecondary

/**
 * Settings → Tail app section: connect ONE Tail habit to the questions-answered
 * counter, and push the full answer history (past + today so far) to it.
 */
@Composable
fun TailSettingsSection(viewModel: MainViewModel) {
    val state by viewModel.tailState.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    SectionCard(
        title = "Tail app",
        subtitle = "send the number of questions answered to the Tail habit tracker"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Questions habit",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Indigo,
                    strokeWidth = 2.dp
                )
            } else {
                TextButton(onClick = { viewModel.loadTailHabits() }) {
                    Text("Refresh", style = MaterialTheme.typography.bodySmall, color = Indigo)
                }
            }
        }

        when {
            state.appUnavailable ->
                Text(
                    "Tail app not found. Make sure it is installed and tap Refresh.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber
                )
            !state.isLoading && state.habitList.isEmpty() && !state.selected.isSet ->
                Text(
                    "No habits loaded yet. Tap Refresh to fetch them from Tail.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (state.selected.isSet) {
                    Text(
                        state.selected.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = LogOk
                    )
                    Text(
                        "+1 in Tail for every answered question",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                } else {
                    Text(
                        "Not set",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.selected.isSet) {
                    IconButton(
                        onClick = { viewModel.clearTailHabit() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear habit",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                ) {
                    Text(
                        if (state.selected.isSet) "Change" else "Set",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ── Retroactive backfill ──────────────────────────────────────────
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Backfill answer history", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Send per-day counts for every past answer — including today " +
                        "so far — to Tail. Connecting a habit backfills automatically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            if (state.isBackfilling) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Indigo,
                    strokeWidth = 2.dp
                )
            } else {
                TextButton(onClick = { viewModel.backfillTail() }) {
                    Text("Send", style = MaterialTheme.typography.bodySmall, color = Indigo)
                }
            }
        }

        state.backfillMessage?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.labelSmall,
                color = LogOk,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.clearBackfillMessage() }
            )
        }
        state.backfillError?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.clearBackfillMessage() }
            )
        }
    }

    if (showPicker) {
        HabitPickerDialog(
            habitList = state.habitList,
            selectedId = state.selected.habitId,
            onSelect = { entry ->
                viewModel.selectTailHabit(entry)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

/**
 * Searchable, alphabetically sorted habit picker popup for the
 * questions-answered slot.
 */
@Composable
private fun HabitPickerDialog(
    habitList: List<HabitEntry>,
    selectedId: String,
    onSelect: (HabitEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(habitList, query) {
        val sorted = habitList.sortedBy { it.habitName.lowercase() }
        if (query.isBlank()) sorted
        else sorted.filter { it.habitName.contains(query.trim(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column {
                Text("Select Habit", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Questions answered",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search habits") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    tint = TextSecondary
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small
                )
                Spacer(Modifier.height(8.dp))
                when {
                    habitList.isEmpty() ->
                        Text(
                            "No habits available. Tap Refresh on the Tail section.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    filtered.isEmpty() ->
                        Text(
                            "No habits match \"${query.trim()}\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    else ->
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // No explicit key: Tail can return duplicate/blank
                            // habit names, which would crash LazyColumn.
                            items(filtered) { entry ->
                                HabitPickerRow(
                                    entry = entry,
                                    isSelected = entry.habitId == selectedId,
                                    onClick = { onSelect(entry) }
                                )
                            }
                        }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun HabitPickerRow(
    entry: HabitEntry,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant
        else androidx.compose.ui.graphics.Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = entry.habitName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else TextSecondary,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Spacer(Modifier.width(4.dp))
                Text("✓", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
