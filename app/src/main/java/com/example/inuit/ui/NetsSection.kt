package com.example.inuit.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inuit.data.Net

/**
 * Settings → Nets: the net registry. Each net is a fully separate question
 * universe (questions, stats, knowledge map, podcast recommendations);
 * LLM/MCP/generation settings are shared globally. The All net owns all
 * pre-nets data and cannot be renamed or deleted.
 *
 * Each net can also switch on OCCASIONAL ACCENTS — location, date, and
 * knowledge pulled from other nets — that lightly season its questions
 * without leaving its scope.
 */
@Composable
fun NetsSection(viewModel: MainViewModel) {
    val nets by viewModel.nets.collectAsStateWithLifecycle()
    val activeNet by viewModel.activeNet.collectAsStateWithLifecycle()
    val sharedTailHabits by viewModel.sharedTextHabits.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Net?>(null) }     // edit dialog
    var creating by remember { mutableStateOf(false) }         // create dialog
    var confirmingDelete by remember { mutableStateOf<Net?>(null) }

    // Refresh the Tail-shared habit list whenever a net dialog opens, so the
    // life-log selector always reflects what Tail currently shares.
    LaunchedEffect(creating, editing) {
        if (creating || editing != null) viewModel.loadSharedTextHabits()
    }

    SectionCard(
        title = "Nets",
        subtitle = "separate question universes — questions, stats and podcasts never cross nets"
    ) {
        nets.forEach { net ->
            NetRow(
                net = net,
                isActive = net.id == activeNet.id,
                // Every net is editable; the All net's dialog hides name /
                // scope (they are fixed) and offers podcasts + accents only.
                onEdit = { editing = net },
                onDelete = if (net.isAll) null else { { confirmingDelete = net } }
            )
        }
        OutlinedButton(onClick = { creating = true }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("New net")
        }
        Text(
            "A new net starts completely empty and becomes active immediately. " +
                "Switch nets from the dropdown at the top of the main screen.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (creating) {
        NetEditDialog(
            title = "New net",
            initial = null,
            otherNets = nets,
            sharedTailHabits = sharedTailHabits,
            onDismiss = { creating = false },
            onSave = { draft ->
                creating = false
                viewModel.createNet(draft)
            }
        )
    }
    editing?.let { net ->
        NetEditDialog(
            title = if (net.isAll) "Edit All net" else "Edit net",
            initial = net,
            otherNets = nets.filter { it.id != net.id },
            sharedTailHabits = sharedTailHabits,
            onDismiss = { editing = null },
            onSave = { draft ->
                editing = null
                viewModel.updateNet(draft)
            }
        )
    }
    confirmingDelete?.let { net ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete \"${net.name}\"?") },
            text = {
                Text(
                    "Its questions, answers, stats and podcast recommendations " +
                        "are erased permanently. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = null
                    viewModel.deleteNet(net.id)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun NetRow(
    net: Net,
    isActive: Boolean,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    net.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                )
                if (isActive) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (net.description.isNotBlank()) {
                Text(
                    net.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit net",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete net",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** Max source nets the generator reads accents from (matches AccentsBuilder). */
private const val MAX_SOURCE_NETS = 3

/**
 * Create / edit dialog: name, scope description, podcast toggle and the
 * occasional accents (location / date / other nets). Returns a full [Net]
 * draft; the caller persists it.
 */
@Composable
private fun NetEditDialog(
    title: String,
    initial: Net?,
    otherNets: List<Net>,
    sharedTailHabits: List<String>,
    onDismiss: () -> Unit,
    onSave: (Net) -> Unit
) {
    val isAll = initial?.isAll == true
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var description by rememberSaveable { mutableStateOf(initial?.description ?: "") }
    var podcastEnabled by rememberSaveable { mutableStateOf(initial?.podcastEnabled ?: true) }
    var locationEnabled by rememberSaveable { mutableStateOf(initial?.locationEnabled ?: false) }
    var dateEnabled by rememberSaveable { mutableStateOf(initial?.dateEnabled ?: false) }
    var tailTextEnabled by rememberSaveable { mutableStateOf(initial?.tailTextEnabled ?: false) }
    // Habit names can contain commas — join with newlines (names are single-line).
    var tailHabitsCsv by rememberSaveable {
        mutableStateOf(initial?.tailTextHabits?.joinToString("\n") ?: "")
    }
    val selectedTailHabits = tailHabitsCsv.split('\n').filter { it.isNotBlank() }
    // Set<String> isn't Bundle-saveable; keep the selection as a CSV of ids.
    var sourceIdsCsv by rememberSaveable {
        mutableStateOf(initial?.sourceNetIds?.joinToString(",") ?: "")
    }
    val selectedSources = sourceIdsCsv.split(',').filter { it.isNotBlank() }

    val context = LocalContext.current
    // Optimistically flip the toggle on; the callback reverts it if denied.
    val locationPermission = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted: Boolean -> if (!granted) locationEnabled = false }

    fun toggleSource(id: String) {
        val current = selectedSources.toMutableList()
        if (id in current) current.remove(id)
        else if (current.size < MAX_SOURCE_NETS) current.add(id)
        sourceIdsCsv = current.joinToString(",")
    }

    fun toggleTailHabit(name: String) {
        val current = selectedTailHabits.toMutableList()
        if (name in current) current.remove(name) else current.add(name)
        tailHabitsCsv = current.joinToString("\n")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                if (!isAll) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Net name") },
                        placeholder = { Text("e.g. Juggling") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Scope description") },
                        placeholder = { Text("All aspects of Juggling — patterns, science, technology, history, research…") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Every question, stat and podcast recommendation for this net " +
                            "stays inside the scope you describe here.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "The All net spans all knowledge — its name and scope are fixed. " +
                            "Podcasts and occasional accents are configurable below.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(vertical = 2.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Podcast recommendations", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Turn off for nets where episodes don't make sense",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = podcastEnabled, onCheckedChange = { podcastEnabled = it })
                }

                Text(
                    "Occasional accents",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Light seasoning only — at most a question or two per batch; " +
                        "the net's own scope always dominates.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(vertical = 2.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Location accents", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Occasionally ask about your current region " +
                                "(needs location permission)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = locationEnabled,
                        onCheckedChange = { want ->
                            if (!want) {
                                locationEnabled = false
                            } else if (ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                locationEnabled = true
                            } else {
                                locationEnabled = true // optimistic; reverted if denied
                                locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                        }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(vertical = 2.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Date accents", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Occasionally tie questions to today — this date in " +
                                "history, this year in past centuries",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = dateEnabled, onCheckedChange = { dateEnabled = it })
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(vertical = 2.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Tail life-log accents", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Occasionally draw inspiration from your recent " +
                                "Tail notes (most recent entries only)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = tailTextEnabled, onCheckedChange = { tailTextEnabled = it })
                }
                if (tailTextEnabled) {
                    if (sharedTailHabits.isEmpty()) {
                        Text(
                            "Nothing shared yet — pick text-input habits in Tail: " +
                                "Settings → Integrations → Inuit.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Shared habits this net may draw on:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        sharedTailHabits.forEach { habit ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { toggleTailHabit(habit) }
                                    .padding(vertical = 1.dp)
                            ) {
                                Checkbox(
                                    checked = habit in selectedTailHabits,
                                    onCheckedChange = { toggleTailHabit(habit) }
                                )
                                Text(habit, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (otherNets.isNotEmpty()) {
                    Text("Pull knowledge from other nets", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Occasionally anchor questions in what you know from these " +
                            "nets — questions still stay inside this net's scope.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    otherNets.forEach { src ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { toggleSource(src.id) }
                                .padding(vertical = 1.dp)
                        ) {
                            Checkbox(
                                checked = src.id in selectedSources,
                                onCheckedChange = { toggleSource(src.id) }
                            )
                            Column {
                                Text(src.name, style = MaterialTheme.typography.bodySmall)
                                if (src.description.isNotBlank()) {
                                    Text(
                                        src.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    if (selectedSources.size >= MAX_SOURCE_NETS) {
                        Text(
                            "At most $MAX_SOURCE_NETS source nets are used.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // For the All net, name/description come unchanged from
                    // the initial net (the fields are hidden).
                    val draft = (initial ?: Net(name = "")).copy(
                        name = if (isAll) initial!!.name else name.trim(),
                        description = if (isAll) initial!!.description else description.trim(),
                        podcastEnabled = podcastEnabled,
                        locationEnabled = locationEnabled,
                        dateEnabled = dateEnabled,
                        tailTextEnabled = tailTextEnabled,
                        tailTextHabits = selectedTailHabits,
                        sourceNetIds = selectedSources
                    )
                    onSave(draft)
                },
                enabled = isAll || name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
