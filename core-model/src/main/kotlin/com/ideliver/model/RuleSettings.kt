package com.ideliver.model

import kotlin.math.roundToInt

/**
 * Driver-configured limits used to turn an [Offer] into a [Verdict].
 *
 * A limit of 0 means "no limit / any allowed" — the default. [homeBase] is an
 * address or "lat,lng" string; true distance-from-home evaluation is not wired
 * yet (the offer screen doesn't expose the dropoff coordinates), so for now
 * [radiusMiles] is compared against the offer's total trip miles.
 */
data class RuleSettings(
    val homeBase: String? = null,
    val radiusMiles: Double = 0.0,
    val maxMinutes: Int = 0,
    // DoorDash base pay, estimated as floor + per-mile × miles, used only to
    // estimate the tip on earn-by-order offers (tip ≈ total − promo − base).
    // Base scales with distance in reality, so both knobs are driver-tunable.
    // Base pay EXCLUDING promos (the app subtracts promoCents separately). Real
    // data (Jul 2026): "DoorDash pay" was $2.50 for short orders but that included
    // a $0.50 Platinum promo, so true base ≈ $2.00, flat for short hops and rising
    // for long hauls (DoorDash keeps base near minimum until ~7 mi, then scales).
    val baseFloorCents: Int = 200,
    val basePerMileCents: Int = 15,
    // Unpaid empty return, as a fraction of the delivery distance (1.0 = you drive
    // back the full delivery distance with no order; 0 = you always get one at the
    // dropoff). Used for the true-cost $/mi and $/hr over all legs.
    val deadheadFactor: Double = 1.0,
    // Optional accept/decline thresholds on the true-cost rates (0 = no limit).
    val minDollarsPerHour: Double = 0.0,
    val minDollarsPerMile: Double = 0.0,
    // Acceptance-rate floor to protect (Platinum is 70% in most markets, 80% in
    // some). Near it, a DECLINE softens to MARGINAL so you don't dig below.
    val platinumTargetPercent: Int = 70,
    // Your market's Earn-by-Time guaranteed rate, for the post-dash mode advisor
    // (compares your actual base pay/active-hr against this).
    val byTimeHourlyCents: Int = 1300,
) {
    val radiusIsLimited: Boolean get() = radiusMiles > 0.0
    val timeIsLimited: Boolean get() = maxMinutes > 0

    /** Distance-scaled base-pay estimate. Falls back to the floor if miles unknown. */
    fun estimatedBaseCents(miles: Double?): Int =
        baseFloorCents + ((miles ?: 0.0) * basePerMileCents).roundToInt()
}
