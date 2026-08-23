package com.example.inuit.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A habit fetched from the Tail app's Content Provider.
 *
 * @param habitId   Stable identifier sent as an Intent extra (Tail uses the habit name).
 * @param habitName Human-readable display name shown in the Settings picker.
 */
data class HabitEntry(
    val habitId: String,
    val habitName: String
)

/**
 * Aggregates answers into per-date counts (`yyyy-MM-dd` → number answered that
 * day) using [zone], matching how Tail's own receiver buckets days via
 * `LocalDate.now()`. Pure and JVM-testable.
 */
fun aggregateAnswersByDate(answers: List<AnswerRecord>, zone: ZoneId): Map<String, Int> {
    val byDate = LinkedHashMap<String, Int>()
    for (a in answers) {
        val date = Instant.ofEpochMilli(a.timestamp).atZone(zone).toLocalDate().toString()
        byDate[date] = (byDate[date] ?: 0) + 1
    }
    return byDate
}

/**
 * Aggregates answers into per-date answer TIMES (`yyyy-MM-dd` → sorted list of
 * `HH:mm:ss` strings, one per answered question) using [zone]. Same bucketing
 * as [aggregateAnswersByDate], so the counts and times payloads always cover
 * identical dates. Pure and JVM-testable.
 */
fun aggregateAnswerTimesByDate(answers: List<AnswerRecord>, zone: ZoneId): Map<String, List<String>> {
    val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    val byDate = LinkedHashMap<String, MutableList<String>>()
    for (a in answers) {
        val zdt = Instant.ofEpochMilli(a.timestamp).atZone(zone)
        byDate.getOrPut(zdt.toLocalDate().toString()) { mutableListOf() }
            .add(zdt.toLocalTime().format(fmt))
    }
    return byDate.mapValues { (_, times) -> times.sorted() }
}

/**
 * IPC integration with the Tail habit-tracking app (same protocol as WAGS).
 *
 * Inuit reports a SINGLE count-based habit: **questions answered**.
 *  • Live: every recorded answer fires a +1 increment broadcast
 *    ([sendQuestionsIncrement]) — no `EXTRA_MINUTES`, so Tail applies its
 *    default count increment of 1.
 *  • Backfill: per-date answer counts (everything from the past AND today so
 *    far) are **SET** (replaced) in Tail via [ACTION_SET_HABIT_VALUES] —
 *    idempotent, so re-running always converges to the true history.
 *
 * The broadcasts are explicit (target package set, required on API 26+) and
 * permission-guarded via the signature permission only Tail defines, so
 * exclusively same-keystore apps can talk to it.
 */
class TailIntegration(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Content Provider query ────────────────────────────────────────────

    /**
     * Queries Tail's Content Provider and returns its habits in user-defined
     * screen order. Empty list if Tail is not installed or denies the query.
     */
    suspend fun fetchHabits(): List<HabitEntry> = withContext(Dispatchers.IO) {
        val results = mutableListOf<HabitEntry>()
        try {
            appContext.contentResolver.query(
                /* uri        */ HABITS_CONTENT_URI,
                /* projection */ arrayOf(COL_HABIT_NAME),
                /* selection  */ null,
                /* selArgs    */ null,
                /* sortOrder  */ null // Tail returns habits in screen order
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(COL_HABIT_NAME)
                while (cursor.moveToNext()) {
                    // Use the habit name as the ID — Tail's receiver accepts a
                    // name for EXTRA_HABIT_ID and it is stable across reorders.
                    val name = cursor.getString(nameIdx)
                    results += HabitEntry(habitId = name, habitName = name)
                }
            }
        } catch (e: Exception) {
            // Provider not installed, permission denied, or column mismatch — fail softly.
            DebugLog.w("Tail", "fetchHabits: could not query Tail app — ${e.message}")
        }
        results
    }

    // ── Selection persistence (single slot) ───────────────────────────────

    /** The persisted habit id for the questions-answered slot, or "" if none. */
    fun getHabitId(): String = prefs.getString(KEY_HABIT_ID, "") ?: ""

    /** The persisted habit display name, or "" if none. */
    fun getHabitName(): String = prefs.getString(KEY_HABIT_NAME, "") ?: ""

    /** True when a Tail habit is connected to the questions-answered slot. */
    val habitSelected: Boolean get() = getHabitId().isNotBlank()

    /** Persists the selected habit for the questions-answered slot. */
    fun setHabit(entry: HabitEntry) {
        prefs.edit()
            .putString(KEY_HABIT_ID, entry.habitId)
            .putString(KEY_HABIT_NAME, entry.habitName)
            .apply()
    }

    /** Clears the selected habit. */
    fun clearHabit() {
        prefs.edit()
            .putString(KEY_HABIT_ID, "")
            .putString(KEY_HABIT_NAME, "")
            .apply()
    }

    // ── Live increment ────────────────────────────────────────────────────

    /**
     * Fires a +1 count increment for the connected habit — called once for
     * every answer the user submits. [answerTimestampMs] is the exact moment
     * the answer was given; Tail stamps its schedule-timeline timestamp at
     * THAT moment (protocol v5) instead of its receive time, so answers land
     * on the correct day and time even if delivery is delayed. No-op when no
     * habit is selected.
     */
    fun sendQuestionsIncrement(answerTimestampMs: Long = System.currentTimeMillis()) {
        val habitName = getHabitId()
        if (habitName.isBlank()) return
        sendBroadcastSafely(ACTION_INCREMENT) {
            putExtra(EXTRA_HABIT_ID, habitName)
            putExtra(EXTRA_SLOT, SLOT_QUESTIONS)
            putExtra(EXTRA_TIMESTAMP, answerTimestampMs)
        }
        DebugLog.i("Tail", "increment sent for habit '$habitName'")
    }

    // ── Retroactive backfill ──────────────────────────────────────────────

    /** Result of the retroactive backfill. */
    data class BackfillResult(
        /** Number of distinct dates sent to Tail. */
        val dates: Int,
        /** Total answers covered (past + today so far). */
        val answers: Int
    )

    /**
     * Sends the FULL per-date answer history — everything from the past and
     * today so far — to the connected Tail habit. Tail REPLACES the stored
     * value for each date, so the operation is idempotent and today's count
     * lands exactly on the number answered so far today.
     *
     * Protocol v5: the per-date answer TIMES ride along in the same broadcast
     * (`EXTRA_TIMES_JSON`), so Tail's schedule timeline shows every past
     * session at the time of day it actually happened — questions answered in
     * close succession merge into a single session block there via Tail's
     * 30-minute merge gap.
     *
     * No-op (result with the aggregation only) when no habit is selected.
     */
    fun backfillAnswers(
        answers: List<AnswerRecord>,
        zone: ZoneId = ZoneId.systemDefault()
    ): BackfillResult {
        val byDate = aggregateAnswersByDate(answers, zone)
        if (habitSelected && byDate.isNotEmpty()) {
            val byDateTimes = aggregateAnswerTimesByDate(answers, zone)
            sendCountsForDates(byDate, byDateTimes)
            DebugLog.i(
                "Tail",
                "backfill sent: ${byDate.size} dates, ${answers.size} answers " +
                    "(${byDateTimes.values.sumOf { it.size }} timestamps)"
            )
        }
        return BackfillResult(dates = byDate.size, answers = answers.size)
    }

    /**
     * Fires the SET broadcast carrying a date→count map as compact JSON:
     * `{"2026-01-15": 12, "2026-01-16": 5}` — plus, when provided, the
     * date→times map `{"2026-01-15": ["09:13:02", ...], ...}` so Tail can
     * place each past session on its schedule timeline.
     */
    private fun sendCountsForDates(dateCounts: Map<String, Int>, dateTimes: Map<String, List<String>>) {
        val habitName = getHabitId()
        if (habitName.isBlank() || dateCounts.isEmpty()) return
        val json = buildString {
            append("{")
            dateCounts.entries.forEachIndexed { i, (date, count) ->
                if (i > 0) append(",")
                append("\"").append(date).append("\":").append(count)
            }
            append("}")
        }
        sendBroadcastSafely(ACTION_SET_HABIT_VALUES) {
            putExtra(EXTRA_HABIT_ID, habitName)
            putExtra(EXTRA_SLOT, SLOT_QUESTIONS)
            putExtra(EXTRA_VALUES_JSON, json)
            if (dateTimes.isNotEmpty()) {
                putExtra(EXTRA_TIMES_JSON, timesJson(dateTimes))
            }
        }
    }

    /** Compact JSON for the times payload: `{"date": ["HH:mm:ss", ...], ...}`. */
    private fun timesJson(dateTimes: Map<String, List<String>>): String {
        val obj = JSONObject()
        for ((date, times) in dateTimes) {
            obj.put(date, JSONArray(times))
        }
        return obj.toString()
    }

    /**
     * Explicit, permission-guarded broadcast to Tail. Never crashes the app:
     * a SecurityException means Tail is not installed (the permission is then
     * defined by nobody, which Android 14+ rejects).
     */
    private inline fun sendBroadcastSafely(action: String, extras: Intent.() -> Unit) {
        try {
            val intent = Intent(action).apply {
                `package` = HABIT_APP_PACKAGE
                extras()
            }
            appContext.sendBroadcast(intent, PERMISSION_TAIL)
        } catch (e: SecurityException) {
            DebugLog.w("Tail", "$action: SecurityException — Tail app likely not installed. ${e.message}")
        } catch (e: Exception) {
            DebugLog.w("Tail", "$action: unexpected error — ${e.message}")
        }
    }

    companion object {
        private const val PREFS = "inuit_tail_prefs"
        private const val KEY_HABIT_ID = "habit_id_questions"
        private const val KEY_HABIT_NAME = "habit_name_questions"

        /** Informational slot name sent with every broadcast. */
        const val SLOT_QUESTIONS = "QUESTIONS_ANSWERED"

        /** Package name of the Tail habit-tracking app. */
        const val HABIT_APP_PACKAGE = "com.example.tail"

        /**
         * Content Provider URI exposed by Tail.
         * Authority: com.example.tail.provider   Path: /habits
         */
        val HABITS_CONTENT_URI: Uri =
            Uri.parse("content://com.example.tail.provider/habits")

        /** Column names returned by the Tail app's Content Provider. */
        const val COL_HABIT_NAME = "habit_name"

        /** Broadcast action Tail's HabitIncrementReceiver listens for. */
        const val ACTION_INCREMENT = "com.example.tail.ACTION_INCREMENT_HABIT"

        /** Broadcast action Tail's SET-values receiver listens for (backfill). */
        const val ACTION_SET_HABIT_VALUES = "com.example.tail.ACTION_SET_HABIT_VALUES"

        /** Intent extra: the habit name (String). */
        const val EXTRA_HABIT_ID = "EXTRA_HABIT_ID"

        /**
         * Protocol v5 — Intent extra (Long, epoch millis): the exact moment
         * the increment event HAPPENED (the answer time). Tail stamps its
         * schedule-timeline timestamp at this moment instead of receive time.
         */
        const val EXTRA_TIMESTAMP = "EXTRA_TIMESTAMP"

        /**
         * Protocol v5 — Intent extra (String, JSON object): per-date answer
         * times `{"yyyy-MM-dd": ["HH:mm:ss", ...], ...}` sent with the SET
         * action so backfilled history lands on Tail's schedule timeline.
         */
        const val EXTRA_TIMES_JSON = "EXTRA_TIMES_JSON"

        /** Intent extra: originating Inuit slot name (informational). */
        const val EXTRA_SLOT = "inuit_slot"

        /** Intent extra: JSON object `{"yyyy-MM-dd": <count:Int>, ...}` for the SET action. */
        const val EXTRA_VALUES_JSON = "EXTRA_VALUES_JSON"

        /**
         * Signature-level permission declared by Tail. Used as the read
         * permission on its ContentProvider and as the receiverPermission
         * argument to sendBroadcast.
         */
        const val PERMISSION_TAIL = "com.example.tail.permission.TAIL_INTEGRATION"
    }
}
