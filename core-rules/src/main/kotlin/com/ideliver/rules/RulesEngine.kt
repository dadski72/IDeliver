package com.ideliver.rules

import com.ideliver.model.Decision
import com.ideliver.model.Offer
import com.ideliver.model.RuleSettings
import com.ideliver.model.Verdict

/**
 * The one place a silent bug directly costs the driver money — pure, and unit
 * tested against synthetic offers.
 *
 * The headline economics are **true-cost, over all legs the driver actually
 * drives**, not just the paid delivery leg:
 *  - Paid: the store→dropoff delivery ([Offer.miles], [Offer.estMinutes] which is
 *    the whole accept→complete window).
 *  - Unpaid: the empty return home/deadhead, estimated as
 *    [RuleSettings.deadheadFactor] × delivery miles.
 *
 * So $/mile is earnings ÷ (delivery + return) miles, and $/hour is earnings ÷ the
 * whole cycle time including the unpaid return. [Offer.payCents] carries the
 * offer's guaranteed earnings (by-order total incl. capped tip; by-time = rate ×
 * active minutes, tips excluded and thus a floor).
 *
 * Limits (each skipped when 0): trip miles ≤ radius, trip minutes ≤ max, and the
 * true-cost $/mi and $/hr must clear their minimums. Any breach → DECLINE with a
 * reason; all clear → ACCEPT; no miles and no minutes → INSUFFICIENT_DATA.
 */
object RulesEngine {

    private const val RETURN_MPH = 30.0

    // A quality rate within this fraction of the minimum is MARGINAL, not DECLINE —
    // because declining costs acceptance rate, a near-miss isn't worth a hard "no".
    private const val MARGINAL_BAND = 0.85

    // Declines of headroom above the acceptance-rate floor at or below which a
    // DECLINE softens to MARGINAL — you can't afford to say no.
    private const val AR_BUFFER_CUSHION = 3

    fun evaluate(
        offer: Offer,
        settings: RuleSettings,
        acceptanceRate: Int? = null,
        isAddToRoute: Boolean = false,
        isEarnByTime: Boolean = false,
    ): Verdict {
        val miles = offer.miles
        val minutes = offer.estMinutes
        val earnedCents = offer.payCents
        val stops = (offer.stops ?: 1).coerceAtLeast(1)
        // Add-to-route is incremental — you continue your route, no empty return.
        val deadhead = if (isAddToRoute) 0.0 else settings.deadheadFactor
        val reasons = mutableListOf<String>()
        var hardDecline = false
        var marginal = false

        // Hard operational limits the driver set — over the limit is a firm no.
        if (settings.radiusIsLimited && miles != null && miles > settings.radiusMiles) {
            reasons.add("%.1f mi over %.1f mi limit".format(miles, settings.radiusMiles))
            hardDecline = true
        }
        // The time limit is an earn-by-ORDER lever (long trip = poor $/order). In
        // earn-by-TIME you're paid for that time, so a longer trip is *more* pay —
        // the limit is skipped (as is add-to-route). Batches get N× the budget.
        val effectiveMaxMinutes = settings.maxMinutes * stops
        if (settings.timeIsLimited && !isAddToRoute && !isEarnByTime &&
            minutes != null && minutes > effectiveMaxMinutes
        ) {
            reasons.add("$minutes min over $effectiveMaxMinutes min limit")
            hardDecline = true
        }

        // True-cost rates over all legs (delivery + unpaid return).
        var perMile: Double? = null
        var perHour: Double? = null
        if (earnedCents != null) {
            val earned = earnedCents / 100.0
            if (miles != null && miles > 0) {
                perMile = earned / (miles * (1.0 + deadhead))
            }
            if (minutes != null && minutes > 0) {
                val returnMinutes = (miles ?: 0.0) * deadhead / RETURN_MPH * 60.0
                perHour = earned / ((minutes + returnMinutes) / 60.0)
            }
        }

        // Quality thresholds: a near-miss is MARGINAL, a clear miss is DECLINE.
        if (settings.minDollarsPerMile > 0 && perMile != null) {
            when {
                perMile < settings.minDollarsPerMile * MARGINAL_BAND -> {
                    reasons.add("$%.2f/mi under $%.2f/mi".format(perMile, settings.minDollarsPerMile)); hardDecline = true
                }
                perMile < settings.minDollarsPerMile -> {
                    reasons.add("$%.2f/mi just under $%.2f/mi".format(perMile, settings.minDollarsPerMile)); marginal = true
                }
            }
        }
        if (settings.minDollarsPerHour > 0 && perHour != null) {
            when {
                perHour < settings.minDollarsPerHour * MARGINAL_BAND -> {
                    reasons.add("$%.0f/hr under $%.0f/hr".format(perHour, settings.minDollarsPerHour)); hardDecline = true
                }
                perHour < settings.minDollarsPerHour -> {
                    reasons.add("$%.0f/hr just under $%.0f/hr".format(perHour, settings.minDollarsPerHour)); marginal = true
                }
            }
        }

        val hasData = miles != null || minutes != null
        var decision = when {
            !hasData -> Decision.INSUFFICIENT_DATA
            hardDecline -> Decision.DECLINE
            marginal -> Decision.MARGINAL
            else -> Decision.ACCEPT
        }

        // Acceptance-rate protection: near the floor, don't recommend declining —
        // the decline costs more (lost Platinum) than a below-target offer.
        if (decision == Decision.DECLINE && acceptanceRate != null && settings.platinumTargetPercent in 1..100) {
            val buffer = acceptanceRate - settings.platinumTargetPercent
            if (buffer <= AR_BUFFER_CUSHION) decision = Decision.MARGINAL
        }

        return Verdict(
            decision = decision,
            dollarsPerMile = perMile,
            dollarsPerHour = perHour,
            netAfterMileage = null,
            reasons = reasons,
        )
    }
}
