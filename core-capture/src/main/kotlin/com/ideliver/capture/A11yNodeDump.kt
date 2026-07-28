package com.ideliver.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Serializes an accessibility node tree to one JSONL record. This is a *fixture*
 * of the offer screen — a faithful, undecoded snapshot so we can find where pay,
 * miles, and time actually live before writing a parser. Read-only: it never
 * touches, focuses, or actions a node.
 *
 * Only nodes carrying text or a content description are emitted, each with its
 * `viewIdResourceName`, class, and bounds — enough to locate the pay field
 * stably later without dumping the entire (huge, PII-heavy) view hierarchy.
 */
object A11yNodeDump {

    private const val MAX_NODES = 400
    private const val MAX_DEPTH = 40

    fun build(packageName: String?, root: AccessibilityNodeInfo?): String {
        val record = JSONObject()
        record.put("seenAt", Instant.now().toString())
        record.put("source", "ACCESSIBILITY")
        record.put("package", packageName ?: JSONObject.NULL)
        record.put("offerStore", OfferSignal.lastStore ?: JSONObject.NULL)

        val nodes = JSONArray()
        if (root != null) {
            val budget = intArrayOf(MAX_NODES)
            collect(root, 0, nodes, budget)
        }
        record.put("nodes", nodes)
        return record.toString()
    }

    /** Flat list of every node's text, for parsing (mirrors [build]'s traversal). */
    fun collectTexts(root: AccessibilityNodeInfo?): List<String> {
        val out = ArrayList<String>()
        if (root != null) collectTexts(root, 0, out, intArrayOf(MAX_NODES))
        return out
    }

    private fun collectTexts(node: AccessibilityNodeInfo, depth: Int, out: MutableList<String>, budget: IntArray) {
        if (budget[0] <= 0 || depth > MAX_DEPTH) return
        node.text?.toString()?.takeIf { it.isNotEmpty() }?.let {
            out.add(it)
            budget[0]--
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, depth + 1, out, budget) }
        }
    }

    private fun collect(node: AccessibilityNodeInfo, depth: Int, out: JSONArray, budget: IntArray) {
        if (budget[0] <= 0 || depth > MAX_DEPTH) return

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty()) {
            val n = JSONObject()
            if (!text.isNullOrEmpty()) n.put("text", text)
            if (!desc.isNullOrEmpty()) n.put("desc", desc)
            node.viewIdResourceName?.let { n.put("id", it) }
            node.className?.let { n.put("class", it.toString()) }
            val b = Rect().also { node.getBoundsInScreen(it) }
            n.put("bounds", "${b.left},${b.top},${b.right},${b.bottom}")
            out.put(n)
            budget[0]--
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, depth + 1, out, budget)
        }
    }
}
