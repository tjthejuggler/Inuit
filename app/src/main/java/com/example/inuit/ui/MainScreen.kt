package com.example.inuit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inuit.data.gen.QuestionGenerator.GenState
import com.example.inuit.ui.theme.Indigo
import com.example.inuit.ui.theme.Rose
import com.example.inuit.ui.theme.Teal

/**
 * Main screen: a collapsible question card pinned on top; everything below
 * is the stats/knowledge-map scroll. Stats are session-frozen (blind
 * training): they refresh only when the user returns to the app.
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
    val collapsed by viewModel.questionCollapsed.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val queueSize by viewModel.queueSize.collectAsStateWithLifecycle()
    val podcast by viewModel.podcast.collectAsStateWithLifecycle()
    val podcastLoading by viewModel.podcastLoading.collectAsStateWithLifecycle()
    val podcastHistory by viewModel.podcastHistory.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                        Spacer(Modifier.width(12.dp))
                        GenerationStatusChip(genState, queueSize = queueSize)
                    }
                },
                actions = {
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
                    if (collapsed) {
                        CollapsedQuestionBar(
                            question = q,
                            onExpand = { viewModel.setCollapsed(false) }
                        )
                    } else {
                        Column {
                            QuestionCard(
                                question = q,
                                onSubmit = viewModel::submitAnswer,
                                onSkip = viewModel::skip
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedButton(onClick = { viewModel.setCollapsed(true) }) {
                                    Text("Collapse · browse stats")
                                }
                            }
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
                    // Retire + regenerate + open (feed resolved on demand).
                    onOpenPodcast = viewModel::onPodcastOpened,
                    onOpenHistoryPodcast = viewModel::onHistoryPodcastOpened,
                    onOpenSettings = onOpenSettings
                )
            }
        }
    }
}

@Composable
private fun GenerationStatusChip(state: GenState, queueSize: Int) {
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
            "$queueSize queued"
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
            maxLines = 1
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
