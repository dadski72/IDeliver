package com.ideliver.capture

import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Turns a [StatusBarNotification] into one JSONL record. Captures exactly what
 * Phase 1 needs and nothing that requires interpretation — the whole point is a
 * faithful, undecoded snapshot the parser can be developed against later:
 *
 *  - package, key, postTime
 *  - every `extras` key and value (stringified, structure preserved best-effort)
 *  - text flattened out of any `RemoteViews` (custom-layout notifications whose
 *    `extras` come back near-empty)
 *  - whether this event was a fresh post or an in-place update
 *
 * Extraction (including RemoteViews inflation) runs on the caller's thread —
 * the listener's main thread — because View inflation wants a Looper. Only the
 * disk write is offloaded.
 */
object DumpRecords {

    fun build(context: Context, sbn: StatusBarNotification, isUpdate: Boolean): String {
        val n = sbn.notification
        val record = JSONObject()

        record.put("seenAt", Instant.now().toString())
        record.put("event", if (isUpdate) "update" else "post")
        record.put("package", sbn.packageName)
        record.put("key", sbn.key)
        record.put("postTime", sbn.postTime)
        record.put("flags", n.flags)
        record.put("category", n.category ?: JSONObject.NULL)

        val extras = JSONObject()
        val bundle = n.extras
        if (bundle != null) {
            for (key in bundle.keySet()) {
                @Suppress("DEPRECATION")
                extras.put(key, stringify(bundle.get(key)))
            }
        }
        record.put("extras", extras)

        val remoteViews = JSONObject()
        @Suppress("DEPRECATION") // legacy RemoteViews fields are the point of the dump
        run {
            remoteViews.put("contentView", flatten(context, n.contentView))
            remoteViews.put("bigContentView", flatten(context, n.bigContentView))
            remoteViews.put("headsUpContentView", flatten(context, n.headsUpContentView))
        }
        record.put("remoteViewsText", remoteViews)

        return record.toString()
    }

    /** Best-effort JSON-friendly rendering of an arbitrary extras value. */
    private fun stringify(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Boolean, is Int, is Long, is Double -> value
        is CharSequence -> value.toString()
        is Array<*> -> JSONArray().apply { value.forEach { put(stringify(it)) } }
        else -> value.toString()
    }

    /**
     * Inflates a [RemoteViews] and collects the text of every [TextView] in the
     * resulting tree. Cross-package inflation can fail for all sorts of reasons;
     * failure just yields an empty array rather than losing the whole record.
     */
    private fun flatten(context: Context, rv: RemoteViews?): JSONArray {
        val out = JSONArray()
        if (rv == null) return out
        runCatching {
            val root = rv.apply(context, FrameLayout(context))
            collectText(root, out)
        }
        return out
    }

    private fun collectText(view: View, out: JSONArray) {
        if (view is TextView) {
            val text = view.text?.toString()
            if (!text.isNullOrEmpty()) out.put(text)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectText(view.getChildAt(i), out)
            }
        }
    }
}
