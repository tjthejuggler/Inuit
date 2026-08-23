package com.example.inuit.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inuit.data.StatsCalculator
import com.example.inuit.ui.charts.ProficiencyBar
import com.example.inuit.ui.charts.ProficiencyRing
import com.example.inuit.ui.charts.proficiencyColor
import com.example.inuit.ui.theme.ProfHigh
import com.example.inuit.ui.theme.ProfLow
import com.example.inuit.ui.theme.ProfMid
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The full-screen interactive knowledge map — the stats radar evolved into
 * a explorable galaxy. FORCED LANDSCAPE: the wider canvas fits the whole
 * realm taxonomy at once.
 *
 * The map (left): every section of [com.example.inuit.data.gen.RealmTaxonomy]
 * spirals outward from the center; its realms cluster around it and every
 * territory (subgroup) orbits its realm. Charters glow with proficiency
 * colors and grow with answer volume; uncharted land stays dim. Pan with
 * one finger, pinch to zoom, double-tap to zoom in, tap any node to inspect
 * it. The panel (right) drills down: section → realm → territories.
 *
 * Blind-training invariant: everything shown derives from the session-frozen
 * [StatsCalculator.Snapshot] — aggregates only, never per-question verdicts.
 */
@Composable
fun KnowledgeMapScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    ForceLandscape()
    KnowledgeMapContent(stats = stats, onBack = onBack)
}

/** Locks the activity to sensor-landscape while this screen is shown. */
@Composable
private fun ForceLandscape() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val original = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = original }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeMapContent(stats: StatsCalculator.Snapshot, onBack: () -> Unit) {
    val landscape = remember(stats) { buildLandscape(stats) }
    var filterOrdinal by rememberSaveable { mutableStateOf(0) }
    val filter = LandscapeFilter.entries[filterOrdinal.coerceIn(LandscapeFilter.entries.indices)]
    val model = remember(landscape, filter) {
        buildMapModel(filterLandscape(landscape, "", filter))
    }
    var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedNode = selectedPath?.let { model.byPath[it] }

    val allRealms = landscape.flatMap { it.realms }
    val chartedRealms = allRealms.count { it.charted }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Knowledge map", fontWeight = FontWeight.Bold)
                        Text(
                            "$chartedRealms of ${allRealms.size} realms charted · pinch to zoom, tap to inspect",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
        Row(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            // ── the map (canvas + overlays live together) ─────────────────
            MapCanvas(
                model = model,
                selectedNode = selectedNode,
                onTapNode = { selectedPath = it },
                modifier = Modifier
                    .weight(0.62f)
                    .fillMaxHeight()
            )

            // ── the drill-down panel ───────────────────────────────────────
            Column(
                Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LandscapeFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = {
                                filterOrdinal = f.ordinal
                                selectedPath = null
                            },
                            label = { Text(f.label) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (selectedNode?.kind) {
                    null -> OverviewBody(landscape, onSelect = { selectedPath = it })
                    MapNodeKind.SECTION -> SectionBody(model, selectedNode, onSelect = { selectedPath = it })
                    MapNodeKind.REALM -> RealmBody(model, selectedNode, onSelect = { selectedPath = it })
                    MapNodeKind.TERRITORY -> TerritoryBody(model, selectedNode, onSelect = { selectedPath = it })
                }
            }
        }
    }
}

// ── the map canvas ─────────────────────────────────────────────────────────

@Composable
private fun MapCanvas(
    model: MapModel,
    selectedNode: MapNode?,
    onTapNode: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    /**
     * Opening view: comfortably zoomed in (labels readable, no clutter),
     * centered on the user's most-answered realm — their "home" region —
     * or the first section when nothing is charted yet.
     */
    fun resetView() {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return
        scale = 0.65f
        val home = model.nodes
            .filter { it.kind == MapNodeKind.REALM && it.charted }
            .maxByOrNull { it.attempts }
            ?: model.nodes.firstOrNull { it.kind == MapNodeKind.SECTION }
        offset = Offset(
            canvasSize.width / 2f - (home?.x ?: 0f) * scale,
            canvasSize.height / 2f - (home?.y ?: 0f) * scale
        )
    }

    /** ⤢ — the explicit zoom-all overview. */
    fun fitAll() {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return
        val bw = (model.maxX - model.minX).coerceAtLeast(1f)
        val bh = (model.maxY - model.minY).coerceAtLeast(1f)
        scale = min(canvasSize.width / bw, canvasSize.height / bh) * 0.92f
        offset = Offset(
            canvasSize.width / 2f - (model.minX + model.maxX) / 2f * scale,
            canvasSize.height / 2f - (model.minY + model.maxY) / 2f * scale
        )
    }

    fun zoomAbout(point: Offset, factor: Float) {
        val ns = (scale * factor).coerceIn(0.12f, 5f)
        offset = (offset - point) * (ns / scale) + point
        scale = ns
    }

    LaunchedEffect(canvasSize, model) { resetView() }

    fun hitTest(screen: Offset): MapNode? {
        val world = (screen - offset) / scale
        var best: MapNode? = null
        var bestScore = Float.MAX_VALUE
        for (n in model.nodes) {
            val d = hypot(world.x - n.x, world.y - n.y)
            val reach = n.radius + 28f / scale
            if (d <= reach) {
                val score = d - n.radius
                if (score < bestScore) {
                    bestScore = score
                    best = n
                }
            }
        }
        return best
    }

    val colorScheme = MaterialTheme.colorScheme
    val tints = listOf(colorScheme.primary, colorScheme.secondary, colorScheme.tertiary)

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(model) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(0.12f, 5f)
                        val c = Offset(size.width / 2f, size.height / 2f)
                        offset = (offset - c) * (ns / scale) + c + pan
                        scale = ns
                    }
                }
                .pointerInput(model) {
                    detectTapGestures(
                        onTap = { p -> onTapNode(hitTest(p)?.path) },
                        onDoubleTap = { p -> zoomAbout(p, 1.8f) }
                    )
                }
        ) {
        val s = scale
        fun w2s(wx: Float, wy: Float) = Offset(wx * s + offset.x, wy * s + offset.y)

        // graticule rings — a sense of scale and depth
        val maxR = max(hypot(model.maxX, model.maxY), hypot(model.minX, model.minY))
        var ring = 500f
        val center = w2s(0f, 0f)
        while (ring < maxR) {
            drawCircle(
                colorScheme.outlineVariant.copy(alpha = 0.10f),
                radius = ring * s,
                center = center,
                style = Stroke(1f)
            )
            ring += 500f
        }

        // edges: section → realm (always), realm → territory (zoomed in)
        for (n in model.nodes) {
            if (n.parent == null) continue
            val parent = model.byPath[n.parent] ?: continue
            if (n.kind == MapNodeKind.TERRITORY && s < 0.5f) continue
            val tint = tints[n.tintIndex % tints.size]
            drawLine(
                color = tint.copy(alpha = if (n.kind == MapNodeKind.TERRITORY) 0.10f else 0.16f),
                start = w2s(parent.x, parent.y),
                end = w2s(n.x, n.y),
                strokeWidth = 1f
            )
        }

        // nodes
        for (n in model.nodes) {
            val p = w2s(n.x, n.y)
            val r = n.radius * s
            val tint = tints[n.tintIndex % tints.size]
            when (n.kind) {
                MapNodeKind.SECTION -> {
                    drawCircle(tint.copy(alpha = 0.14f), radius = r, center = p)
                    drawCircle(tint, radius = r, center = p, style = Stroke(2f))
                    if (n.charted) {
                        val ar = max(r, 7f) + 5f
                        drawArc(
                            color = tint,
                            startAngle = -90f,
                            sweepAngle = 360f * n.accuracy,
                            useCenter = false,
                            topLeft = Offset(p.x - ar, p.y - ar),
                            size = Size(ar * 2f, ar * 2f),
                            style = Stroke(3f)
                        )
                    }
                }

                MapNodeKind.REALM -> {
                    val rr = max(r, 4f)
                    if (n.charted) {
                        drawCircle(proficiencyColor(n.accuracy).copy(alpha = 0.90f), radius = rr, center = p)
                        drawCircle(
                            colorScheme.onSurface.copy(alpha = 0.20f),
                            radius = rr, center = p, style = Stroke(1f)
                        )
                    } else {
                        drawCircle(colorScheme.surfaceVariant.copy(alpha = 0.45f), radius = rr, center = p)
                        drawCircle(
                            colorScheme.outlineVariant,
                            radius = rr, center = p, style = Stroke(1f)
                        )
                    }
                }

                MapNodeKind.TERRITORY -> {
                    val rr = max(r, 2.5f)
                    if (n.charted) {
                        drawCircle(proficiencyColor(n.accuracy).copy(alpha = 0.85f), radius = rr, center = p)
                    } else {
                        drawCircle(
                            colorScheme.outlineVariant.copy(alpha = 0.7f),
                            radius = rr, center = p, style = Stroke(1.2f)
                        )
                    }
                }
            }
        }

        // selection halo
        selectedNode?.let { n ->
            drawCircle(
                colorScheme.primary,
                radius = max(n.radius * s, 5f) + 7f,
                center = w2s(n.x, n.y),
                style = Stroke(2.5f)
            )
        }

        // labels — level-of-detail + collision avoidance: zooming OUT makes
        // text disappear instead of piling on top of itself. The selected
        // node's label always qualifies; otherwise each kind has its own
        // zoom threshold, and a label is only drawn if its rectangle fits
        // without overlapping an already-placed one.
        val sectionLabelPaint = android.graphics.Paint().apply {
            color = colorScheme.onSurface.toArgb()
            textSize = 11.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        val realmLabelPaint = android.graphics.Paint().apply {
            color = colorScheme.onSurfaceVariant.toArgb()
            textSize = 9.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val territoryLabelPaint = android.graphics.Paint().apply {
            color = colorScheme.onSurfaceVariant.toArgb()
            textSize = 8.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }

        class Label(
            val node: MapNode,
            val text: String,
            val paint: android.graphics.Paint,
            val cx: Float,
            val cy: Float
        )

        val labels = ArrayList<Label>(48)
        fun addLabel(n: MapNode, text: String, paint: android.graphics.Paint, cx: Float, cy: Float) {
            // cheap cull: anchors far off-screen need no label
            if (cx < -80f || cx > size.width + 80f || cy < -40f || cy > size.height + 40f) return
            labels.add(Label(n, text, paint, cx, cy))
        }

        for (n in model.nodes) {
            val sel = n.path == selectedNode?.path
            val p = w2s(n.x, n.y)
            when (n.kind) {
                MapNodeKind.SECTION -> if (s >= 0.30f || sel) {
                    val len = hypot(n.x, n.y)
                    if (len >= 1f) {
                        addLabel(
                            n, n.name.uppercase().take(18), sectionLabelPaint,
                            p.x + (n.x / len) * (n.radius * s + 16f),
                            p.y + (n.y / len) * (n.radius * s + 16f) + 4f
                        )
                    }
                }

                MapNodeKind.REALM -> if (s >= 0.75f || sel) {
                    addLabel(
                        n, n.name.take(16), realmLabelPaint,
                        p.x, p.y + max(n.radius * s, 4f) + 12f
                    )
                }

                MapNodeKind.TERRITORY -> if (s >= 1.8f || sel) {
                    addLabel(
                        n, n.name.take(20), territoryLabelPaint,
                        p.x, p.y + max(n.radius * s, 2.5f) + 10f
                    )
                }
            }
        }

        // the selected label claims space first, then the most-answered
        labels.sortByDescending {
            (if (it.node.path == selectedNode?.path) 1_000_000 else 0) + it.node.attempts
        }
        val placed = ArrayList<Rect>(48)
        val canvas = drawContext.canvas
        for (l in labels) {
            val w = l.paint.measureText(l.text)
            val h = l.paint.textSize
            val rect = Rect(l.cx - w / 2f - 3f, l.cy - h, l.cx + w / 2f + 3f, l.cy + 3f)
            if (placed.any { it.overlaps(rect) }) continue
            placed.add(rect)
            canvas.nativeCanvas.drawText(l.text, l.cx, l.cy, l.paint)
        }
        }

        // overlays share the gesture state: legend + zoom / fit controls
        Legend(Modifier.align(Alignment.BottomStart))
        val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
        Column(
            Modifier.align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MapButton("+") { zoomAbout(center, 1.45f) }
            MapButton("−") { zoomAbout(center, 1f / 1.45f) }
            MapButton("⤢") { fitAll() }
        }
    }
}

// ── overlays ───────────────────────────────────────────────────────────────

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            "proficiency",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .size(width = 72.dp, height = 6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(ProfLow, ProfMid, ProfHigh)))
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "dim = uncharted · size = answers",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MapButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── drill-down panel bodies ────────────────────────────────────────────────

@Composable
private fun OverviewBody(
    landscape: List<LandscapeSection>,
    onSelect: (String?) -> Unit
) {
    val allRealms = landscape.flatMap { it.realms }
    SectionLabel("How to read the map")
    Text(
        "Colored rings are realm groups; filled circles are realms; the small " +
            "dots orbiting them are territories. Pan with a finger, pinch or " +
            "double-tap to zoom, tap anything to inspect it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(14.dp))

    val mostExplored = allRealms.filter { it.charted }
        .sortedByDescending { it.attempts }.take(6)
    if (mostExplored.isNotEmpty()) {
        SectionLabel("Most explored")
        mostExplored.forEach { RealmRow(it, onClick = { onSelect(it.path) }) }
        Spacer(Modifier.height(10.dp))
    }

    val uncharted = allRealms.filter { !it.charted }.take(4)
    if (uncharted.isNotEmpty()) {
        SectionLabel("Uncharted land")
        uncharted.forEach { RealmRow(it, onClick = { onSelect(it.path) }) }
    }
}

@Composable
private fun SectionBody(model: MapModel, node: MapNode, onSelect: (String?) -> Unit) {
    val section = model.sections[node.path] ?: return
    Text(
        section.name,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        "${section.chartedRealms}/${section.realms.size} realms charted · ${section.attempts} answers",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))
    section.realms.forEach { RealmRow(it, onClick = { onSelect(it.path) }) }
}

@Composable
private fun RealmBody(model: MapModel, node: MapNode, onSelect: (String?) -> Unit) {
    val realm = model.realms[node.path] ?: return
    Text(
        realm.name,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        "in ${realm.section}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))
    if (realm.charted) {
        ProficiencyBar(
            label = "Proficiency",
            accuracy = realm.accuracy,
            caption = "${realm.attempts} answers · ${realm.chartedSubgroups}/${realm.subgroups.size} territories charted"
        )
    } else {
        Text(
            "Uncharted — no questions here yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(12.dp))
    SectionLabel("Territories (${realm.chartedSubgroups}/${realm.subgroups.size})")
    realm.subgroups.forEach { sub ->
        if (sub.charted) {
            ProficiencyBar(
                label = sub.name,
                accuracy = sub.accuracy,
                caption = "${sub.attempts} answers",
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .alpha(0.55f)
            ) {
                Text(
                    sub.name,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "uncharted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TerritoryBody(model: MapModel, node: MapNode, onSelect: (String?) -> Unit) {
    val parentRealm = node.parent?.let { model.realms[it] }
    if (parentRealm != null) {
        Text(
            "‹ ${parentRealm.name}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onSelect(parentRealm.path) }
                .padding(vertical = 4.dp)
        )
        Spacer(Modifier.height(4.dp))
    }
    Text(
        node.name,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        parentRealm?.let { "${it.section} › ${it.name}" } ?: "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))
    if (node.charted) {
        ProficiencyBar(
            label = "Proficiency",
            accuracy = node.accuracy,
            caption = "${node.attempts} answers"
        )
    } else {
        Text(
            "Still uncharted — the trainer will lead questions here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RealmRow(realm: LandscapeRealm, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 5.dp)
    ) {
        ProficiencyRing(
            progress = if (realm.charted) realm.accuracy else 0f,
            sizeDp = 30.dp,
            stroke = 4.dp
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                realm.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (realm.charted) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (realm.charted)
                    "${realm.chartedSubgroups}/${realm.subgroups.size} territories · ${realm.attempts} answers"
                else "uncharted · ${realm.subgroups.size} territories",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
}
