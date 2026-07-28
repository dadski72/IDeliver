package com.ideliver.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.hypot

/**
 * Reads the destination off a DoorDash offer-screen screenshot, on-device.
 *
 * The map shows a blue dot (you), pickup pin(s), and a dropoff (house) pin, over
 * a Mapbox tile with city/street labels. We find the driver's blue dot and the
 * white pins by pixel scan, take the pin farthest from the driver as the dropoff
 * (single offers: you → nearby pickup → far customer), then OCR the map labels
 * and return the one nearest that pin.
 *
 * Deliberately conservative — returns null unless it's confident. Better silent
 * than wrong. v1: thresholds will want on-device tuning against real captures.
 */
object MapReader {

    data class Result(val city: String?, val far: Boolean)

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val NOISE = setOf(
        "mapbox", "new", "delivery", "order", "pickup", "go", "other", "store",
        "just", "now", "customer", "dropoff", "accept", "decline", "active", "tips",
    )

    fun analyze(bitmap: Bitmap, onResult: (Result) -> Unit) {
        runCatching {
            val scale = 2
            val w = bitmap.width / scale
            val h = bitmap.height / scale
            val mapH = (h * 0.60).toInt() // offer panel is the bottom ~40%
            val small = Bitmap.createScaledBitmap(bitmap, w, h, false)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            small.recycle()

            val blue = findBlueDot(px, w, mapH)
            val pins = findPins(px, w, mapH)
            if (blue == null || pins.isEmpty()) {
                onResult(Result(null, false)); return@runCatching
            }
            val dropoff = pins.maxByOrNull { hypot((it.first - blue.first).toDouble(), (it.second - blue.second).toDouble()) }!!
            val dist = hypot((dropoff.first - blue.first).toDouble(), (dropoff.second - blue.second).toDouble())
            val far = dist > w * 0.30

            val dropX = dropoff.first * scale
            val dropY = dropoff.second * scale
            val mapRegionFull = (bitmap.height * 0.60).toInt()
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { onResult(Result(nearestCity(it, dropX, dropY, mapRegionFull), far)) }
                .addOnFailureListener { onResult(Result(null, far)) }
        }.onFailure { onResult(Result(null, false)) }
    }

    /** Centroid of the driver's saturated-blue location dot. */
    private fun findBlueDot(px: IntArray, w: Int, mapH: Int): Pair<Int, Int>? {
        var sx = 0L; var sy = 0L; var n = 0
        for (y in (mapH / 10) until mapH) {
            for (x in 0 until w) {
                val p = px[y * w + x]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                if (b > 170 && r < 130 && b - r > 50 && g in 70..200) { sx += x; sy += y; n++ }
            }
        }
        return if (n > 8) Pair((sx / n).toInt(), (sy / n).toInt()) else null
    }

    /** Compact near-white blobs = map pins (text is thin, the logo is excluded). */
    private fun findPins(px: IntArray, w: Int, mapH: Int): List<Pair<Int, Int>> {
        val white = BooleanArray(w * mapH)
        for (i in white.indices) {
            val p = px[i]
            white[i] = ((p shr 16) and 0xFF) >= 215 && ((p shr 8) and 0xFF) >= 215 && (p and 0xFF) >= 215
        }
        val seen = BooleanArray(w * mapH)
        val pins = mutableListOf<Pair<Int, Int>>()
        val queue = ArrayDeque<Int>()
        for (start in white.indices) {
            if (!white[start] || seen[start]) continue
            var area = 0; var sx = 0L; var sy = 0L
            var minX = w; var maxX = 0; var minY = mapH; var maxY = 0
            queue.clear(); queue.addLast(start); seen[start] = true
            while (queue.isNotEmpty()) {
                val idx = queue.removeLast()
                val x = idx % w; val y = idx / w
                area++; sx += x; sy += y
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && white[idx - 1] && !seen[idx - 1]) { seen[idx - 1] = true; queue.addLast(idx - 1) }
                if (x < w - 1 && white[idx + 1] && !seen[idx + 1]) { seen[idx + 1] = true; queue.addLast(idx + 1) }
                if (y > 0 && white[idx - w] && !seen[idx - w]) { seen[idx - w] = true; queue.addLast(idx - w) }
                if (y < mapH - 1 && white[idx + w] && !seen[idx + w]) { seen[idx + w] = true; queue.addLast(idx + w) }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val cx = (sx / area).toInt(); val cy = (sy / area).toInt()
            val aspect = bw.toDouble() / bh
            val inLogo = cy > mapH * 0.85 && cx < w * 0.40
            val inBanner = cy < mapH * 0.12
            if (area in 120..1600 && aspect in 0.45..2.4 && !inLogo && !inBanner) {
                pins.add(Pair(cx, cy))
            }
        }
        return pins
    }

    /** OCR label (city/street) nearest the dropoff pin, within the map region. */
    private fun nearestCity(text: com.google.mlkit.vision.text.Text, dx: Int, dy: Int, mapH: Int): String? {
        var best: String? = null; var bestD = Double.MAX_VALUE
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val bb = line.boundingBox ?: continue
                if (bb.centerY() > mapH) continue
                val t = line.text.trim()
                if (t.length < 3 || t.length > 22) continue
                if (t.any { it.isDigit() } || !t.any { it.isLetter() }) continue
                if (t.lowercase().split(" ", "/").any { it in NOISE }) continue
                val d = hypot((bb.exactCenterX() - dx).toDouble(), (bb.exactCenterY() - dy).toDouble())
                if (d < bestD) { bestD = d; best = t }
            }
        }
        return best
    }
}
