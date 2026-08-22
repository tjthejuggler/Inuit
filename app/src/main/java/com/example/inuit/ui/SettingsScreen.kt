package com.example.inuit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inuit.data.AppSettings
import com.example.inuit.data.DebugLog
import com.example.inuit.ui.theme.Amber
import com.example.inuit.ui.theme.LogErr
import com.example.inuit.ui.theme.LogOk

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val llmTest by viewModel.llmTest.collectAsStateWithLifecycle()
    val mcpTest by viewModel.mcpTest.collectAsStateWithLifecycle()

    // Local editable state, re-seeded whenever persisted settings change.
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var apiKey by rememberSaveable(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var model by rememberSaveable(settings.model) { mutableStateOf(settings.model) }
    var showKey by remember { mutableStateOf(false) }
    var temperature by remember(settings.temperature) { mutableFloatStateOf(settings.temperature) }
    var mcpJson by rememberSaveable(settings.mcpJson) { mutableStateOf(settings.mcpJson) }

    var batchSize by remember(settings.batchSize) { mutableIntStateOf(settings.batchSize) }
    var queueThreshold by remember(settings.queueThreshold) { mutableIntStateOf(settings.queueThreshold) }
    var minConfidence by remember(settings.minConfidence) { mutableFloatStateOf(settings.minConfidence) }
    var mcpBudget by remember(settings.mcpBudget) { mutableIntStateOf(settings.mcpBudget) }
    var verify by remember(settings.verifyEnabled) { mutableStateOf(settings.verifyEnabled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── LLM ───────────────────────────────────────────────────────
            SectionCard(title = "LLM", subtitle = "any OpenAI-compatible endpoint") {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model name") },
                    placeholder = { Text("e.g. glm-4.7, gpt-4o, llama-3.1-70b") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("Temperature: ${"%.2f".format(temperature)}", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..1.5f
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Disable deep thinking", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "GLM reasoning models: skip internal chains — much faster and cheaper",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.disableThinking,
                        onCheckedChange = { viewModel.setDisableThinking(it) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        viewModel.saveLlmSettings(baseUrl, apiKey, model, temperature)
                    }) { Text("Save") }
                    OutlinedButton(onClick = { viewModel.testLlm(baseUrl, apiKey, model) }) {
                        Text("Test")
                    }
                }
                TestResultView(llmTest)
            }

            // ── Generation ────────────────────────────────────────────────
            SectionCard(title = "Generation", subtitle = "how the question engine behaves") {
                LabeledSlider(
                    label = "Batch size",
                    value = batchSize.toFloat(),
                    range = 5f..60f,
                    display = { "${it.toInt()}" },
                    onChange = { batchSize = it.toInt() }
                )
                LabeledSlider(
                    label = "Refill queue when fewer than",
                    value = queueThreshold.toFloat(),
                    range = 10f..150f,
                    display = { "${it.toInt()}" },
                    onChange = { queueThreshold = it.toInt() }
                )
                LabeledSlider(
                    label = "Min confidence",
                    value = minConfidence,
                    range = 0.5f..1f,
                    display = { "%.2f".format(it) },
                    onChange = { minConfidence = it }
                )
                LabeledSlider(
                    label = "Web tool budget per batch",
                    value = mcpBudget.toFloat(),
                    range = 0f..10f,
                    display = { "${it.toInt()}" },
                    onChange = { mcpBudget = it.toInt() }
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Fact-check pass (verifier)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Second LLM call that drops suspicious questions",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = verify, onCheckedChange = { verify = it })
                }
                Button(onClick = {
                    viewModel.saveGenerationSettings(batchSize, queueThreshold, verify, minConfidence, mcpBudget)
                }) { Text("Save") }
            }

            // ── MCP ───────────────────────────────────────────────────────
            SectionCard(
                title = "MCP servers",
                subtitle = "streamable-http servers with url + headers (stdio is ignored on Android)"
            ) {
                OutlinedTextField(
                    value = mcpJson,
                    onValueChange = { mcpJson = it },
                    label = { Text("mcpServers JSON") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 8,
                    maxLines = 20,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { viewModel.saveMcpJson(mcpJson) }) { Text("Save") }
                    OutlinedButton(onClick = { viewModel.testMcp(mcpJson) }) { Text("Test") }
                }
                TestResultView(mcpTest)
                Text(
                    "Seeded with z.ai web search + web reader. Paste your own API key " +
                        "into the Authorization headers. Used (sparingly, per budget) to " +
                        "ground obscure statistics questions.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Diagnostics ───────────────────────────────────────────────
            DiagnosticsCard()
        }
    }
}

@Composable
private fun DiagnosticsCard() {
    val log by DebugLog.entries.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    SectionCard(
        title = "Diagnostics",
        subtitle = "engine event log — survives restarts; also in logcat (tag Inuit)"
    ) {
        val entries = log.asReversed() // newest first
        if (entries.isEmpty()) {
            Text(
                "No events yet.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                entries.take(300).forEach { e ->
                    Text(
                        text = DebugLog.formatEntry(e),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = when (e.level) {
                            DebugLog.ERROR -> LogErr
                            DebugLog.WARN -> Amber
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { clipboard.setText(AnnotatedString(DebugLog.asText())) }) {
                Text("Copy log")
            }
            OutlinedButton(onClick = { DebugLog.clear() }) { Text("Clear") }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onChange: (Float) -> Unit
) {
    Column {
        Text(
            "$label: ${display(value)}",
            style = MaterialTheme.typography.labelMedium
        )
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun TestResultView(result: MainViewModel.TestResult?) {
    when (result) {
        is MainViewModel.TestResult.Running -> {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(16.dp).width(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Testing…", style = MaterialTheme.typography.bodySmall)
            }
        }
        is MainViewModel.TestResult.Ok -> {
            Spacer(Modifier.height(8.dp))
            Text(
                "✓ ${result.message}",
                style = MaterialTheme.typography.bodySmall,
                color = com.example.inuit.ui.theme.LogOk,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
        is MainViewModel.TestResult.Fail -> {
            Spacer(Modifier.height(8.dp))
            Text(
                "✗ ${result.message}",
                style = MaterialTheme.typography.bodySmall,
                color = com.example.inuit.ui.theme.LogErr,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
        null -> {}
    }
}
