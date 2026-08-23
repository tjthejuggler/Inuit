package com.example.inuit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.inuit.data.KnowledgeSummary
import com.example.inuit.data.StatsCalculator
import com.example.inuit.ui.charts.BarChart
import com.example.inuit.ui.charts.LineChart
import com.example.inuit.ui.charts.ProficiencyBar
import com.example.inuit.ui.charts.ProficiencyRing
import com.example.inuit.ui.charts.RadarChart
import java.time.format.TextStyle
import java.util.Locale

/**
 * The whole stats panel rendered below the (collapsible) question card.
 *
 * BLIND-TRAINING NOTE: this snapshot is frozen for the duration of a
 * session — it reflects everything answered up to the moment the user
 * last entered the app, never the current session's answers.
 */
@Composable
fun StatsPanel(
    stats: StatsCalculator.Snapshot,
    summaries: List<KnowledgeSummary>,
    liveQueueSize: Int
) {
    OverviewChips(stats, liveQueueSize)
    if (stats.totalAnswers == 0) {
        EmptyStatsCard()
        return
    }
    KnowledgeMapCard(stats)
    ProficiencyCard(stats)
    WeakestStrongestCards(stats)
    DomainTreeCard(stats)
    ActivityCard(stats)
    TimeOfDayCard(stats)
    GrowthCard(stats)
    ProfileCard(stats)
    if (summaries.isNotEmpty()) KnowledgeStateCard(summaries)
}

// ── overview chips ────────────────────────────────────────────────────────

@Composable
private fun OverviewChips(stats: StatsCalculator.Snapshot, liveQueueSize: Int) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatChip("Answered", "${stats.totalAnswers}")
            StatChip("Accuracy", "${(stats.accuracy * 100).toInt()}%")
            StatChip("Streak", "${stats.streak}", highlight = stats.streak >= 3)
            StatChip("Best", "${stats.bestStreak}")
            StatChip("Realms", "${stats.domainsExplored}")
            StatChip("Topics", "${stats.distinctDomains}")
            StatChip("Queue", "$liveQueueSize")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Reflects every answer up to your last visit — this session stays unread.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun StatChip(label: String, value: String, highlight: Boolean = false) {
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (highlight) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyStatsCard() {
    SectionCard(title = "Your knowledge map") {
        Text(
            "Answer questions and your knowledge map will grow here — realms, " +
                "proficiency, trends and the shape of what you know.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── knowledge map (radar) ─────────────────────────────────────────────────

@Composable
private fun KnowledgeMapCard(stats: StatsCalculator.Snapshot) {
    val top = stats.topDomains.take(8)
    if (top.isEmpty()) return
    SectionCard(title = "Knowledge map", subtitle = "top-level realms by proficiency") {
        RadarChart(
            entries = top.map { it.path to it.accuracy },
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        )
        Spacer(Modifier.height(10.dp))
        top.forEach { d ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    d.path,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${d.correct}/${d.attempts}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                ProficiencyRing(progress = d.accuracy, sizeDp = 30.dp, stroke = 4.dp)
            }
        }
    }
}

// ── proficiency bars ──────────────────────────────────────────────────────

@Composable
private fun ProficiencyCard(stats: StatsCalculator.Snapshot) {
    val top = stats.topDomains.take(8)
    if (top.isEmpty()) return
    SectionCard(title = "Proficiency by realm") {
        top.forEach { d ->
            ProficiencyBar(
                label = d.path,
                accuracy = d.accuracy,
                caption = "${d.correct} of ${d.attempts} answered correctly",
                modifier = Modifier.padding(vertical = 5.dp)
            )
        }
    }
}

// ── weakest / strongest ───────────────────────────────────────────────────

@Composable
private fun WeakestStrongestCards(stats: StatsCalculator.Snapshot) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val weak = stats.weakest.take(5)
        if (weak.isNotEmpty()) {
            SectionCard(title = "Weakest areas", modifier = Modifier.weight(1f)) {
                weak.forEach { d ->
                    MiniDomainRow(d)
                }
                Text(
                    "New threads will lead here",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val strong = stats.strongest.take(5)
        if (strong.isNotEmpty()) {
            SectionCard(title = "Strongest areas", modifier = Modifier.weight(1f)) {
                strong.forEach { d -> MiniDomainRow(d) }
                Text(
                    "Expect deeper questions here",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MiniDomainRow(d: StatsCalculator.DomainAgg) {
    val short = d.path.split(" > ").last()
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(
            short,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(d.accuracy.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(com.example.inuit.ui.charts.proficiencyColor(d.accuracy))
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "${d.attempts}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── domain tree (grows in complexity over time) ───────────────────────────

@Composable
private fun DomainTreeCard(stats: StatsCalculator.Snapshot) {
    if (stats.domainTree.isEmpty()) return
    SectionCard(title = "Domain tree", subtitle = "tap a realm to unfold its branches") {
        val expanded = rememberSaveable { mutableStateOf(setOf<String>()) }
        stats.domainTree.forEach { node ->
            DomainTreeNode(node, depth = 0, expanded = expanded.value,
                onToggle = { path ->
                    expanded.value = if (path in expanded.value) expanded.value - path
                    else expanded.value + path
                })
        }
    }
}

@Composable
private fun DomainTreeNode(
    node: StatsCalculator.DomainNode,
    depth: Int,
    expanded: Set<String>,
    onToggle: (String) -> Unit
) {
    val isOpen = node.path in expanded
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = node.children.isNotEmpty()) { onToggle(node.path) }
            .padding(start = (depth * 18).dp, top = 6.dp, bottom = 6.dp, end = 4.dp)
    ) {
        if (node.children.isNotEmpty()) {
            Icon(
                if (isOpen) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(20.dp)
            )
        } else {
            Spacer(Modifier.width(20.dp))
        }
        Text(
            node.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${node.totalAttempts}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        ProficiencyRing(progress = node.totalAccuracy, sizeDp = 28.dp, stroke = 4.dp)
    }
    if (isOpen) {
        node.children.forEach { child ->
            DomainTreeNode(child, depth + 1, expanded, onToggle)
        }
    }
}

// ── activity & trends ─────────────────────────────────────────────────────

@Composable
private fun ActivityCard(stats: StatsCalculator.Snapshot) {
    SectionCard(title = "Activity", subtitle = "answers per day · last 14 days") {
        BarChart(
            values = stats.byDay.map { it.day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ROOT) to it.count },
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        )
        if (stats.accuracyTrend.size >= 2) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Accuracy trend",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LineChart(
                values = stats.accuracyTrend,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
            )
        }
    }
}

// ── time of day ────────────────────────────────────────────────────────────

/** "09" for a band starting at 9; "24" for the band ending the day. */
private fun bandLabel(startHour: Int): String =
    "%02d–%02d".format(startHour, startHour + 3)

@Composable
private fun TimeOfDayCard(stats: StatsCalculator.Snapshot) {
    if (stats.byHour.isEmpty() || stats.byHour.all { it.attempts == 0 }) return
    SectionCard(title = "Time of day", subtitle = "when you answer — and how sharp you are") {
        BarChart(
            values = stats.byHour.map { "%02d".format(it.hour) to it.attempts },
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        )
        Spacer(Modifier.height(4.dp))
        stats.peakHour?.let { peak ->
            Text(
                "Busiest hour ${"%02d".format(peak.hour)}:00 — ${peak.attempts} answers",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Accuracy by time of day",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        stats.byBand
            .filter { it.attempts > 0 }
            .forEach { band ->
                ProficiencyBar(
                    label = bandLabel(band.startHour),
                    accuracy = band.accuracy,
                    caption = "${band.attempts} answers" +
                        when (band) {
                            stats.sharpestBand -> " · your sharpest time"
                            stats.weakestBand -> " · your weakest time"
                            else -> ""
                        }
                )
                Spacer(Modifier.height(8.dp))
            }
        if (stats.sharpestBand != null && stats.weakestBand != null && stats.sharpestBand != stats.weakestBand) {
            Text(
                "You answer ${(stats.sharpestBand.accuracy * 100).toInt()}% correctly at " +
                    "${bandLabel(stats.sharpestBand.startHour)} but only " +
                    "${(stats.weakestBand.accuracy * 100).toInt()}% at " +
                    "${bandLabel(stats.weakestBand.startHour)}.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GrowthCard(stats: StatsCalculator.Snapshot) {
    if (stats.growth.size < 2 || stats.growth.last().count < 2) return
    SectionCard(title = "Knowledge space growth", subtitle = "distinct realms discovered") {
        LineChart(
            values = stats.growth.map { it.count.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
            color = MaterialTheme.colorScheme.tertiary,
            minY = 0f,
            maxY = maxOf(4f, stats.growth.maxOf { it.count.toFloat() })
        )
        Text(
            "Now exploring ${stats.growth.last().count} realms · ${stats.distinctDomains} topics",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── difficulty & type profile ─────────────────────────────────────────────

@Composable
private fun ProfileCard(stats: StatsCalculator.Snapshot) {
    if (stats.byDifficulty.all { it.attempts == 0 }) return
    SectionCard(title = "Your profile") {
        Text(
            "Accuracy by difficulty",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            stats.byDifficulty.forEach { d ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    ProficiencyRing(
                        progress = if (d.attempts == 0) 0f else d.correct.toFloat() / d.attempts,
                        sizeDp = 38.dp,
                        stroke = 5.dp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(d.label, style = MaterialTheme.typography.labelSmall)
                    Text(
                        "${d.attempts}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (stats.byType.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "By question type",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            stats.byType.forEach { t ->
                ProficiencyBar(
                    label = t.type.displayName,
                    accuracy = if (t.attempts == 0) 0f else t.correct.toFloat() / t.attempts,
                    caption = "${t.correct}/${t.attempts}",
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

// ── rolling knowledge state (LLM summaries) ───────────────────────────────

@Composable
private fun KnowledgeStateCard(summaries: List<KnowledgeSummary>) {
    SectionCard(title = "Knowledge state", subtitle = "rolling summaries of where you stand") {
        summaries.forEach { s ->
            Text(
                s.domain,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                s.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ── shared card shell ─────────────────────────────────────────────────────

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 0.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
