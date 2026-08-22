package com.example.inuit.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * In-app diagnostics log — the debugging infrastructure for the question engine.
 *
 * Every meaningful event (LLM requests/responses with latency and token usage,
 * MCP handshakes and tool calls, validation drops, verifier flags, errors with
 * context) is recorded here. Entries are:
 *  - kept in an in-memory ring buffer exposed as a StateFlow (Settings → Diagnostics),
 *  - mirrored to logcat (tag "Inuit"),
 *  - persisted to files/inuit_debug.log (rotated) so failures survive app restarts
 *    and can be pulled via `adb shell run-as com.example.inuit cat files/inuit_debug.log`.
 *
 * Safe to use in JVM unit tests: without [init] it simply skips persistence and
 * the logcat mirror is wrapped against "not mocked" failures.
 */
object DebugLog {

    const val INFO = 0
    const val WARN = 1
    const val ERROR = 2

    data class Entry(val ts: Long, val level: Int, val tag: String, val message: String)

    private const val MAX_ENTRIES = 800
    private const val PERSIST_LIMIT_BYTES = 256 * 1024
    private const val PERSIST_KEEP_BYTES = 128 * 1024

    private val buffer = ArrayList<Entry>(256)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Volatile private var logFile: File? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "inuit-debuglog").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Call once from Application.onCreate(). Loads the persisted tail. */
    fun init(filesDir: File) {
        if (logFile != null) return
        val f = File(filesDir, "inuit_debug.log")
        logFile = f
        io.execute {
            val tail = runCatching {
                val bytes = f.readBytes()
                val start = maxOf(0, bytes.size - 64 * 1024)
                val text = String(bytes, start, bytes.size - start, Charsets.UTF_8)
                text.lineSequence().filter { it.length > 20 }.toList().takeLast(400)
            }.getOrDefault(emptyList())
            if (tail.isNotEmpty()) {
                synchronized(buffer) {
                    for (line in tail) buffer.add(parseLine(line) ?: continue)
                    trimLocked()
                    publishLocked()
                }
            }
        }
    }

    fun i(tag: String, message: String) = append(INFO, tag, message)
    fun w(tag: String, message: String) = append(WARN, tag, message)
    fun e(tag: String, message: String, error: Throwable? = null) =
        append(ERROR, tag, if (error != null) "$message — ${error.javaClass.simpleName}: ${error.message}" else message)

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            publishLocked()
        }
        val f = logFile ?: return
        io.execute { runCatching { f.delete() } }
    }

    /** Newest-last plain-text rendering (used by the copy button). */
    fun asText(): String = synchronized(buffer) {
        buffer.joinToString("\n") { formatEntry(it) }
    }

    fun formatEntry(e: Entry): String {
        val lvl = when (e.level) { WARN -> "W"; ERROR -> "E"; else -> "I" }
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(e.ts))
        return "$ts $lvl ${e.tag}: ${e.message}"
    }

    private fun parseLine(line: String): Entry? {
        // format: "MM-dd HH:mm:ss.SSS L tag: message"
        return runCatching {
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).parse(line.take(17))
            val level = when (line.getOrNull(18)) { 'W' -> WARN; 'E' -> ERROR; else -> INFO }
            val rest = line.drop(20)
            val idx = rest.indexOf(": ")
            val tag = if (idx > 0) rest.take(idx) else rest
            val msg = if (idx > 0) rest.drop(idx + 2) else ""
            Entry(ts?.time ?: 0L, level, tag, msg)
        }.getOrNull()
    }

    private fun append(level: Int, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message.replace('\n', ' ').take(1200))
        synchronized(buffer) {
            buffer.add(entry)
            trimLocked()
            publishLocked()
        }
        // Mirror to logcat; guarded for JVM unit tests ("Method d in android.util.Log not mocked").
        try {
            val line = formatEntry(entry)
            when (level) {
                ERROR -> android.util.Log.e("Inuit", line)
                WARN -> android.util.Log.w("Inuit", line)
                else -> android.util.Log.i("Inuit", line)
            }
        } catch (_: Throwable) {
        }
        val f = logFile ?: return
        io.execute {
            runCatching {
                f.appendText(formatEntry(entry) + "\n")
                if (f.length() > PERSIST_LIMIT_BYTES) {
                    val bytes = f.readBytes()
                    val keep = maxOf(0, bytes.size - PERSIST_KEEP_BYTES)
                    var start = keep
                    // snap to next newline so we don't keep half a line
                    while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
                    File(f.parentFile, f.name + ".tmp").apply {
                        writeBytes(bytes.copyOfRange(minOf(start + 1, bytes.size), bytes.size))
                        renameTo(f)
                    }
                }
            }
        }
    }

    private fun trimLocked() {
        while (buffer.size > MAX_ENTRIES) buffer.removeAt(0)
    }

    private fun publishLocked() {
        _entries.value = buffer.toList()
    }
}
