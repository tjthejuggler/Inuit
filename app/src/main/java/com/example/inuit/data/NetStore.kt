package com.example.inuit.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A Net is a scoped question universe. Everything the user builds —
 * questions, answers, stats, knowledge summaries, frontiers, podcast
 * recommendations — belongs to exactly one net and never crosses over.
 * Global concerns (LLM config, MCP servers, generation tuning, Tail)
 * live in [SettingsStore] and are shared by all nets.
 *
 * The built-in "All" net ([Net.ALL_ID]) covers all human knowledge and
 * owns the pre-nets legacy data.
 */
data class Net(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Free-text scope description fed to every generation prompt. */
    val description: String = "",
    /** Podcast recommendations can be disabled per net (some scopes have
     *  no meaningful podcast coverage). */
    val podcastEnabled: Boolean = true,
    /** Accent availability gates, kept in sync with [sourceWeights]
     *  (weight > 0 ⇔ enabled). They gate PERMISSIONS and data selection
     *  (location permission, Tail habits, source nets); the actual per-batch
     *  DOSAGE comes from [mix]. */
    val locationEnabled: Boolean = false,
    val dateEnabled: Boolean = false,
    /** Other nets whose knowledge base (summaries, weak domains, missed
     *  questions) may season this net's questions. The user picks these;
     *  a net never sources itself. */
    val sourceNetIds: List<String> = emptyList(),
    val tailTextEnabled: Boolean = false,
    /** Which Tail text-input habits this net may draw from — a per-net
     *  subset of the habits Tail itself is willing to share. */
    val tailTextHabits: List<String> = emptyList(),
    /** Per-source question distribution (percent) — see [SourceMix]. Empty
     *  map means "not configured yet": [mix] then derives legacy defaults
     *  from the accent booleans (each enabled accent gets a small sprinkle
     *  share, the rest is core). */
    val sourceWeights: Map<String, Int> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
) {
    val isAll: Boolean get() = id == ALL_ID

    /** The normalized per-source distribution actually used by generation. */
    fun mix(): Map<String, Int> =
        if (sourceWeights.isEmpty())
            SourceMix.legacy(locationEnabled, dateEnabled, sourceNetIds.isNotEmpty(), tailTextEnabled)
        else SourceMix.normalize(sourceWeights)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("podcast", podcastEnabled)
        put("loc", locationEnabled)
        put("date", dateEnabled)
        put("srcNets", JSONArray(sourceNetIds))
        put("tailText", tailTextEnabled)
        put("tailHabits", JSONArray(tailTextHabits))
        put("mix", JSONObject(mix()))
        put("ts", createdAt)
    }

    companion object {
        const val ALL_ID = "all"

        /** The immutable, undeletable default net spanning all knowledge. */
        val ALL = Net(id = ALL_ID, name = "All", description = "", podcastEnabled = true, createdAt = 0L)

        fun fromJson(o: JSONObject): Net {
            val locationEnabled = o.optBoolean("loc", false)
            val dateEnabled = o.optBoolean("date", false)
            val sourceNetIds = o.optJSONArray("srcNets").toStringList().filter { it.isNotBlank() }
            val tailTextEnabled = o.optBoolean("tailText", false)
            // Mix migration: nets saved before source mixes existed keep
            // working — their accent toggles become small legacy shares.
            val mixObj = o.optJSONObject("mix")
            val weights = if (mixObj != null) {
                val raw = HashMap<String, Int>()
                for (k in mixObj.keys()) raw[k] = mixObj.optInt(k, 0)
                SourceMix.normalize(raw)
            } else null
            return Net(
                id = o.optString("id", ALL_ID).ifBlank { ALL_ID },
                name = o.optString("name").trim().ifEmpty { "Net" },
                description = o.optString("description"),
                podcastEnabled = o.optBoolean("podcast", true),
                locationEnabled = locationEnabled,
                dateEnabled = dateEnabled,
                sourceNetIds = sourceNetIds,
                tailTextEnabled = tailTextEnabled,
                tailTextHabits = o.optJSONArray("tailHabits").toStringList().filter { it.isNotBlank() }.distinct(),
                sourceWeights = weights
                    ?: SourceMix.legacy(locationEnabled, dateEnabled, sourceNetIds.isNotEmpty(), tailTextEnabled),
                createdAt = o.optLong("ts", 0L)
            )
        }
    }
}

/**
 * Per-net distribution of question SOURCES, in percent of each generated
 * batch. The user chooses how much of a net's questions come from each
 * flavor; [Net.mix] always returns a normalized map (core + the four
 * accents, summing to 100).
 */
object SourceMix {
    /** Adaptive core: the net's own scope, driven by user state/frontiers. */
    const val CORE = "core"
    /** Questions tied to the phone's current region. */
    const val LOCATION = "location"
    /** Questions tied to today (this date in history, anniversaries…). */
    const val DATE = "date"
    /** Questions anchored in knowledge from other nets. */
    const val CROSS_NET = "crossNet"
    /** Questions inspired by the user's Tail life-log entries. */
    const val TAIL_TEXT = "tailText"

    val ACCENTS = listOf(LOCATION, DATE, CROSS_NET, TAIL_TEXT)

    /** Accents combined can never fully take over a net — core keeps a floor. */
    const val MAX_TOTAL_ACCENTS = 80

    /** Share an accent got when it was a plain on/off toggle (legacy migration). */
    const val LEGACY_ACCENT_PERCENT = 8

    /** Clamps, scales and completes a raw weight map into a full
     *  core + accents distribution summing to 100. Pure — unit tested. */
    fun normalize(raw: Map<String, Int>): Map<String, Int> {
        var accents = ACCENTS.associateWith { k -> (raw[k] ?: 0).coerceIn(0, 100) }
        val total = accents.values.sum()
        if (total > MAX_TOTAL_ACCENTS) {
            val scale = MAX_TOTAL_ACCENTS.toDouble() / total
            accents = accents.mapValues { (_, v) -> Math.round(v * scale).toInt() }
        }
        val out = HashMap<String, Int>()
        for (k in ACCENTS) out[k] = accents[k] ?: 0
        out[CORE] = 100 - accents.values.sum()
        return out
    }

    /** Pre-mix defaults: every toggled-on accent gets a small sprinkle
     *  share; the rest is core. Pure — unit tested. */
    fun legacy(
        location: Boolean,
        date: Boolean,
        crossNet: Boolean,
        tailText: Boolean
    ): Map<String, Int> = normalize(
        buildMap {
            if (location) put(LOCATION, LEGACY_ACCENT_PERCENT)
            if (date) put(DATE, LEGACY_ACCENT_PERCENT)
            if (crossNet) put(CROSS_NET, LEGACY_ACCENT_PERCENT)
            if (tailText) put(TAIL_TEXT, LEGACY_ACCENT_PERCENT)
        }
    )
}

/**
 * Registry of nets + which net is active. Persisted as a tiny JSON file
 * (`inuit_nets.json`); the per-net question/answer/podcast data lives in
 * [QuestionStore], one file per net.
 *
 * [QuestionStore] observes [activeNet] and swaps its in-memory state; the
 * [onNetDeleted] callback (wired in AppGraph) lets it drop the deleted
 * net's file without a circular constructor dependency.
 */
class NetStore(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "NetStore"
        private const val FILE = "inuit_nets.json"
        private const val MAX_NETS = 50

        /** Parses the registry file content → (activeId, nets). Pure — unit tested. */
        fun parse(text: String): Pair<String, List<Net>> {
            val root = JSONObject(text)
            val nets = ArrayList<Net>()
            val arr = root.optJSONArray("nets") ?: JSONArray()
            for (i in 0 until arr.length()) {
                nets.add(Net.fromJson(arr.optJSONObject(i) ?: continue))
            }
            // Invariants: the All net always exists, exactly once, first.
            val withoutAll = nets.filter { !it.isAll }
            val all = if (nets.any { it.isAll }) nets.first { it.isAll } else Net.ALL
            val fixed = listOf(all) + withoutAll
            val active = root.optString("active").ifBlank { Net.ALL_ID }
            val activeOk = if (fixed.any { it.id == active }) active else Net.ALL_ID
            return activeOk to fixed
        }

        /** Serializes the registry. Pure — unit tested. */
        fun serialize(activeId: String, nets: List<Net>): String = JSONObject().apply {
            put("version", 1)
            put("active", activeId)
            put("nets", JSONArray().apply { nets.forEach { put(it.toJson()) } })
        }.toString()
    }

    private val lock = Any()
    private val file = File(context.filesDir, FILE)

    private val _nets = MutableStateFlow<List<Net>>(listOf(Net.ALL))
    /** All nets; the All net is always first. */
    val nets: StateFlow<List<Net>> = _nets.asStateFlow()

    private val _activeNet = MutableStateFlow(Net.ALL)
    /** The net the whole app currently operates on. */
    val activeNet: StateFlow<Net> = _activeNet.asStateFlow()

    /** Set by AppGraph: drops the deleted net's data file from QuestionStore. */
    var onNetDeleted: ((String) -> Unit)? = null

    /**
     * Set by AppGraph: synchronously switches QuestionStore to the new net.
     * Invoked INSIDE the lock and BEFORE the [activeNet] flow publishes, so
     * every collector that reacts to the switch (MainViewModel, generator,
     * podcasts) already sees the store operating on the new net. Without
     * this, the store's own async collector races the ViewModel's — the
     * ViewModel could read the outgoing net's pending question (a new net
     * showed All's questions) or write a null pending into the wrong net
     * (All got stuck on "generating questions").
     */
    var onNetChanged: ((String) -> Unit)? = null

    init {
        load()
    }

    fun active(): Net = _activeNet.value

    /** Switches the active net; no-op for unknown ids. */
    fun setActive(id: String) {
        synchronized(lock) {
            val net = _nets.value.firstOrNull { it.id == id } ?: return
            if (net.id == _activeNet.value.id) return
            onNetChanged?.invoke(net.id) // store must switch before anyone observes it
            _activeNet.value = net
            persist()
        }
    }

    /**
     * Creates a net (from a UI draft) and makes it active immediately — a
     * brand-new net starts empty (no questions, no stats, no podcasts) with
     * zero carryover. Source-net ids are validated against existing nets.
     */
    fun createNet(draft: Net): Net? {
        if (draft.name.trim().isEmpty()) return null
        synchronized(lock) {
            if (_nets.value.size >= MAX_NETS) return null
            val known = _nets.value.mapTo(HashSet()) { it.id }
            val net = draft.copy(
                name = draft.name.trim(),
                description = draft.description.trim(),
                sourceNetIds = draft.sourceNetIds.filter { it != draft.id && it in known }.distinct()
            )
            _nets.value = _nets.value + net
            onNetChanged?.invoke(net.id) // store must switch before anyone observes it
            _activeNet.value = net
            persist()
            return net
        }
    }

    /** Updates an existing net. The All net may only toggle podcasts and
     *  accents (no rename/re-scope); user nets are fully editable. */
    fun updateNet(net: Net) {
        synchronized(lock) {
            val known = _nets.value.mapTo(HashSet()) { it.id }
            val sources = net.sourceNetIds.filter { it != net.id && it in known }.distinct()
            if (net.isAll) {
                _nets.value = _nets.value.map {
                    if (it.isAll) it.copy(
                        podcastEnabled = net.podcastEnabled,
                        locationEnabled = net.locationEnabled,
                        dateEnabled = net.dateEnabled,
                        sourceNetIds = sources,
                        tailTextEnabled = net.tailTextEnabled,
                        tailTextHabits = net.tailTextHabits.distinct(),
                        sourceWeights = net.mix()
                    ) else it
                }
            } else {
                _nets.value = _nets.value.map {
                    if (it.id == net.id) {
                        net.copy(
                            name = net.name.trim().ifEmpty { it.name },
                            sourceNetIds = sources,
                            tailTextHabits = net.tailTextHabits.distinct(),
                            sourceWeights = net.mix()
                        )
                    } else it
                }
            }
            if (_activeNet.value.id == net.id) {
                _activeNet.value = _nets.value.first { it.id == net.id }
            }
            persist()
        }
    }

    /**
     * Deletes a user net (the All net is immortal). If it was active, the
     * All net becomes active first; then [onNetDeleted] fires so
     * QuestionStore can erase the net's data file.
     */
    fun deleteNet(id: String) {
        if (id == Net.ALL_ID) return
        synchronized(lock) {
            val exists = _nets.value.any { it.id == id }
            if (!exists) return
            if (_activeNet.value.id == id) {
                onNetChanged?.invoke(Net.ALL_ID) // store off the doomed net before we publish
                _activeNet.value = _nets.value.first { it.isAll }
            }
            _nets.value = _nets.value
                .filter { it.id != id }
                // Other nets must not keep referencing the deleted net as a source.
                .map { n -> if (id in n.sourceNetIds) n.copy(sourceNetIds = n.sourceNetIds - id) else n }
            persist()
        }
        onNetDeleted?.invoke(id)
    }

    // ── Persistence ──────────────────────────────────────────────────────

    private fun persist() {
        val payload = serialize(_activeNet.value.id, _nets.value)
        scope.launch(Dispatchers.IO) {
            try {
                val tmp = File(file.parentFile, FILE + ".tmp")
                tmp.writeText(payload)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "persist failed", e)
            }
        }
    }

    private fun load() {
        try {
            if (!file.exists()) {
                _nets.value = listOf(Net.ALL)
                _activeNet.value = Net.ALL
                return
            }
            val (activeId, nets) = parse(file.readText())
            _nets.value = nets
            _activeNet.value = nets.first { it.id == activeId }
            Log.i(TAG, "loaded ${nets.size} nets, active=${_activeNet.value.name}")
        } catch (e: Exception) {
            Log.e(TAG, "load failed — falling back to All net", e)
            _nets.value = listOf(Net.ALL)
            _activeNet.value = Net.ALL
        }
    }
}
