package com.ideliver.capture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider

/**
 * User-initiated export of the captured fixtures — the "no adb required" path
 * from CLAUDE.md. Hands the JSONL files to the system share sheet via a
 * FileProvider so the user chooses where they go; the app itself never
 * transmits anything.
 */
object DumpExporter {

    /**
     * Fires a share chooser for every dump file on disk.
     * @return the number of files shared (0 if nothing has been captured yet).
     */
    fun export(activity: Activity): Int {
        val files = DumpStore(activity).files()
        if (files.isEmpty()) return 0

        val authority = "${activity.packageName}.fileprovider"
        val uris = ArrayList<Uri>(files.size)
        files.forEach { uris.add(FileProvider.getUriForFile(activity, authority, it)) }

        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/json"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Export capture fixtures"))
        return files.size
    }
}
