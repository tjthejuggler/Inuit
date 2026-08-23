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
    val createdAt: Long = System.currentTimeMillis()
) {
    val isAll: Boolean get() = id == ALL_ID

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("podcast", podcastEnabled)
        put("ts", createdAt)
    }

    companion object {
        const val ALL_ID = "all"

        /** The immutable, undeletable default net spanning all knowledge. */
        val ALL = Net(id = ALL_ID, name = "All", description = "", podcastEnabled = true, createdAt = 0L)

        fun fromJson(o: JSONObject): Net = Net(
            id = o.optString("id", ALL_ID).ifBlank { ALL_ID },
            name = o.optString("name").trim().ifEmpty { "Net" },
            description = o.optString("description"),
            podcastEnabled = o.optBoolean("podcast", true),
            createdAt = o.optLong("ts", 0L)
        )
    }
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
     * Creates a net and makes it active immediately — a brand-new net starts
     * empty (no questions, no stats, no podcasts) with zero carryover.
     */
    fun createNet(name: String, description: String, podcastEnabled: Boolean): Net? {
        val net = Net(
            name = name.trim().ifEmpty { return null },
            description = description.trim(),
            podcastEnabled = podcastEnabled
        )
        synchronized(lock) {
            if (_nets.value.size >= MAX_NETS) return null
            _nets.value = _nets.value + net
            onNetChanged?.invoke(net.id) // store must switch before anyone observes it
            _activeNet.value = net
            persist()
        }
        return net
    }

    /** Updates name/description/podcast flag of an existing net. */
    fun updateNet(net: Net) {
        synchronized(lock) {
            if (net.isAll) {
                // The All net may only toggle podcasts / description, not rename.
                _nets.value = _nets.value.map {
                    if (it.isAll) it.copy(podcastEnabled = net.podcastEnabled) else it
                }
            } else {
                _nets.value = _nets.value.map {
                    if (it.id == net.id) net.copy(name = net.name.trim().ifEmpty { it.name }) else it
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
            _nets.value = _nets.value.filter { it.id != id }
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
