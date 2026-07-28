package com.ideliver.model

/** The gig platform an offer came from. */
enum class Platform {
    DOORDASH,
    UBER_EATS,
}

/**
 * Where the raw text of an offer was captured. Downstream of [Offer] nothing
 * should branch on this except diagnostics — it exists to explain confidence,
 * not to drive rules.
 */
enum class CaptureSource {
    NOTIFICATION,
    ACCESSIBILITY,
    OCR,
}

/**
 * The rules engine's call on an offer.
 *
 * [MARGINAL] is a first-class state, not a rounding of ACCEPT/DECLINE: most
 * offers land near the line and a two-state badge just trains the driver to
 * keep second-guessing. [INSUFFICIENT_DATA] is returned when the notification
 * was too thin to evaluate — never guessed around.
 */
enum class Decision {
    ACCEPT,
    MARGINAL,
    DECLINE,
    INSUFFICIENT_DATA,
}
