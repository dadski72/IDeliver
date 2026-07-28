package com.ideliver.capture

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * Configuration for the Phase 1 fixture-dump harness.
 *
 * [ENABLED] is a *permanent* debug flag, not scaffolding to be deleted: the
 * harness ships in the codebase so parser breaks can always be diagnosed from
 * freshly captured fixtures. Flip it off to stop writing without removing code.
 *
 * [TARGET_PACKAGES] are the only apps whose notifications are ever recorded.
 * Everything else the listener sees is ignored and never touches disk.
 *
 * NOTE: verify these package names on-device before relying on them — driver
 * app package ids are not documented and can change. Dasher and the Uber Driver
 * app (which carries Uber Eats offers) are the current best-known values.
 */
object FixtureDump {

    const val ENABLED: Boolean = true

    val TARGET_PACKAGES: Set<String> = setOf(
        "com.doordash.driverapp", // DoorDash "Dasher"
        "com.ubercab.driver",     // Uber Driver (delivers Uber Eats offers)
    )

    /**
     * Whether a notification is worth recording.
     *
     * Verified from a real shift: a DoorDash shift is dominated by *persistent
     * status UI* — the turn-by-turn navigation notification alone updated ~7,400
     * times in 3 hours and leaked customer addresses, while only 7 records were
     * actual offers. That firehose is what makes the dump grow without bound.
     *
     * So: always keep anything offer-shaped (even if it is flagged ongoing), and
     * otherwise drop the persistent status notifications — foreground-service,
     * ongoing, and navigation/transport/service categories. This keeps every
     * driver-facing *alert* (offers and future offer variants) without the spam.
     */
    fun shouldCapture(sbn: StatusBarNotification): Boolean {
        if (OfferSignal.isOfferNotification(sbn)) return true

        val n = sbn.notification
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        // Empty group-summary notifications (Aggregate_*Section) — pure noise.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false

        return when (n.category) {
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS -> false
            else -> true
        }
    }
}
