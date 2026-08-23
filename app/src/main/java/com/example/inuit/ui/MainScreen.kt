package com.example.inuit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inuit.data.Net
import com.example.inuit.data.gen.QuestionGenerator.GenState
import com.example.inuit.ui.theme.Indigo
import com.example.inuit.ui.theme.Rose
import com.example.inuit.ui.theme.Teal

/**
 * Main screen: the question card pinned on top; everything below is the
 * stats/knowledge-map scroll. Stats are session-frozen (blind training):
 * they refresh only when the user returns to the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit
) {
    val question by viewModel.currentQuestion.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val summaries by viewModel.knowledgeSummaries.collectAsStateWithLifecycle()
    val genState by viewModel.genState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val queueSize by viewModel.queueSize.collectAsStateWithLifecycle()
    val nets by viewModel.nets.collectAsStateWithLifecycle()
    val activeNet by viewModel.activeNet.collectAsStateWithLifecycle()
    val podcast by viewModel.podcast.collectAsStateWithLifecycle()
    val podcastLoading by viewModel.podcastLoading.collectAsStateWithLifecycle()
    val podcastHistory by viewModel.podcastHistory.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Inuit",
                            fontWeight = FontWeight.Bold,
                            style = TextStyle(
                                brush = Brush.horizontalGradient(listOf(Indigo, Teal))
                            )
                        )
                        Text(
                            "INTUITION TRAINER",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                letterSpacing = 2.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    NetSelector(
                        activeNet = activeNet,
                        nets = nets,
                        onSelect = viewModel::selectNet
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "question") {
                val q = question
                if (q != null) {
                    Column {
                        QuestionCard(
                            question = q,
                            onSubmit = viewModel::submitAnswer,
                            onSkip = viewModel::skip
                        )
                        // Generation progress / errors sit below the question.
                        if (genState is GenState.Running || genState is GenState.Error) {
                            Spacer(Modifier.height(6.dp))
                            GenerationStatusChip(genState)
                        }
                    }
                } else {
                    EmptyQueueCard(
                        llmConfigured = settings.llmConfigured,
                        genState = genState,
                        onOpenSettings = onOpenSettings,
                        onGenerate = viewModel::generateNow
                    )
                }
            }

            item(key = "stats") {
                StatsPanel(
                    stats = stats,
                    summaries = summaries,
                    liveQueueSize = queueSize,
                    podcast = podcast,
                    podcastLoading = podcastLoading,
                    podcastHistory = podcastHistory,
                    podcastAppConfigured = settings.podcastAppPackage.isNotBlank(),
                    podcastEnabled = activeNet.podcastEnabled,
                    // Retire + regenerate + open (feed resolved on demand).
                    onOpenPodcast = viewModel::onPodcastOpened,
                    onOpenHistoryPodcast = viewModel::onHistoryPodcastOpened,
                    onOpenSettings = onOpenSettings
                )
            }
        }
    }
}

/**
 * Top-bar net selector: a chip showing the active net that opens a
 * dropdown of every net. Switching swaps the entire app — questions,
 * stats, podcasts — to that net's separate universe.
 */
@Composable
private fun NetSelector(
    activeNet: Net,
    nets: List<Net>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                activeNet.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp)
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = "Choose net",
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            nets.forEach { net ->
                val active = net.id == activeNet.id
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    net.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                )
                                if (!net.isAll && net.description.isNotBlank()) {
                                    Text(
                                        net.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (active) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Active net",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(net.id)
                    },
                    // Menu items echo the chip: rounded pills, the active net
                    // highlighted with the same surfaceVariant fill.
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                        )
                )
            }
        }
    }
}

@Composable
private fun GenerationStatusChip(state: GenState) {
    val (bg, fg, label) = when (state) {
        is GenState.Running -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.primary,
            state.note
        )
        is GenState.Error -> Triple(
            Rose.copy(alpha = 0.14f),
            Rose,
            "⚠ ${state.message.take(60)}"
        )
        is GenState.Completed -> Triple(
            Teal.copy(alpha = 0.14f),
            Teal,
            "+${state.added} queued"
        )
        GenState.Idle -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            ""
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        if (state is GenState.Running) {
            CircularProgressIndicator(
                strokeWidth = 1.6.dp,
                modifier = Modifier.size(12.dp),
                color = fg
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 190.dp)
        )
    }
}

@Composable
private fun EmptyQueueCard(
    llmConfigured: Boolean,
    genState: GenState,
    onOpenSettings: () -> Unit,
    onGenerate: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp)
    ) {
        when {
            !llmConfigured -> {
                Text("Welcome to Inuit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "An intuition trainer that asks about everything — and never tells you the answers.\n\n" +
                        "Connect an LLM to conjure your first batch of questions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenSettings) { Text("Open settings") }
            }

            genState is GenState.Running -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    genState.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            genState is GenState.Error -> {
                Text("Generation failed", color = Rose, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    genState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onGenerate) { Text("Retry") }
            }

            else -> {
                Text(
                    "The queue is empty",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onGenerate) { Text("Generate a batch") }
            }
        }
    }
}
