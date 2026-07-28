package com.ideliver.model

/**
 * The result of evaluating an [Offer] against the driver's settings.
 *
 * The derived economics ([dollarsPerMile], [dollarsPerHour], [netAfterMileage])
 * are nullable because they depend on offer fields that may be absent. [reasons]
 * carries the human-readable justification shown on the overlay — e.g.
 * "below $2.00/mi", "blacklisted store".
 */
data class Verdict(
    val decision: Decision,
    val dollarsPerMile: Double?,
    val dollarsPerHour: Double?,
    val netAfterMileage: Double?,
    val reasons: List<String>,
)
