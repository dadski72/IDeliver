package com.ideliver.parse

/** DoorDash pay model, read from the offer screen. */
enum class EarnMode { ORDER, TIME }

/**
 * What we can recover from a DoorDash offer screen's accessibility node text.
 * Everything is nullable — a snapshot taken mid-transition may only have some of
 * it, and the caller decides when a reading is complete enough to use.
 */
data class ParsedOfferScreen(
    val offerId: String?,
    val store: String?,
    val payCents: Int?,      // flat guaranteed total, incl. tip (earn by order)
    val hourlyCents: Int?,   // guaranteed $/active hr (earn by time)
    val promoCents: Int?,    // promo already included in payCents (e.g. Platinum)
    val miles: Double?,
    val minutes: Int?,
    val mode: EarnMode?,
    val stops: Int,          // dropoffs on this offer (1 = single, 2+ = batch)
    val isAddToRoute: Boolean, // "Add to route": incremental to an active delivery
)

/**
 * Parses the text nodes of a DoorDash offer screen. Verified against real
 * fixtures (2026-07-25): the screen surfaces a UUID, a "$X.XX Guaranteed" total
 * or "$X.XX/active hr" rate, a "N mi • M min" line, and the store after "Pickup".
 */
object OfferScreenParser {

    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val HOURLY = Regex("""\$(\d+\.\d{2})\s*/\s*active hr""")
    private val GUARANTEED = Regex("""\$(\d+\.\d{2})\s+Guaranteed""")
    private val PROMO = Regex("""Includes\s+\$(\d+\.\d{2})""")
    private val DIST_TIME = Regex("""(\d+(?:\.\d+)?)\s*mi\s*[•·]\s*(\d+)\s*min""")

    fun parse(texts: List<String>): ParsedOfferScreen? {
        var offerId: String? = null
        var payCents: Int? = null
        var hourlyCents: Int? = null
        var promoCents: Int? = null
        var miles: Double? = null
        var minutes: Int? = null
        var mode: EarnMode? = null
        var store: String? = null

        texts.forEachIndexed { i, t ->
            if (offerId == null) UUID.find(t)?.let { offerId = it.value }

            HOURLY.find(t)?.let {
                hourlyCents = dollarsToCents(it.groupValues[1])
                mode = EarnMode.TIME
            }
            GUARANTEED.find(t)?.let {
                payCents = dollarsToCents(it.groupValues[1])
            }
            // Sum every "Includes $X …" promo line (Platinum, Peak Pay, etc.) so
            // stacked promos are all excluded from the tip estimate.
            PROMO.find(t)?.let {
                promoCents = (promoCents ?: 0) + (dollarsToCents(it.groupValues[1]) ?: 0)
            }
            DIST_TIME.find(t)?.let {
                miles = it.groupValues[1].toDoubleOrNull()
                minutes = it.groupValues[2].toIntOrNull()
            }
            // First "Pickup" only — a batch lists several, and the notification
            // names the first, so first keeps the two capture paths consistent.
            if (t == "Pickup" && store == null && i + 1 < texts.size) store = texts[i + 1]
        }

        if (mode == null && payCents != null) mode = EarnMode.ORDER

        // Not an offer screen (or too early a snapshot) if nothing priced landed.
        if (payCents == null && hourlyCents == null && miles == null) return null

        // Batch detection: each delivery has its own "Customer dropoff" row.
        val stops = texts.count { it.equals("Customer dropoff", ignoreCase = true) }.coerceAtLeast(1)
        // "Add to route" tacks an extra stop onto the delivery you're already on:
        // marked by the button, an "Additional …" distance line, or a "+$…" total.
        val isAddToRoute = texts.any { it.equals("Add to route", ignoreCase = true) } ||
            texts.any { it.startsWith("Additional") && DIST_TIME.containsMatchIn(it) } ||
            texts.any { it.trimStart().startsWith("+$") }

        return ParsedOfferScreen(
            offerId, store, payCents, hourlyCents, promoCents, miles, minutes, mode, stops, isAddToRoute,
        )
    }

    private fun dollarsToCents(s: String): Int? =
        s.toDoubleOrNull()?.let { Math.round(it * 100).toInt() }
}
