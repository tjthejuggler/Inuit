package com.example.inuit.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/** Proficiency color: red → amber → green. */
fun proficiencyColor(t: Float): Color {
    val c = t.coerceIn(0f, 1f)
    return when {
        c < 0.5f -> lerp(ProfLowC, ProfMidC, c / 0.5f)
        else -> lerp(ProfMidC, ProfHighC, (c - 0.5f) / 0.5f)
    }
}

private val ProfLowC = Color(0xFFE15759)
private val ProfMidC = Color(0xFFF5A623)
private val ProfHighC = Color(0xFF3FCF8E)

private fun lerp(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f
)

// ── Radar (knowledge map of top-level realms) ─────────────────────────────

@Composable
fun RadarChart(
    entries: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.outline
) {
    if (entries.isEmpty()) return
    val n = max(entries.size, 3)
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#9AA3BC")
        textSize = 11.sp.value
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    Canvas(modifier = modifier.fillMaxWidth()) {
        val cx = size.width / 2
        val cy = size.height / 2 + 6f
        val radius = min(cx, cy) * 0.68f
        val angleStep = 2.0 * Math.PI / n
        val startAngle = -Math.PI / 2

        // rings
        for (ring in 1..4) {
            val r = radius * ring / 4f
            val path = Path()
            for (i in 0 until n) {
                val a = startAngle + i * angleStep
                val p = Offset(cx + (r * kotlin.math.cos(a)).toFloat(), cy + (r * kotlin.math.sin(a)).toFloat())
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            drawPath(path, lineColor.copy(alpha = 0.35f), style = Stroke(1.2f))
        }
        // spokes + labels
        for (i in 0 until n) {
            val a = startAngle + i * angleStep
            val edge = Offset(cx + (radius * kotlin.math.cos(a)).toFloat(), cy + (radius * kotlin.math.sin(a)).toFloat())
            drawLine(lineColor.copy(alpha = 0.3f), Offset(cx, cy), edge, 1f)
        }
        // data polygon
        val data = Path()
        for (i in 0 until n) {
            val v = entries[i % entries.size].second.coerceIn(0f, 1f)
            val r = radius * (0.12f + 0.88f * v)
            val a = startAngle + i * angleStep
            val p = Offset(cx + (r * kotlin.math.cos(a)).toFloat(), cy + (r * kotlin.math.sin(a)).toFloat())
            if (i == 0) data.moveTo(p.x, p.y) else data.lineTo(p.x, p.y)
        }
        data.close()
        drawPath(data, Color(0xFF8FA7FF).copy(alpha = 0.22f))
        drawPath(data, Color(0xFF8FA7FF), style = Stroke(2.4f))
        // vertex dots
        for (i in 0 until n) {
            val v = entries[i % entries.size].second.coerceIn(0f, 1f)
            val r = radius * (0.12f + 0.88f * v)
            val a = startAngle + i * angleStep
            drawCircle(Color(0xFF5ED4C8), 4.5f, Offset(cx + (r * kotlin.math.cos(a)).toFloat(), cy + (r * kotlin.math.sin(a)).toFloat()))
        }
        // labels
        drawIntoCanvasLabels(entries, cx, cy, radius, startAngle, angleStep, labelPaint)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIntoCanvasLabels(
    entries: List<Pair<String, Float>>,
    cx: Float,
    cy: Float,
    radius: Float,
    startAngle: Double,
    angleStep: Double,
    paint: android.graphics.Paint
) {
    val canvas = drawContext.canvas
    for (i in entries.indices) {
        val a = startAngle + i * angleStep
        val lx = cx + (radius * 1.22 * kotlin.math.cos(a)).toFloat()
        val ly = cy + (radius * 1.22 * kotlin.math.sin(a)).toFloat()
        val label = entries[i].first.take(14)
        canvas.nativeCanvas.drawText(label, lx, ly + 4f, paint)
    }
}

// ── Horizontal proficiency bar ────────────────────────────────────────────

@Composable
fun ProficiencyBar(
    label: String,
    accuracy: Float,
    caption: String,
    modifier: Modifier = Modifier
) {
    val barColor = proficiencyColor(accuracy)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${(accuracy * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = barColor
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF232C45))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(accuracy.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(barColor.copy(alpha = 0.6f), barColor)
                        )
                    )
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Line chart (trends) ───────────────────────────────────────────────────

@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF5ED4C8),
    minY: Float = 0f,
    maxY: Float = 1f
) {
    Canvas(modifier = modifier.fillMaxWidth()) {
        if (values.size < 2) return@Canvas
        val pad = 8f
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val lo = minY
        val hi = maxY
        fun y(v: Float): Float = pad + h * (1f - (v.coerceIn(lo, hi) - lo) / (hi - lo))
        // grid
        for (g in 1..3) {
            val gy = pad + h * g / 4f
            drawLine(Color(0xFF232C45), Offset(pad, gy), Offset(pad + w, gy), 1f)
        }
        val stepX = w / (values.size - 1)
        val path = Path()
        val fill = Path()
        values.forEachIndexed { i, v ->
            val x = pad + i * stepX
            val yy = y(v)
            if (i == 0) {
                path.moveTo(x, yy); fill.moveTo(x, pad + h); fill.lineTo(x, yy)
            } else {
                path.lineTo(x, yy); fill.lineTo(x, yy)
            }
        }
        fill.lineTo(pad + w, pad + h)
        fill.close()
        drawPath(fill, color.copy(alpha = 0.15f))
        drawPath(path, color, style = Stroke(2.2f, cap = StrokeCap.Round))
        // last point marker
        drawCircle(color, 5f, Offset(pad + w, y(values.last())))
    }
}

// ── Bar chart (activity per day) ──────────────────────────────────────────

@Composable
fun BarChart(
    values: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
    barColor: Color = Color(0xFF8FA7FF)
) {
    if (values.isEmpty()) return
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#5E6884")
        textSize = 9.sp.value
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    Canvas(modifier = modifier.fillMaxWidth()) {
        val pad = 10f
        val labelH = 16f
        val w = size.width - pad * 2
        val h = size.height - pad - labelH
        val maxV = values.maxOf { it.second }.coerceAtLeast(1)
        val slot = w / values.size
        val barW = (slot * 0.55f).coerceAtMost(26f)
        values.forEachIndexed { i, (label, v) ->
            val x = pad + slot * i + (slot - barW) / 2f
            val barH = if (v == 0) 2f else h * v / maxV
            drawRoundRect(
                color = if (v == 0) Color(0xFF232C45) else barColor.copy(alpha = 0.85f),
                topLeft = Offset(x, pad + h - barH),
                size = Size(barW, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )
            if (i % 2 == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    label, x + barW / 2f, size.height - 2f, labelPaint
                )
            }
        }
    }
}

// ── Ring (single proficiency gauge) ───────────────────────────────────────

@Composable
fun ProficiencyRing(
    progress: Float,
    modifier: Modifier = Modifier,
    stroke: Dp = 5.dp,
    sizeDp: Dp = 34.dp,
    trackColor: Color = Color(0xFF232C45)
) {
    val color = proficiencyColor(progress)
    Box(modifier = modifier.size(sizeDp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(sizeDp)) {
            val s = this.size.minDimension
            val inset = stroke.toPx() / 2
            val arcSize = Size(s - stroke.toPx(), s - stroke.toPx())
            drawArc(
                trackColor, 0f, 360f, false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(stroke.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color, -90f, 360f * progress.coerceIn(0f, 1f), false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(stroke.toPx(), cap = StrokeCap.Round)
            )
        }
        Text(
            "${(progress * 100).toInt()}",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
