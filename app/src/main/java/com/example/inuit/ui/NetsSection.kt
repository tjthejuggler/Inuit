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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inuit.data.CustomSource
import com.example.inuit.data.Net
import com.example.inuit.data.SourceMix

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
    // Source mix, in percent per accent (core = the remainder). Sliders snap
    // to steps of 5; accents may take the whole batch (core may reach 0),
    // and a raw map summing past 100 is scaled back on save.
    val initialMix = initial?.mix() ?: SourceMix.legacy(false, false, false, false)
    var wLocation by rememberSaveable { mutableStateOf(initialMix[SourceMix.LOCATION] ?: 0) }
    var wDate by rememberSaveable { mutableStateOf(initialMix[SourceMix.DATE] ?: 0) }
    var wCrossNet by rememberSaveable { mutableStateOf(initialMix[SourceMix.CROSS_NET] ?: 0) }
    var wTailText by rememberSaveable { mutableStateOf(initialMix[SourceMix.TAIL_TEXT] ?: 0) }
    var wGdrive by rememberSaveable { mutableStateOf(initialMix[SourceMix.GDRIVE] ?: 0) }
    var wShared by rememberSaveable { mutableStateOf(initialMix[SourceMix.SHARED] ?: 0) }
    // Custom sources: label + guidance + own weight. Plain remember (not
    // saveable) — CustomSource isn't Bundle-saveable; losing an in-progress
    // dialog on process death is acceptable.
    var customs by remember { mutableStateOf(initial?.customSources ?: emptyList()) }
    var customWeights by remember {
        val ws = HashMap<String, Int>()
        initial?.mix()?.forEach { (k, v) ->
            k.removePrefix("custom:")?.let { id -> if (id != k) ws[id] = v }
        }
        mutableStateOf(ws)
    }
    var editingCustom by remember { mutableStateOf<CustomSource?>(null) }
    var addingCustom by remember { mutableStateOf(false) }
    var confirmingDeleteCustom by remember { mutableStateOf<CustomSource?>(null) }
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
    ) { granted: Boolean -> if (!granted) wLocation = 0 }

    /** Snap to 5% steps; accents share one budget of 100 (validated on save). */
    fun snapAccent(raw: Float): Int = (raw / 5f).toInt() * 5

    /** Sum of all accent weights — whatever is left of 100 is core. */
    fun othersSum(): Int =
        wLocation + wDate + wCrossNet + wTailText + wGdrive + wShared +
            customs.sumOf { customWeights[it.id] ?: 0 }

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
                    "Question source mix",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "How each batch's questions are distributed. Core is the net's " +
                        "own adaptive material; the sources below may take the " +
                        "whole batch. Tap a source's name for an explanation.",
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
                    InfoName(
                        "Core (this net)",
                        "Adaptive questions chosen from the net's own scope and " +
                            "spaced-repetition state — whatever the sources below " +
                            "leave of each batch. It may be 0%.",
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${(100 - othersSum()).coerceAtLeast(0)}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (othersSum() > 100) {
                    Text(
                        "Sources add up to more than 100% — shares are scaled " +
                            "back on save.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(vertical = 2.dp)
                ) {
                    InfoName(
                        "Location",
                        "Questions tied to your current region — city, country, " +
                            "local topics. Needs location permission.",
                        modifier = Modifier.weight(1f)
                    )
                    Text("$wLocation%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = wLocation.toFloat(),
                        onValueChange = { raw ->
                            val snapped = snapAccent(raw)
                            if (snapped > 0 && wLocation == 0) {
                                // flipping on — make sure the permission is there
                                if (ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.ACCESS_COARSE_LOCATION
                                    ) == PackageManager.PERMISSION_GRANTED ||
                                    ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.ACCESS_FINE_LOCATION
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    wLocation = snapped
                                } else {
                                    wLocation = snapped // optimistic; reverted if denied
                                    locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                                }
                            } else {
                                wLocation = snapped
                            }
                        },
                        valueRange = 0f..100f,
                        steps = 19,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(vertical = 2.dp)
                ) {
                    InfoName(
                        "Date",
                        "Questions tied to today — this date in history, " +
                            "anniversaries, this year in past centuries.",
                        modifier = Modifier.weight(1f)
                    )
                    Text("$wDate%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = wDate.toFloat(),
                        onValueChange = { raw ->
                            wDate = snapAccent(raw)
                        },
                        valueRange = 0f..100f,
                        steps = 19,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(vertical = 2.dp)
                ) {
                    InfoName(
                        "Other nets",
                        "Questions anchored in what you know from the source " +
                            "nets picked below — still inside this net's scope.",
                        modifier = Modifier.weight(1f)
                    )
                    Text("$wCrossNet%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = wCrossNet.toFloat(),
                        onValueChange = { raw ->
                            wCrossNet = snapAccent(raw)
                        },
                        valueRange = 0f..100f,
                        steps = 19,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(vertical = 2.dp)
                ) {
                    InfoName(
                        "Tail life-log",
                        "Questions inspired by your recent Tail life-log notes " +
                            "(most recent entries only).",
                        modifier = Modifier.weight(1f)
                    )
                    Text("$wTailText%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = wTailText.toFloat(),
                        onValueChange = { raw ->
                            wTailText = snapAccent(raw)
                        },
                        valueRange = 0f..100f,
                        steps = 19,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(vertical = 2.dp)
                ) {
                    InfoName(
                        "Drive documents",
                        "Questions from key facts in the Gemini chat exports " +
                            "saved to your Google Drive root (needs Drive " +
                            "credentials in Settings).",
                        modifier = Modifier.weight(1f)
                    )
                    Text("$wGdrive%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = wGdrive.toFloat(),
                        onValueChange = { raw ->
                            wGdrive = snapAccent(raw)
                        },
                        valueRange = 0f..100f,
                        steps = 19,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(vertical = 2.dp)
                ) {
                    InfoName(
                        "Shared snippets",
                        "Questions drawn from text you shared to Inuit from " +
                            "anywhere on your phone (share menu → Inuit). " +
                            "Full facts become quiz material and related " +
                            "questions; short topics inspire new questions.",
                        modifier = Modifier.weight(1f)
                    )
                    Text("$wShared%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = wShared.toFloat(),
                        onValueChange = { raw ->
                            wShared = snapAccent(raw)
                        },
                        valueRange = 0f..100f,
                        steps = 19,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                }

                // ── custom sources ─────────────────────────────────────────
                customs.forEach { src ->
                    val w = customWeights[src.id] ?: 0
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .padding(vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            InfoName(
                                src.label,
                                if (src.guidance.isNotBlank()) src.guidance
                                else "Your own source — edit it to add guidance the " +
                                    "generator follows for this source's share of " +
                                    "each batch.",
                                modifier = Modifier.weight(1f)
                            )
                            Text("$w%", style = MaterialTheme.typography.labelMedium)
                            IconButton(
                                onClick = { editingCustom = src },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit, contentDescription = "Edit custom source",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            IconButton(
                                onClick = { confirmingDeleteCustom = src },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete, contentDescription = "Delete custom source",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Slider(
                            value = w.toFloat(),
                            onValueChange = { raw ->
                                customWeights = HashMap(customWeights).also {
                                    it[src.id] = snapAccent(raw)
                                }
                            },
                            valueRange = 0f..100f,
                            steps = 19
                        )
                    }
                }
                OutlinedButton(
                    onClick = { addingCustom = true },
                    enabled = customs.size < MAX_CUSTOM_SOURCES
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add custom source")
                }
                if (wTailText > 0) {
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
                            InfoName(
                                src.name,
                                if (src.description.isNotBlank()) src.description
                                else "Source net — anchor questions in what you " +
                                    "know from this net.",
                                modifier = Modifier.weight(1f)
                            )
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
                        locationEnabled = wLocation > 0,
                        dateEnabled = wDate > 0,
                        tailTextEnabled = wTailText > 0,
                        gdriveEnabled = wGdrive > 0,
                        sharedTextEnabled = wShared > 0,
                        tailTextHabits = selectedTailHabits,
                        sourceNetIds = selectedSources,
                        sourceWeights = SourceMix.normalize(
                            buildMap {
                                put(SourceMix.LOCATION, wLocation)
                                put(SourceMix.DATE, wDate)
                                put(SourceMix.CROSS_NET, wCrossNet)
                                put(SourceMix.TAIL_TEXT, wTailText)
                                put(SourceMix.GDRIVE, wGdrive)
                                put(SourceMix.SHARED, wShared)
                                customs.forEach { put(SourceMix.customKey(it.id), customWeights[it.id] ?: 0) }
                            }
                        ),
                        customSources = customs
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

    // ── custom-source add / edit / delete dialogs ─────────────────────────
    if (addingCustom || editingCustom != null) {
        CustomSourceDialog(
            initial = editingCustom,
            onDismiss = { addingCustom = false; editingCustom = null },
            onSave = { src ->
                if (addingCustom) {
                    customs = customs + src
                } else if (editingCustom != null) {
                    customs = customs.map { if (it.id == src.id) src else it }
                }
                addingCustom = false
                editingCustom = null
            }
        )
    }
    confirmingDeleteCustom?.let { src ->
        AlertDialog(
            onDismissRequest = { confirmingDeleteCustom = null },
            title = { Text("Delete \"${src.label}\"?") },
            text = { Text("Its slider and guidance are removed; its share returns to core.") },
            confirmButton = {
                TextButton(onClick = {
                    customs = customs.filter { it.id != src.id }
                    customWeights = HashMap(customWeights).also { it.remove(src.id) }
                    confirmingDeleteCustom = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDeleteCustom = null }) { Text("Cancel") }
            }
        )
    }
}

/** Max custom sources per net (bounded so the mix stays legible). */
private const val MAX_CUSTOM_SOURCES = 6

/** Add / edit dialog for a custom question source: label + guidance. */
@Composable
private fun CustomSourceDialog(
    initial: CustomSource?,
    onDismiss: () -> Unit,
    onSave: (CustomSource) -> Unit
) {
    var label by rememberSaveable { mutableStateOf(initial?.label ?: "") }
    var guidance by rememberSaveable { mutableStateOf(initial?.guidance ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New custom source" else "Edit custom source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Numbers & magnitudes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = guidance,
                    onValueChange = { guidance = it },
                    label = { Text("Guidance") },
                    placeholder = { Text("What questions from this source should focus on — e.g. \"order-of-magnitude estimates in physics and everyday life\"") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "The generator follows this guidance for this source's share of every batch; " +
                        "questions still stay inside the net's scope.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        (initial ?: CustomSource(label = "")).copy(
                            label = label.trim(),
                            guidance = guidance.trim()
                        )
                    )
                },
                enabled = label.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** A source name that opens a small explanatory popup when tapped — keeps
 *  each mix row to a single uncluttered line. */
@Composable
private fun InfoName(
    name: String,
    explanation: String,
    modifier: Modifier = Modifier
) {
    var showTip by remember { mutableStateOf(false) }
    Text(
        name,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable { showTip = !showTip }
    )
    if (showTip) {
        Popup(
            alignment = Alignment.BottomStart,
            onDismissRequest = { showTip = false },
            properties = PopupProperties(focusable = true)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}
