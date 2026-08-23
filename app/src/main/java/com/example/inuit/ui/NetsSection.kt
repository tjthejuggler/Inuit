package com.example.inuit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inuit.data.Net

/**
 * Settings → Nets: the net registry. Each net is a fully separate question
 * universe (questions, stats, knowledge map, podcast recommendations);
 * LLM/MCP/generation settings are shared globally. The All net owns all
 * pre-nets data and cannot be renamed or deleted.
 */
@Composable
fun NetsSection(viewModel: MainViewModel) {
    val nets by viewModel.nets.collectAsStateWithLifecycle()
    val activeNet by viewModel.activeNet.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Net?>(null) }     // edit dialog
    var creating by remember { mutableStateOf(false) }         // create dialog
    var confirmingDelete by remember { mutableStateOf<Net?>(null) }

    SectionCard(
        title = "Nets",
        subtitle = "separate question universes — questions, stats and podcasts never cross nets"
    ) {
        nets.forEach { net ->
            NetRow(
                net = net,
                isActive = net.id == activeNet.id,
                // The All net's scope is fixed ("all knowledge") — only its
                // podcast toggle is editable; user nets are fully editable.
                onEdit = if (net.isAll) null else { { editing = net } },
                onDelete = if (net.isAll) null else { { confirmingDelete = net } },
                onTogglePodcasts = { enabled ->
                    viewModel.updateNet(net.copy(podcastEnabled = enabled))
                }
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
            onDismiss = { creating = false },
            onSave = { name, description, podcastEnabled ->
                creating = false
                viewModel.createNet(name, description, podcastEnabled)
            }
        )
    }
    editing?.let { net ->
        NetEditDialog(
            title = "Edit net",
            initial = net,
            onDismiss = { editing = null },
            onSave = { name, description, podcastEnabled ->
                editing = null
                viewModel.updateNet(
                    net.copy(name = name, description = description, podcastEnabled = podcastEnabled)
                )
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
    onDelete: (() -> Unit)?,
    onTogglePodcasts: (Boolean) -> Unit
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
        Text(
            "Podcasts",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(
            checked = net.podcastEnabled,
            onCheckedChange = onTogglePodcasts,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
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

/** Create / edit dialog: name, scope description, podcast toggle. */
@Composable
private fun NetEditDialog(
    title: String,
    initial: Net?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, podcastEnabled: Boolean) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var description by rememberSaveable { mutableStateOf(initial?.description ?: "") }
    var podcastEnabled by rememberSaveable { mutableStateOf(initial?.podcastEnabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), description.trim(), podcastEnabled) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
