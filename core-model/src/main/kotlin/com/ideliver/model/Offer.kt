package com.ideliver.model

import java.time.Instant

/**
 * A single delivery offer, normalized away from however it was captured.
 *
 * Every field except [platform], [source], [seenAt], [confidence], and
 * [rawText] is nullable and must stay that way. Notification payloads are thin
 * and inconsistent; a missing value is a real state, never a defaulted one. The
 * rules engine degrades gracefully or returns [Decision.INSUFFICIENT_DATA].
 *
 * [rawText] is always retained — it is how a broken parser gets fixed after the
 * fact, so it must never be dropped even when every other field parsed cleanly.
 */
data class Offer(
    val platform: Platform,
    val source: CaptureSource,
    val payCents: Int?,
    val miles: Double?,
    val estMinutes: Int?,
    val storeName: String?,
    val stops: Int?,
    val seenAt: Instant,
    val confidence: Float,
    val rawText: String,
)
