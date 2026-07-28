package com.ideliver.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Human-readable, newest-first feed shown in the app's log view. This is
 * deliberately separate from the raw JSONL fixtures in [DumpStore]: it lives in
 * its own file ([FILE]) so the in-app "Clear" button can wipe the *display* log
 * without ever touching the captured JSON data.
 *
 * App-process-lifetime singleton. Services publish to it from the background;
 * the UI observes [entries]. Survives Activity destruction, and reloads from
 * disk after a process restart.
 */
object EventLog {

    data class Entry(val at: Instant, val text: String)

    private const val FILE = "display-events.jsonl"
    private const val CAP = 300

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "event-log-io") }

    @Volatile
    private var loaded = false

    /** Loads persisted entries once (newest first). Idempotent. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val restored = runCatching {
            val f = file(context)
            if (!f.exists()) return@runCatching emptyList()
            f.readLines().mapNotNull { line ->
                runCatching {
                    val o = JSONObject(line)
                    Entry(Instant.parse(o.getString("at")), o.getString("text"))
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        // File is chronological; display is newest first.
        _entries.value = restored.asReversed().take(CAP)
    }

    fun add(context: Context, text: String) {
        ensureLoaded(context)
        val entry = Entry(Instant.now(), text)
        _entries.update { (listOf(entry) + it).take(CAP) }
        val appContext = context.applicationContext
        io.execute {
            runCatching {
                val o = JSONObject().put("at", entry.at.toString()).put("text", entry.text)
                file(appContext).appendText(o.toString() + "\n")
            }
        }
    }

    /** Clears the display feed only — leaves capture-dumps/ JSONL untouched. */
    fun clear(context: Context) {
        _entries.value = emptyList()
        val appContext = context.applicationContext
        io.execute { runCatching { file(appContext).delete() } }
    }

    private fun file(context: Context) = File(context.filesDir, FILE)
}
