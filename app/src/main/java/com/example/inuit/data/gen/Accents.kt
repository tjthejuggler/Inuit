package com.example.inuit.data.gen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.example.inuit.data.AnswerRecord
import com.example.inuit.data.DebugLog
import com.example.inuit.data.DomainStat
import com.example.inuit.data.KnowledgeSummary
import com.example.inuit.data.Net
import com.example.inuit.data.NetStore
import com.example.inuit.data.Question
import com.example.inuit.data.QuestionStore
import com.example.inuit.data.TailIntegration
import com.example.inuit.data.TailTextEntry
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * OCCASIONAL ACCENTS — per-net options that lightly season question
 * generation without ever taking it over:
 *  - location: questions tied to where the phone currently is,
 *  - date: questions tied to today (this date in history, this year in
 *    past centuries, current season…),
 *  - cross-net: questions anchored in knowledge from OTHER nets the user
 *    picked as sources.
 *
 * Everything here produces at most a few context lines; [Prompts] wraps
 * them in a strict dosage block ("seasoning, not the meal") so the net's
 * own material always dominates the batch.
 */

/** The assembled accent context for one generation batch. */
data class NetAccents(
    /** e.g. "the user is currently near Milan, Italy" — null when off/unavailable. */
    val locationLine: String? = null,
    val dateLines: List<String> = emptyList(),
    val crossNetLines: List<String> = emptyList(),
    /** Life-log seeds: the user's own recent Tail text entries, one compact
     *  line per habit (see [TailTextAccents]). Empty when the net has the
     *  accent off, nothing is shared, or Tail is unavailable. */
    val tailTextLines: List<String> = emptyList()
) {
    val isEmpty: Boolean
        get() = locationLine == null && dateLines.isEmpty() &&
            crossNetLines.isEmpty() && tailTextLines.isEmpty()
}

/** Hard ceiling on accent questions per batch — a sprinkle, never a takeover. */
fun accentQuestionCap(batchSize: Int): Int = (batchSize / 6).coerceIn(1, 3)

// ── Date accent (pure — unit tested) ──────────────────────────────────────

object DateAccents {

    /**
     * Context lines about "today". The LLM supplies the actual history; we
     * only hand it stable calendar facts plus angles to explore.
     *
     * @param latitude rough latitude of the user (when the location accent
     *   is on) — used for the hemisphere-correct season line.
     */
    fun lines(today: LocalDate, latitude: Double? = null): List<String> {
        val out = ArrayList<String>(4)
        val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val monthName = today.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        out.add(
            "today is $weekday, ${today.dayOfMonth} $monthName ${today.year} " +
                "(day ${today.dayOfYear} of the year${if (today.isLeapYear) ", leap year" else ""})"
        )
        out.add(
            "on-this-day angle: \"$monthName ${today.dayOfMonth}\" — events, births and " +
                "deaths that happened on this exact calendar date in earlier years/centuries " +
                "(only long-settled historical facts)"
        )
        val years = ANNIVERSARY_OFFSETS
            .map { today.year - it }
            .filter { it > 0 }
            .take(ANNIVERSARY_COUNT)
        out.add(
            "same-year-in-history angle: ${today.year} in past centuries — " +
                years.joinToString(", ") { "$it (${it - today.year} years ago)" } +
                " (what was founded, invented, published or ruled then)"
        )
        if (latitude != null) {
            val hemisphere = if (latitude >= 0.0) "northern" else "southern"
            val season = seasonOf(today.monthValue, northern = latitude >= 0.0)
            out.add("the user's local season is $season ($hemisphere hemisphere)")
        }
        return out
    }

    /** Meteorological seasons; flipped for the southern hemisphere. */
    internal fun seasonOf(month: Int, northern: Boolean): String {
        val n = when (month) {
            12, 1, 2 -> "winter"
            3, 4, 5 -> "spring"
            6, 7, 8 -> "summer"
            else -> "autumn"
        }
        return if (northern) n else when (n) {
            "winter" -> "summer"
            "spring" -> "autumn"
            "summer" -> "winter"
            else -> "spring"
        }
    }

    private val ANNIVERSARY_OFFSETS = listOf(25, 50, 100, 150, 200, 250, 300, 400, 500, 750, 1000)
    private const val ANNIVERSARY_COUNT = 6
}

// ── Cross-net accent (renderer is pure — unit tested) ─────────────────────

object CrossNetAccents {

    /** One source net's distilled knowledge base. */
    data class Source(
        val name: String,
        val description: String,
        val summaries: List<KnowledgeSummary>,
        val stats: List<DomainStat>,
        val missedPrompts: List<String>
    )

    /**
     * One compact line per source net: what the user knows there (summaries),
     * where they struggle (weakest domains), what they recently missed.
     * Sources with no data at all are dropped — nothing to anchor on.
     */
    fun lines(sources: List<Source>): List<String> = sources.mapNotNull { s ->
        val parts = ArrayList<String>(3)
        if (s.summaries.isNotEmpty()) {
            parts.add(
                "knows: " + s.summaries
                    .sortedByDescending { it.createdAt }
                    .take(SUMMARY_SAMPLE)
                    .joinToString("; ") { "${it.domain}: ${it.text.replace('\n', ' ').trim().take(SUMMARY_CHARS)}" }
            )
        }
        val weak = s.stats.filter { it.attempts >= 3 }
            .sortedWith(compareBy<DomainStat> { it.accuracy }.thenBy { it.path })
            .take(WEAK_SAMPLE)
        if (weak.isNotEmpty()) {
            parts.add(
                "weak: " + weak.joinToString("; ") { "${it.path} (${it.correct}/${it.attempts})" }
            )
        }
        if (s.missedPrompts.isNotEmpty()) {
            parts.add("recently missed: " + s.missedPrompts.joinToString(" | ") { "\"${it.take(PROMPT_CHARS)}…\"" })
        }
        if (parts.isEmpty()) return@mapNotNull null
        val scope = if (s.description.isBlank()) "" else " — ${s.description.trim().take(80)}"
        "- NET \"${s.name}\"$scope: " + parts.joinToString(" · ")
    }

    /**
     * Prompts of the source net's recently answered-wrong questions (the
     * prompts are safe to share — answers never leave this function's input).
     */
    fun recentMissedPrompts(
        questions: List<Question>,
        answers: List<AnswerRecord>,
        limit: Int
    ): List<String> {
        val byId = questions.associateBy { it.id }
        val lastById = HashMap<String, AnswerRecord>()
        for (a in answers) lastById[a.questionId] = a // answers are chronological
        return answers.asReversed()
            .filter { !it.correct }
            .mapNotNull { byId[it.questionId] }
            .distinctBy { it.id }
            .take(limit)
            .map { it.prompt }
    }

    private const val SUMMARY_SAMPLE = 2
    private const val SUMMARY_CHARS = 200
    private const val WEAK_SAMPLE = 2
    private const val PROMPT_CHARS = 100
}

// ── Tail life-log accent (renderer is pure — unit tested) ──────────────────

/**
 * Renders the user's recent Tail text-log entries as compact accent lines.
 *
 * Dosage mirrors the other accents: at most [MAX_HABITS] habits, at most
 * [ENTRIES_PER_HABIT] entries each, every entry clipped to [ENTRY_CHARS].
 * The prompt wraps these in the same strict "seasoning, not the meal" block,
 * so the net's own material always dominates the batch.
 */
object TailTextAccents {

    /** Entries fetched from Tail per habit (Tail caps at 3 anyway). */
    private const val FETCH_LIMIT = 3

    /** Habits rendered into the prompt, even if more are selected. */
    const val MAX_HABITS = 3

    /** Entries shown per habit line. */
    const val ENTRIES_PER_HABIT = 2

    /** Characters per entry inside the line. */
    const val ENTRY_CHARS = 140

    /**
     * One line per selected habit that actually has entries:
     * `- LOG "Dreams": "flying over the bay" · "late for a train"`
     * Habits without entries (or not selected for this net) are dropped.
     */
    fun lines(entries: List<TailTextEntry>, selectedHabits: Collection<String>): List<String> {
        val selected = selectedHabits.toSet()
        return entries
            .groupBy { it.habitName }
            .filterKeys { it in selected }
            .entries
            .asSequence()
            .sortedBy { it.key } // stable, alphabetical — deterministic prompts
            .take(MAX_HABITS)
            .map { (habit, rows) ->
                val quoted = rows
                    .sortedByDescending { it.timestamp }
                    .take(ENTRIES_PER_HABIT)
                    .joinToString(" · ") { "\"${clip(it.text)}\"" }
                "- LOG \"$habit\": $quoted"
            }
            .toList()
    }

    private fun clip(text: String): String {
        val flat = text.replace('\n', ' ').trim()
        return if (flat.length <= ENTRY_CHARS) flat else flat.take(ENTRY_CHARS) + "…"
    }

    /** Per-habit fetch limit handed to Tail's provider. */
    val fetchLimit: Int get() = FETCH_LIMIT
}

// ── Location accent (Android side) ────────────────────────────────────────

/** Where the phone currently is, at region precision. */
data class Place(
    /** Human-readable, e.g. "Milan, Italy" or "~45.5°N, 9.2°E". */
    val description: String,
    val latitude: Double,
    val longitude: Double
)

/**
 * Reads the phone's last known location (coarse is plenty — the accent only
 * needs "which region is the user in"). No permission prompt can happen
 * here; the net-edit dialog requests the permission when the toggle flips
 * on. Every failure degrades to null and the accent is silently skipped.
 * Must be called off the main thread (geocoding does I/O).
 */
class LocationProvider(private val context: Context) {

    fun currentPlace(): Place? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val best = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).asSequence()
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null } catch (_: IllegalArgumentException) { null } }
            .maxByOrNull { it.time }
            ?: return null
        if (System.currentTimeMillis() - best.time > MAX_AGE_MS) return null
        return Place(
            description = describe(best),
            latitude = best.latitude,
            longitude = best.longitude
        )
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Region-level description: reverse-geocoded place when available,
     *  rounded coordinates as a privacy-preserving fallback. */
    private fun describe(loc: Location): String {
        val geocoded = try {
            @Suppress("DEPRECATION") // sync variant still works on 33+; we're off-main
            Geocoder(context).getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()
        } catch (_: Exception) {
            null
        }
        if (geocoded != null) {
            val place = geocoded.locality ?: geocoded.subAdminArea ?: geocoded.adminArea
            if (!place.isNullOrBlank()) {
                return if (!geocoded.countryName.isNullOrBlank()) "$place, ${geocoded.countryName}" else place
            }
        }
        val lat = String.format(Locale.US, "%.1f°%s", Math.abs(loc.latitude), if (loc.latitude >= 0) "N" else "S")
        val lon = String.format(Locale.US, "%.1f°%s", Math.abs(loc.longitude), if (loc.longitude >= 0) "E" else "W")
        return "~$lat, $lon"
    }

    companion object {
        /** Older than this → stale, skip the accent entirely. */
        private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}

// ── Builder: assembles the accents for one generation ─────────────────────

/**
 * Collects the accent context for the ACTIVE net right before a batch is
 * generated. Pure renderers above keep this testable; only [LocationProvider]
 * touches Android.
 */
class AccentsBuilder(
    private val locationProvider: LocationProvider,
    private val netStore: NetStore,
    private val store: QuestionStore,
    /** Tail bridge; null keeps the life-log accent permanently off. */
    private val tail: TailIntegration? = null
) {

    suspend fun build(net: Net): NetAccents {
        // Location doubles as the hemisphere hint for the date accent.
        val place = if (net.locationEnabled) locationProvider.currentPlace() else null
        val locationLine = place?.let { "the user is currently near ${it.description}" }
        val dateLines = if (net.dateEnabled) DateAccents.lines(LocalDate.now(), place?.latitude) else emptyList()
        val crossNetLines = if (net.sourceNetIds.isEmpty()) emptyList() else crossNetLines(net)
        val tailTextLines =
            if (net.tailTextEnabled && net.tailTextHabits.isNotEmpty()) tailTextLines(net) else emptyList()
        return NetAccents(locationLine, dateLines, crossNetLines, tailTextLines)
    }

    /**
     * Pulls the recent shared entries from Tail and renders them through the
     * pure [TailTextAccents] renderer. Any provider failure just drops the
     * accent for this batch — generation itself must never fail because of
     * the bridge.
     */
    private suspend fun tailTextLines(net: Net): List<String> = try {
        val entries = tail?.fetchRecentTextEntries(TailTextAccents.fetchLimit) ?: emptyList()
        TailTextAccents.lines(entries, net.tailTextHabits)
    } catch (e: Exception) {
        DebugLog.w("Accents", "tail text accent failed (continuing without): ${e.message}")
        emptyList()
    }

    private fun crossNetLines(net: Net): List<String> {
        val known = netStore.nets.value
        val sources = net.sourceNetIds.asSequence()
            .mapNotNull { id -> known.firstOrNull { it.id == id } }
            .filter { it.id != net.id }
            .take(MAX_SOURCES)
            .toList()
        if (sources.isEmpty()) return emptyList()
        return CrossNetAccents.lines(sources.map { src ->
            CrossNetAccents.Source(
                name = src.name,
                description = src.description,
                summaries = store.snapshotSummariesFor(src.id),
                stats = store.snapshotDomainStatsFor(src.id),
                missedPrompts = CrossNetAccents.recentMissedPrompts(
                    store.snapshotQuestionsFor(src.id),
                    store.snapshotAnswersFor(src.id),
                    MISSED_SAMPLE
                )
            )
        })
    }

    companion object {
        private const val MAX_SOURCES = 3
        private const val MISSED_SAMPLE = 2
    }
}
