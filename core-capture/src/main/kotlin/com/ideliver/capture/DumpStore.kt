package com.ideliver.capture

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * On-device sink for captured fixtures. One JSONL file per day under the app's
 * internal storage — never external, never networked. Offer text can contain
 * customer addresses, so nothing here leaves the device on its own; the only
 * way out is the user-initiated export ([DumpExporter]).
 *
 * Writes are serialized onto a single background thread so the listener's
 * main-thread callback never blocks on disk I/O.
 */
class DumpStore(context: Context, private val filePrefix: String = "dump") {

    // App-private; safe to hold the application context indirectly via filesDir.
    private val rootDir: File = File(context.filesDir, DIR)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "fixture-dump-io") }

    /** The file the current day's records append to. */
    fun currentFile(): File {
        rootDir.mkdirs()
        val day = LocalDate.now(ZoneId.systemDefault())
        return File(rootDir, "$filePrefix-$day.jsonl")
    }

    /** All dump files on disk, oldest name first. */
    fun files(): List<File> =
        rootDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Appends one already-serialized JSON record as a line. Non-blocking. */
    fun append(jsonLine: String) {
        io.execute {
            runCatching {
                currentFile().appendText(jsonLine + "\n")
            }
        }
    }

    companion object {
        const val DIR = "capture-dumps"
    }
}
